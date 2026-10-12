package contractwatcher.monitoring;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "MONITORING_TEST_DATABASE_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@ExtendWith(OutputCaptureExtension.class)
class ReportIngestionTests {

	private static final String INTEGRATION = "22222222-2222-4222-8222-222222222222";
	private static final String CONTRACT = "33333333-3333-4333-8333-333333333333";
	private static final String MISSING = """
			{"fieldName":"customer_id","type":"RequiredFieldMissing","expectedType":"String","actualType":null}
			""".strip();
	private static final String MISMATCH = """
			{"fieldName":"amount","type":"TypeMismatch","expectedType":"Number","actualType":"String"}
			""".strip();
	private final HttpClient http = HttpClient.newHttpClient();
	private ServletWebServerApplicationContext application;
	private JsonMapper json;
	private JdbcTemplate jdbc;
	private URI endpoint;
	private String database;
	private boolean databaseCreated;

	@BeforeAll
	void startOnOwnedDatabase() throws SQLException {
		try (Connection admin = adminConnection(); var statement = admin.createStatement()) {
			try (var result = statement.executeQuery("SELECT current_database()")) {
				assertThat(result.next()).isTrue();
				assertThat(result.getString(1)).as("Нужна отдельная тестовая БД monitoring_test_...")
						.matches("monitoring_test_[a-z0-9_]+");
			}
			database = "monitoring_test_ingestion_" + UUID.randomUUID().toString().replace("-", "");
			statement.execute("CREATE DATABASE " + database);
			databaseCreated = true;
		}
		String adminUrl = System.getenv("MONITORING_TEST_DATABASE_URL");
		int queryStart = adminUrl.indexOf('?');
		int databaseStart = adminUrl.lastIndexOf('/', queryStart < 0 ? adminUrl.length() : queryStart) + 1;
		assertThat(databaseStart).isPositive();
		String url = adminUrl.substring(0, databaseStart) + database
				+ (queryStart < 0 ? "" : adminUrl.substring(queryStart));
		String user = System.getenv("MONITORING_TEST_DATABASE_USER");
		String password = System.getenv("MONITORING_TEST_DATABASE_PASSWORD");
		jdbc = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
		application = (ServletWebServerApplicationContext) SpringApplication.run(Application.class,
				"--server.port=0", "--spring.config.import=", "--spring.datasource.url=" + url,
				"--spring.datasource.username=" + user, "--spring.datasource.password=" + password,
				"--logging.level.com.zaxxer.hikari.HikariDataSource=WARN",
				"--logging.level.com.zaxxer.hikari.pool.HikariPool=WARN", "--logging.level.org.springframework=WARN",
				"--spring.main.banner-mode=off");
		json = application.getBean(JsonMapper.class);
		endpoint = URI.create("http://127.0.0.1:" + application.getWebServer().getPort() + "/internal/v1/reports");
	}

	@AfterAll
	void closeAndDropOwnedDatabase() throws SQLException {
		http.close();
		if (application != null) {
			application.close();
		}
		if (databaseCreated) {
			assertThat(database).matches("monitoring_test_ingestion_[a-f0-9]{32}");
			try (Connection admin = adminConnection(); var statement = admin.createStatement()) {
				statement.execute("DROP DATABASE " + database);
			}
		}
	}

	@Test
	void acceptsPassedAndConfirmsCommittedDatabaseTime() throws Exception {
		String id = UUID.randomUUID().toString();
		HttpResponse<String> response = post(request(id, "Passed", ""), "application/json");
		assertThat(response.statusCode()).isEqualTo(201);
		assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json");
		assertThat(response.headers().firstValue("Location")).isEmpty();
		JsonNode body = json.readTree(response.body());
		assertThat(body.size()).isEqualTo(4);
		assertThat(body.path("integrationId").asString()).isEqualTo(INTEGRATION);
		assertThat(body.path("reportId").asString()).isEqualTo(id);
		assertThat(body.path("duplicate").asBoolean()).isFalse();
		assertThat(body.path("receivedAt").asString()).endsWith("Z");
		var stored = report(id);
		assertThat(stored).containsEntry("outcome", "PASSED").containsEntry("contract_version", 3)
				.containsEntry("contract_id", UUID.fromString(CONTRACT));
		Instant storedTime = jdbc.queryForObject("""
				SELECT received_at FROM monitoring.reports WHERE external_report_id = ?
				""", (row, index) -> row.getObject(1, OffsetDateTime.class).toInstant(), UUID.fromString(id));
		assertThat(Instant.parse(body.path("receivedAt").asString())).isEqualTo(storedTime);
		assertThat(violations(id)).isEmpty();
	}

