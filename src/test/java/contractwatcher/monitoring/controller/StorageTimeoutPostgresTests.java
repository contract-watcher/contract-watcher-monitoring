package contractwatcher.monitoring.controller;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import contractwatcher.monitoring.repository.ProjectSummariesRepository;
import contractwatcher.monitoring.service.ProjectSummariesService;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@EnabledIfEnvironmentVariable(named = "MONITORING_TEST_DATABASE_URL", matches = ".+")
class StorageTimeoutPostgresTests {
	@Test
	void statementAndPoolTimeoutReturn503AndRecoverWithoutChangingData() throws Exception {
		String adminUrl = System.getenv("MONITORING_TEST_DATABASE_URL");
		String database = "monitoring_test_timeouts_" + UUID.randomUUID().toString().replace("-", "");
		try (var admin = open(adminUrl); var ddl = admin.createStatement()) {
			try (var rows = ddl.executeQuery("SELECT current_database()")) {
				assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).matches("monitoring_test_[a-z0-9_]+");
			}
			ddl.execute("CREATE DATABASE " + database);
			int query = adminUrl.indexOf('?');
			int start = adminUrl.lastIndexOf('/', query < 0 ? adminUrl.length() : query) + 1;
			String url = adminUrl.substring(0, start) + database + (query < 0 ? "" : adminUrl.substring(query));
			try {
				Flyway.configure().dataSource(url, System.getenv("MONITORING_TEST_DATABASE_USER"), System.getenv("MONITORING_TEST_DATABASE_PASSWORD"))
						.schemas("monitoring").defaultSchema("monitoring").load().migrate();
				try (var reader = open(url); var blocker = open(url)) {
					var jdbc = new JdbcTemplate(new SingleConnectionDataSource(reader, true));
					jdbc.execute("SET statement_timeout = '200ms'");
					blocker.setAutoCommit(false);
					try (var lock = blocker.createStatement()) {
						lock.execute("LOCK TABLE monitoring.reports IN ACCESS EXCLUSIVE MODE");
						try { assertResponse(jdbc, 503); } finally { blocker.rollback(); }
					}
					assertResponse(jdbc, 200);
					assertThat(jdbc.queryForObject("SELECT count(*) FROM monitoring.reports", Long.class)).isZero();
				}
				var config = new HikariConfig(); config.setJdbcUrl(url);
				config.setUsername(System.getenv("MONITORING_TEST_DATABASE_USER"));
				config.setPassword(System.getenv("MONITORING_TEST_DATABASE_PASSWORD"));
				config.setMaximumPoolSize(1); config.setConnectionTimeout(250);
				try (var pool = new HikariDataSource(config)) {
					var jdbc = new JdbcTemplate(pool);
					try (var held = pool.getConnection()) {
						assertThat(held.isValid(2)).isTrue(); assertResponse(jdbc, 503);
					}
					assertResponse(jdbc, 200);
					for (String table : java.util.List.of("reports", "report_violations", "violation_groups")) {
						assertThat(jdbc.queryForObject("SELECT count(*) FROM monitoring." + table, Long.class)).isZero();
					}
				}
				assertWriteTimeoutRollsBackAndRetryUsesSameId(url);
			} finally {
				assertThat(database).matches("monitoring_test_timeouts_[a-f0-9]{32}"); ddl.execute("DROP DATABASE " + database);
			}
		}
	}

	private static void assertResponse(JdbcTemplate jdbc, int status) throws Exception {
		var mvc = MockMvcBuilders.standaloneSetup(new ProjectSummariesController(new ProjectSummariesService(new ProjectSummariesRepository(jdbc))))
				.setControllerAdvice(new ReportExceptionHandler()).build();
		var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ReportExceptionHandler.class);
		var logs = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
		logs.start(); logger.addAppender(logs);
		try {
			var response = mvc.perform(post(ProjectSummariesController.PATH).contentType("application/json")
					.content("{\"integrationIds\":[\"22222222-2222-4222-8222-222222222222\"]}")).andReturn().getResponse();
			assertThat(response.getStatus()).isEqualTo(status);
			var body = new tools.jackson.databind.json.JsonMapper().readTree(response.getContentAsString());
			if (status == 503) {
				assertThat(body.path("code").asString()).isEqualTo("STORAGE_UNAVAILABLE");
				assertThat(body.has("items")).isFalse();
				assertThat(response.getContentAsString()).doesNotContain("jdbc:", "SELECT", "org.postgresql", "HikariPool");
				assertThat(logs.list).hasSize(1);
				assertThat(logs.list.getFirst().getLevel()).isEqualTo(ch.qos.logback.classic.Level.ERROR);
				assertThat(logs.list.getFirst().getFormattedMessage()).contains("timeout=true", "count=1").doesNotContain("jdbc:", "SELECT", "HikariPool");
				assertThat(logs.list.getFirst().getThrowableProxy()).isNull();
			} else {
				assertThat(body.path("total").asLong()).isEqualTo(1);
				assertThat(body.path("items").get(0).path("totalReports").asLong()).isZero();
				assertThat(logs.list).isEmpty();
			}
		} finally { logger.detachAppender(logs); logs.stop(); }
	}

	private static void assertWriteTimeoutRollsBackAndRetryUsesSameId(String url) throws Exception {
		try (var connection = open(url); var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
			var source = new SingleConnectionDataSource(connection, true);
			var jdbc = new JdbcTemplate(source);
			jdbc.execute("SET statement_timeout = '200ms'");
			jdbc.execute("CREATE FUNCTION monitoring.delay_second_violation() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN IF NEW.ordinal = 1 THEN PERFORM pg_sleep(2); END IF; RETURN NEW; END'");
			jdbc.execute("CREATE TRIGGER delay_second_violation BEFORE INSERT ON monitoring.report_violations FOR EACH ROW EXECUTE FUNCTION monitoring.delay_second_violation()");
			context.registerBean(javax.sql.DataSource.class, () -> source);
			context.registerBean(JdbcTemplate.class, () -> jdbc);
			context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,
					() -> new org.springframework.jdbc.support.JdbcTransactionManager(source));
			context.scan("contractwatcher.monitoring.service", "contractwatcher.monitoring.repository");
			context.refresh();
			var mvc = MockMvcBuilders.standaloneSetup(new ReportIngestionController(context.getBean(contractwatcher.monitoring.service.ReportIngestionService.class)))
					.setControllerAdvice(new ReportExceptionHandler()).build();
			var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ReportExceptionHandler.class);
			var logs = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
			logs.start(); logger.addAppender(logs);
			try {
				var response = mvc.perform(post("/internal/v1/reports").contentType("application/json").content(contractwatcher.monitoring.config.ApiExamples.REQUEST)).andReturn().getResponse();
				assertThat(response.getStatus()).isEqualTo(503);
				assertThat(response.getContentAsString()).contains("STORAGE_UNAVAILABLE").doesNotContain("pg_sleep", "INSERT", "jdbc:");
				assertThat(logs.list).hasSize(1);
				assertThat(logs.list.getFirst().getFormattedMessage()).contains("timeout=true", "integrationId=22222222-2222-4222-8222-222222222222", "reportId=66666666-6666-4666-8666-666666666666");
				for (String table : java.util.List.of("reports", "report_violations", "violation_groups")) {
					assertThat(jdbc.queryForObject("SELECT count(*) FROM monitoring." + table, Long.class)).isZero();
				}
			} finally {
				logger.detachAppender(logs); logs.stop(); jdbc.execute("DROP TRIGGER delay_second_violation ON monitoring.report_violations");
				jdbc.execute("DROP FUNCTION monitoring.delay_second_violation()");
			}
			for (int expected : java.util.List.of(201, 200)) {
				assertThat(mvc.perform(post("/internal/v1/reports").contentType("application/json").content(contractwatcher.monitoring.config.ApiExamples.REQUEST))
						.andReturn().getResponse().getStatus()).isEqualTo(expected);
			}
			assertThat(jdbc.queryForObject("SELECT count(*) FROM monitoring.reports", Long.class)).isEqualTo(1);
			assertThat(jdbc.queryForObject("SELECT count(*) FROM monitoring.report_violations", Long.class)).isEqualTo(2);
			assertThat(jdbc.queryForList("SELECT occurrence_count FROM monitoring.violation_groups", Long.class)).containsExactly(2L);
		}
	}

	private static Connection open(String url) throws Exception {
		return DriverManager.getConnection(url, System.getenv("MONITORING_TEST_DATABASE_USER"), System.getenv("MONITORING_TEST_DATABASE_PASSWORD"));
	}
}