	@Test
	void acceptsFailedWithMultipleAndIdenticalViolationsAndNullableActualType() throws Exception {
		String id = UUID.randomUUID().toString();
		String nullValue = """
				{"fieldName":" optional ","type":"NullNotAllowed","expectedType":"Boolean"}
				""".strip();
		assertThat(post(request(id, "Failed", String.join(",", MISSING, MISMATCH, MISMATCH, nullValue)),
				"application/json").statusCode()).isEqualTo(201);
		assertThat(report(id)).containsEntry("outcome", "FAILED");
		var rows = violations(id);
		assertThat(rows).hasSize(4);
		assertThat(rows).extracting(row -> row.get("ordinal")).containsExactly(0, 1, 2, 3);
		assertThat(rows.get(0)).containsEntry("violation_kind", "REQUIRED_FIELD_MISSING")
				.containsEntry("expected_type", "string").containsEntry("actual_type", null);
		assertThat(rows.get(1)).containsEntry("violation_kind", "TYPE_MISMATCH")
				.containsEntry("expected_type", "number").containsEntry("actual_type", "string");
		var repeated = new HashMap<>(rows.get(1));
		repeated.put("ordinal", 2);
		assertThat(rows.get(2)).isEqualTo(repeated);
		assertThat(rows.get(3)).containsEntry("field_name", " optional ")
				.containsEntry("violation_kind", "NULL_NOT_ALLOWED").containsEntry("actual_type", null);
		assertThat(rows).allSatisfy(row -> assertThat(row).containsEntry("group_id", null));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM monitoring.violation_groups", Integer.class)).isZero();
	}

	@Test
	void rejectsMalformedJsonAndCoercionsWithoutWriting() throws Exception {
		String valid = request(UUID.randomUUID().toString(), "Passed", "");
		var before = counts();
		for (String invalid : List.of("", "{", valid.replace("\"Passed\"", "0"),
				valid.replace("\"Passed\"", "\" Passed \""), valid.replace("\"Passed\"", "\"Unknown\""),
				valid.replace(":3,", ":3.0,"), valid.replace(":3,", ":\"3\","),
				request(UUID.randomUUID().toString(), "Failed", MISSING.replace("\"customer_id\"", "42")))) {
			assertProblem(post(invalid, "application/json"), 400, "INVALID_REQUEST");
		}
		assertThat(counts()).isEqualTo(before);
	}

	@Test
	void rejectsRequiredFieldsAndMetadataBeforeWriting() throws Exception {
		String valid = request(UUID.randomUUID().toString(), "Passed", "");
		var before = counts();
		for (String invalid : List.of("{}", valid.replace(INTEGRATION, "1-1-1-1-1"),
				valid.replace(":3,", ":0,"), request(UUID.randomUUID().toString(), "Failed", ""),
				request(UUID.randomUUID().toString(), "Passed", MISSING),
				request(UUID.randomUUID().toString(), "Failed", "null"),
				request(UUID.randomUUID().toString(), "Failed", MISSING.replace(",\"expectedType\":\"String\"", "")),
				request(UUID.randomUUID().toString(), "Failed", MISMATCH.replace("\"actualType\":\"String\"", "\"actualType\":null")),
				request(UUID.randomUUID().toString(), "Failed", MISSING.replace("\"actualType\":null", "\"actualType\":\"Number\"")))) {
			JsonNode problem = assertProblem(post(invalid, "application/json"), 400, "VALIDATION_REPORT_INVALID");
			assertThat(problem.path("errors").isObject()).isTrue();
			assertThat(problem.path("errors").size()).isPositive();
		}
		JsonNode problem = assertProblem(post(request(UUID.randomUUID().toString(), "Failed",
				MISMATCH.replace("\"actualType\":\"String\"", "\"actualType\":\"Number\"")), "application/json"),
				400, "VALIDATION_REPORT_INVALID");
		assertThat(problem.path("errors").path("report.violations[0].actualType").isArray()).isTrue();
		assertThat(counts()).isEqualTo(before);
	}

	@Test
	void rejectsUnsupportedMediaTypeWithoutWriting() throws Exception {
		var before = counts();
		assertProblem(post(request(UUID.randomUUID().toString(), "Passed", ""), "text/plain"),
				415, "UNSUPPORTED_MEDIA_TYPE");
		assertThat(counts()).isEqualTo(before);
	}

	@Test
	void leavesExistingReportUnchangedOnConflict() throws Exception {
		String id = UUID.randomUUID().toString();
		assertThat(post(request(id, "Failed", MISSING), "application/json").statusCode()).isEqualTo(201);
		var saved = report(id);
		var savedViolations = violations(id);
		var before = counts();
		assertProblem(post(request(id, "Passed", "").replace(CONTRACT, UUID.randomUUID().toString()),
				"application/json"), 409, "REPORT_ID_CONFLICT");
		assertThat(report(id)).isEqualTo(saved);
		assertThat(violations(id)).isEqualTo(savedViolations);
		assertThat(counts()).isEqualTo(before);
	}

	@Test
	void rollsBackReportAndEarlierViolationOnLateInsertFailure(CapturedOutput output) throws Exception {
		jdbc.execute("""
				CREATE FUNCTION monitoring.test_reject_late_violation() RETURNS trigger LANGUAGE plpgsql AS $$
				BEGIN
				  IF NEW.ordinal = 1 THEN
				    RAISE EXCEPTION 'driver-detail-must-stay-private' USING ERRCODE = '23505';
				  END IF;
				  RETURN NEW;
				END $$
				""");
		try {
			jdbc.execute("""
					CREATE TRIGGER test_reject_late BEFORE INSERT ON monitoring.report_violations
					FOR EACH ROW EXECUTE FUNCTION monitoring.test_reject_late_violation()
					""");
			var before = counts();
			assertProblem(post(request(UUID.randomUUID().toString(), "Failed", MISSING + "," + MISMATCH),
					"application/json"), 500, "INTERNAL_ERROR");
			assertThat(counts()).isEqualTo(before);
			assertThat(output).doesNotContain("driver-detail", "org.postgresql", "jdbc:");
		} finally {
			jdbc.execute("DROP FUNCTION monitoring.test_reject_late_violation() CASCADE");
		}
	}

	@Test
	void returnsNoConfirmationAndRollsBackOnDeferredCommitFailure(CapturedOutput output) throws Exception {
		jdbc.execute("""
				CREATE FUNCTION monitoring.test_reject_commit() RETURNS trigger LANGUAGE plpgsql AS $$
				BEGIN
				  RAISE EXCEPTION 'driver-detail-must-stay-private' USING ERRCODE = '23514';
				END $$
				""");
		try {
			jdbc.execute("""
					CREATE CONSTRAINT TRIGGER test_reject_commit AFTER INSERT ON monitoring.reports
					DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION monitoring.test_reject_commit()
					""");
			var before = counts();
			assertProblem(post(request(UUID.randomUUID().toString(), "Failed", MISSING + "," + MISMATCH),
					"application/json"), 500, "INTERNAL_ERROR");
			assertThat(counts()).isEqualTo(before);
			assertThat(output).doesNotContain("driver-detail", "org.postgresql", "jdbc:");
		} finally {
			jdbc.execute("DROP FUNCTION monitoring.test_reject_commit() CASCADE");
		}
	}

	@Test
	void returnsUnavailableAfterLosingDatabaseConnection(CapturedOutput output) throws Exception {
		jdbc.execute("""
				CREATE FUNCTION monitoring.test_lose_connection() RETURNS trigger LANGUAGE plpgsql AS $$
				BEGIN
				  PERFORM pg_terminate_backend(pg_backend_pid());
				  RETURN NEW;
				END $$
				""");
		try {
			jdbc.execute("""
					CREATE TRIGGER test_lose_connection AFTER INSERT ON monitoring.reports
					FOR EACH ROW EXECUTE FUNCTION monitoring.test_lose_connection()
					""");
			var before = counts();
			assertProblem(post(request(UUID.randomUUID().toString(), "Passed", ""), "application/json"),
					503, "STORAGE_UNAVAILABLE");
			assertThat(counts()).isEqualTo(before);
			assertThat(output).doesNotContain("org.postgresql", "SQLException", "jdbc:");
		} finally {
			jdbc.execute("DROP FUNCTION monitoring.test_lose_connection() CASCADE");
		}
	}

	private Connection adminConnection() throws SQLException {
		return DriverManager.getConnection(System.getenv("MONITORING_TEST_DATABASE_URL"),
				System.getenv("MONITORING_TEST_DATABASE_USER"), System.getenv("MONITORING_TEST_DATABASE_PASSWORD"));
	}

	private String request(String id, String status, String violations) {
		return """
				{"integrationId":"%s","contractId":"%s","report":{
				"reportId":"%s","contractVersion":3,"status":"%s","violations":[%s]}}
				""".formatted(INTEGRATION, CONTRACT, id, status, violations);
	}

	private HttpResponse<String> post(String body, String contentType) throws Exception {
		return http.send(HttpRequest.newBuilder(endpoint).header("Content-Type", contentType)
				.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
	}

	private JsonNode assertProblem(HttpResponse<String> response, int status, String code) {
		assertThat(response.statusCode()).isEqualTo(status);
		assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json");
		JsonNode body = json.readTree(response.body());
		assertThat(body.path("type").asString()).isEqualTo("about:blank");
		assertThat(body.path("status").asInt()).isEqualTo(status);
		assertThat(body.path("code").asString()).isEqualTo(code);
		assertThat(body.path("instance").asString()).isEqualTo("/internal/v1/reports");
		assertThat(body.path("errors").isObject()).isTrue();
		assertThat(response.body()).doesNotContain("driver-detail", "org.postgresql", "SQLException", "jdbc:");
		assertThat(body.has("receivedAt")).isFalse();
		return body;
	}

	private Map<String, Object> report(String id) {
		return jdbc.queryForMap("SELECT * FROM monitoring.reports WHERE external_report_id = ?", UUID.fromString(id));
	}

	private List<Map<String, Object>> violations(String id) {
		return jdbc.queryForList("""
				SELECT v.ordinal, v.field_name, v.violation_kind, v.expected_type, v.actual_type, v.group_id
				FROM monitoring.report_violations v JOIN monitoring.reports r USING (report_row_id)
				WHERE r.external_report_id = ? ORDER BY v.ordinal
				""", UUID.fromString(id));
	}

	private List<Integer> counts() {
		return List.of(jdbc.queryForObject("SELECT count(*) FROM monitoring.reports", Integer.class),
				jdbc.queryForObject("SELECT count(*) FROM monitoring.report_violations", Integer.class));
	}
}
