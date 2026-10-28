package contractwatcher.monitoring;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import contractwatcher.monitoring.model.ProjectSummariesQuery;
import contractwatcher.monitoring.repository.ProjectSummariesRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "MONITORING_TEST_DATABASE_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProjectSummariesPostgresTests {
	private final HttpClient http = HttpClient.newHttpClient();
	private final JsonMapper json = new JsonMapper();
	private ServletWebServerApplicationContext application;
	private JdbcTemplate jdbc;
	private String database;
	private String url;
	private URI base;
	private static final Instant FROM = Instant.parse("2026-10-27T21:00:00Z");
	private static final Instant TO = Instant.parse("2026-10-28T21:00:00Z");

	@BeforeAll
	void startOwnedDatabase() throws Exception {
		String adminUrl = System.getenv("MONITORING_TEST_DATABASE_URL");
		try (var connection = open(adminUrl); var statement = connection.createStatement();
				var rows = statement.executeQuery("SELECT current_database()")) {
			assertThat(rows.next()).isTrue();
			assertThat(rows.getString(1)).matches("monitoring_test_[a-z0-9_]+");
		}
		database = "monitoring_test_summaries_" + UUID.randomUUID().toString().replace("-", "");
		try (var connection = open(adminUrl); var statement = connection.createStatement()) {
			statement.execute("CREATE DATABASE " + database);
		}
		int query = adminUrl.indexOf('?');
		int start = adminUrl.lastIndexOf('/', query < 0 ? adminUrl.length() : query) + 1;
		url = adminUrl.substring(0, start) + database + (query < 0 ? "" : adminUrl.substring(query));
		jdbc = new JdbcTemplate(new DriverManagerDataSource(url, System.getenv("MONITORING_TEST_DATABASE_USER"),
				System.getenv("MONITORING_TEST_DATABASE_PASSWORD")));
		application = (ServletWebServerApplicationContext) SpringApplication.run(Application.class,
				"--server.port=0", "--spring.config.import=", "--spring.datasource.url=" + url,
				"--spring.datasource.username=" + System.getenv("MONITORING_TEST_DATABASE_USER"),
				"--spring.datasource.password=" + System.getenv("MONITORING_TEST_DATABASE_PASSWORD"),
				"--logging.level.com.zaxxer.hikari=WARN", "--logging.level.org.springframework=WARN",
				"--spring.datasource.hikari.connection-init-sql=SET TIME ZONE '"
						+ System.getProperty("monitoring.test.database.timezone", "UTC") + "'", "--spring.main.banner-mode=off");
		base = URI.create("http://127.0.0.1:" + application.getWebServer().getPort());
	}

	@AfterAll
	void closeOwnedDatabase() throws Exception {
		http.close();
		if (application != null) { application.close(); }
		if (database != null) {
			assertThat(database).matches("monitoring_test_summaries_[a-f0-9]{32}");
			try (var connection = open(System.getenv("MONITORING_TEST_DATABASE_URL")); var statement = connection.createStatement()) {
				statement.execute("DROP DATABASE " + database);
			}
		}
	}

	@Test
	void globalOrderFilterSubsetZeroSummariesAndEmptyPage() throws Exception {
		var ids = List.of(new UUID(0x7fffffffffffffffL, 0), new UUID(0x8000000000000000L, 0),
				new UUID(0x8000000000000000L, 1), new UUID(0x8000000000000000L, 2),
				new UUID(0x8000000000000000L, 3), new UUID(0x8000000000000000L, 4));
		for (int index : List.of(1, 2)) {
			for (int n = 0; n < 3; n++) { insert(ids.get(index), "FAILED", FROM.plusSeconds(n)); }
			insert(ids.get(index), "PASSED", FROM.plusSeconds(10 - index));
		}
		insert(ids.get(3), "FAILED", FROM);
		insert(ids.get(4), "PASSED", FROM);
		insert(ids.get(5), "FAILED", FROM.minusNanos(1000));
		UUID outside = UUID.randomUUID();
		for (int n = 0; n < 100; n++) { insert(outside, "FAILED", FROM); }
		var expected = List.of(ids.get(1), ids.get(2), ids.get(3), ids.get(0), ids.get(4), ids.get(5));
		var actual = new ArrayList<String>();
		var counts = tableCounts();
		var reversed = new ArrayList<>(ids);
		Collections.reverse(reversed);
		for (int offset : List.of(0, 2, 4, 6)) {
			var page = page(reversed, false, offset, 2, FROM, TO);
			assertThat(page.path("total").asLong()).isEqualTo(6);
			page.path("items").forEach(item -> actual.add(item.path("integrationId").asString()));
			assertThat(page(ids, false, offset, 2, FROM, TO)).isEqualTo(page);
		}
		assertThat(actual).containsExactlyElementsOf(expected.stream().map(UUID::toString).toList());
		assertThat(page(ids, false, 3, 1, FROM, TO).path("items").get(0).path("lastReceivedAt").isNull()).isTrue();
		for (int offset : List.of(0, 2, 4)) {
			var filtered = page(ids, true, offset, 2, FROM, TO);
			assertThat(filtered.path("total").asLong()).isEqualTo(3);
			assertThat(filtered.path("items").size()).isEqualTo(offset == 0 ? 2 : offset == 2 ? 1 : 0);
		}
		var subset = page(List.of(ids.get(2), ids.get(4), ids.get(2)), false, 0, 20, FROM, TO);
		assertThat(subset.path("total").asLong()).isEqualTo(2);
		assertThat(subset.path("items").get(0).path("passedReports").asLong()).isEqualTo(1);
		assertThat(page(List.of(ids.get(0)), true, 0, 20, FROM, TO).path("total").asLong()).isZero();
		assertThat(tableCounts()).isEqualTo(counts);
	}

	@Test
	void boundsMatchSingleSummaryAndHistoryAndOpenPeriods() throws Exception {
		UUID id = UUID.randomUUID();
		for (Instant time : List.of(FROM.minusNanos(1000), FROM, TO.minusNanos(1000), TO, TO.plusNanos(1000))) {
			insert(id, "FAILED", time);
		}
		for (var bounds : List.of(new Instant[]{FROM, TO}, new Instant[]{FROM, null}, new Instant[]{null, TO}, new Instant[]{null, null})) {
			var item = page(List.of(id), false, 0, 20, bounds[0], bounds[1]).path("items").get(0);
			String query = period(bounds[0], bounds[1]).replace("\"from\":\"", "from=").replace("\"to\":\"", "to=")
					.replace("\"", "").replace(",", "&");
			String suffix = query.isEmpty() ? "" : "?" + query;
			assertThat(get("/internal/v1/integrations/" + id + "/summary" + suffix)).isEqualTo(item);
			assertThat(get("/internal/v1/integrations/" + id + "/reports" + suffix).path("total").asLong())
					.isEqualTo(item.path("totalReports").asLong());
		}
		var item = page(List.of(id), false, 0, 20, FROM, TO).path("items").get(0);
		assertThat(item.path("totalReports").asLong()).isEqualTo(2);
		assertThat(Instant.parse(item.path("lastReceivedAt").asString())).isEqualTo(TO.minusNanos(1000));
	}

	@Test
	void calendarDaysUseProvidedUtcBoundsIncludingDst() throws Exception {
		for (var example : List.of(new String[]{"Europe/Berlin", "2026-03-29", "23"},
				new String[]{"Europe/Berlin", "2026-10-25", "25"}, new String[]{"Europe/Moscow", "2026-10-28", "24"})) {
			var day = java.time.LocalDate.parse(example[1]);
			var zone = java.time.ZoneId.of(example[0]);
			Instant from = day.atStartOfDay(zone).toInstant();
			Instant to = day.plusDays(1).atStartOfDay(zone).toInstant();
			assertThat(java.time.Duration.between(from, to).toHours()).isEqualTo(Long.parseLong(example[2]));
			UUID id = UUID.randomUUID();
			for (Instant time : List.of(from.minusNanos(1000), from, to.minusNanos(1000), to)) { insert(id, "FAILED", time); }
			assertThat(page(List.of(id), false, 0, 20, from, to).path("items").get(0).path("totalReports").asLong()).isEqualTo(2);
		}
	}

	@Test
	void tenThousandUniqueIdsAreAllReachableAndChunkedBodyIsBounded() throws Exception {
		var ids = java.util.stream.IntStream.range(0, 10000).mapToObj(n -> new UUID(3, n)).toList();
		var all = new ArrayList<String>();
		for (int offset = 0; offset < 10000; offset += 100) {
			var page = page(ids, false, offset, 100, null, null);
			assertThat(page.path("total").asLong()).isEqualTo(10000);
			page.path("items").forEach(item -> all.add(item.path("integrationId").asString()));
		}
		assertThat(all).containsExactlyElementsOf(ids.stream().map(UUID::toString).toList());
		for (int size : List.of(524288, 524289)) {
			String prefix = "{\"integrationIds\":[]}";
			byte[] bytes = (prefix + " ".repeat(size - prefix.length())).getBytes(StandardCharsets.UTF_8);
			var publisher = HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.ByteArrayInputStream(bytes));
			var response = http.send(HttpRequest.newBuilder(base.resolve("/internal/v1/integration-summaries/query"))
					.header("Content-Type", "application/json").POST(publisher).build(), HttpResponse.BodyHandlers.ofString());
			assertThat(response.statusCode()).isEqualTo(size == 524288 ? 200 : 413);
		}
	}

	@Test
	void oneStatementSnapshotSurvivesConcurrentCommitIncludingEmptyPage() throws Exception {
		for (int offset : List.of(0, 5)) {
			UUID before = UUID.randomUUID();
			UUID added = UUID.randomUUID();
			insert(before, "FAILED", FROM);
			try (var reader = open(url); var writer = open(url); var pool = Executors.newSingleThreadExecutor()) {
				var writerJdbc = new JdbcTemplate(new SingleConnectionDataSource(writer, true));
				writerJdbc.execute("SELECT pg_advisory_lock(181818)");
				int pid = new JdbcTemplate(new SingleConnectionDataSource(reader, true)).queryForObject("SELECT pg_backend_pid()", Integer.class);
				AtomicInteger statements = new AtomicInteger();
				Connection instrumented = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
						(proxy, method, args) -> {
							if (method.getName().equals("prepareStatement")) {
								statements.incrementAndGet();
								args[0] = ((String) args[0]).replace("WITH requested AS MATERIALIZED (",
										"WITH barrier AS MATERIALIZED (SELECT pg_advisory_xact_lock(181818)), requested AS MATERIALIZED (")
										.replace("AS integration_id\n)", "AS integration_id FROM barrier\n)");
							}
							try {
								Object result = method.invoke(reader, args);
								if (result instanceof java.sql.PreparedStatement statement) { statement.setQueryTimeout(10); }
								return result;
							}
							catch (InvocationTargetException exception) { throw exception.getCause(); }
						});
				var repository = new ProjectSummariesRepository(new JdbcTemplate(new SingleConnectionDataSource(instrumented, true)));
				var query = new ProjectSummariesQuery(List.of(before, added), FROM, TO, true, offset, 20);
				var running = pool.submit(() -> repository.query(query));
				long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
				while (jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE pid = ? AND wait_event = 'advisory'", Integer.class, pid) == 0
						&& !running.isDone() && System.nanoTime() < deadline) { Thread.onSpinWait(); }
				assertThat(running.isDone()).isFalse();
				assertThat(jdbc.queryForObject("SELECT wait_event FROM pg_stat_activity WHERE pid = ?", String.class, pid)).isEqualTo("advisory");
				insert(added, "FAILED", FROM);
				writerJdbc.execute("SELECT pg_advisory_unlock(181818)");
				var result = running.get(10, TimeUnit.SECONDS);
				assertThat(statements.get()).isEqualTo(1);
				assertThat(result.total()).isEqualTo(1);
				assertThat(result.items()).hasSize(offset == 0 ? 1 : 0);
				assertThat(page(List.of(before, added), true, offset, 20, FROM, TO).path("total").asLong()).isEqualTo(2);
			}
		}
	}

	@Test
	void duplicateWithSeveralViolationsKeepsFirstSdkDayAndDoesNotChangeCounters() throws Exception {
		UUID id = UUID.randomUUID();
		String request = contractwatcher.monitoring.config.ApiExamples.REQUEST
				.replace("22222222-2222-4222-8222-222222222222", id.toString())
				.replace("66666666-6666-4666-8666-666666666666", UUID.randomUUID().toString())
				.replace("2026-10-19T09:00:00.123456Z", FROM.toString());
		assertThat(postReport(request).statusCode()).isEqualTo(201);
		var before = tableCounts();
		String duplicate = request.replace(FROM.toString(), TO.toString());
		try (var workers = Executors.newFixedThreadPool(3)) {
			var tasks = java.util.stream.IntStream.range(0, 3).mapToObj(n -> workers.submit(() -> postReport(duplicate))).toList();
			for (var task : tasks) {
				var response = task.get(10, TimeUnit.SECONDS);
				assertThat(response.statusCode()).isEqualTo(200);
				assertThat(json.readTree(response.body()).path("duplicate").asBoolean()).isTrue();
			}
		}
		assertThat(tableCounts()).isEqualTo(before);
		var item = page(List.of(id), true, 0, 20, FROM, TO).path("items").get(0);
		assertThat(item.path("failedReports").asLong()).isEqualTo(1);
		assertThat(Instant.parse(item.path("lastReceivedAt").asString())).isEqualTo(FROM);
		assertThat(page(List.of(id), true, 0, 20, TO, null).path("total").asLong()).isZero();
	}

	@Test
	void uncommittedAndRolledBackReportsStayInvisible() throws Exception {
		UUID id = UUID.randomUUID();
		try (var writer = open(url)) {
			writer.setAutoCommit(false);
			new JdbcTemplate(new SingleConnectionDataSource(writer, true)).update(
					"INSERT INTO monitoring.reports(integration_id,external_report_id,contract_id,contract_version,outcome,received_at) VALUES (?,?,?,1,'FAILED',?)",
					id, UUID.randomUUID(), UUID.randomUUID(), FROM.atOffset(java.time.ZoneOffset.UTC));
			assertThat(page(List.of(id), true, 0, 20, FROM, TO).path("total").asLong()).isZero();
			writer.rollback();
			assertThat(page(List.of(id), false, 0, 20, FROM, TO).path("items").get(0).path("totalReports").asLong()).isZero();
		}
	}

	@Test
	void retryAfterHttpTimeoutDoesNotWriteOrAccumulateCounters() throws Exception {
		UUID id = UUID.randomUUID();
		insert(id, "FAILED", FROM);
		var before = tableCounts();
		try (var blocker = open(url)) {
			blocker.setAutoCommit(false);
			try (var statement = blocker.createStatement()) {
				statement.execute("LOCK TABLE monitoring.reports IN ACCESS EXCLUSIVE MODE");
			}
			String body = "{\"integrationIds\":[\"" + id + "\"]}";
			var running = http.sendAsync(HttpRequest.newBuilder(base.resolve("/internal/v1/integration-summaries/query"))
					.timeout(java.time.Duration.ofSeconds(2)).header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
			try {
				long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
				boolean blocked = false;
				while (!running.isDone() && System.nanoTime() < deadline) {
					blocked = Boolean.TRUE.equals(jdbc.queryForObject("""
							SELECT EXISTS (SELECT FROM pg_stat_activity WHERE datname = current_database()
							AND wait_event_type = 'Lock' AND query LIKE 'WITH requested AS MATERIALIZED%')
							""", Boolean.class));
					if (blocked) { break; }
					Thread.onSpinWait();
				}
				assertThat(blocked).isTrue();
				org.assertj.core.api.Assertions.assertThatThrownBy(() -> running.get(5, TimeUnit.SECONDS))
						.hasCauseInstanceOf(java.net.http.HttpTimeoutException.class);
			} finally {
				blocker.rollback();
			}
		}
		for (int repeat = 0; repeat < 2; repeat++) {
			assertThat(page(List.of(id), false, 0, 20, null, null).path("items").get(0).path("failedReports").asLong()).isEqualTo(1);
		}
		assertThat(tableCounts()).isEqualTo(before);
	}

	@Test
	void openApiDescribesStrictRequestAndErrors() throws Exception {
		var api = get("/v3/api-docs");
		var operation = api.path("paths").path("/internal/v1/integration-summaries/query").path("post");
		for (String status : List.of("200", "400", "413", "415", "500", "503")) {
			assertThat(operation.path("responses").has(status)).isTrue();
		}
		var schema = api.path("components").path("schemas").path("IntegrationSummariesRequest");
		assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
		assertThat(schema.path("properties").path("integrationIds").path("maxItems").asInt()).isEqualTo(10000);
	}

	private JsonNode page(List<UUID> ids, boolean failed, int offset, int limit, Instant from, Instant to) throws Exception {
		String period = period(from, to);
		String body = "{\"integrationIds\":" + json.writeValueAsString(ids) + ",\"onlyFailed\":" + failed
				+ ",\"offset\":" + offset + ",\"limit\":" + limit + (period.isEmpty() ? "" : "," + period) + "}";
		var response = http.send(HttpRequest.newBuilder(base.resolve("/internal/v1/integration-summaries/query"))
				.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).isEqualTo(200);
		return json.readTree(response.body());
	}

	private static String period(Instant from, Instant to) {
		return (from == null ? "" : "\"from\":\"" + from + "\"") + (from != null && to != null ? "," : "")
				+ (to == null ? "" : "\"to\":\"" + to + "\"");
	}

	private JsonNode get(String path) throws Exception {
		var response = http.send(HttpRequest.newBuilder(base.resolve(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).isEqualTo(200);
		return json.readTree(response.body());
	}

	private HttpResponse<String> postReport(String body) throws Exception {
		return http.send(HttpRequest.newBuilder(base.resolve("/internal/v1/reports")).header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
	}

	private void insert(UUID id, String outcome, Instant time) {
		jdbc.update("INSERT INTO monitoring.reports (integration_id,external_report_id,contract_id,contract_version,outcome,received_at) VALUES (?,?,?,1,?,?)",
				id, UUID.randomUUID(), UUID.randomUUID(), outcome, time.atOffset(java.time.ZoneOffset.UTC));
	}

	private List<Long> tableCounts() {
		return List.of(jdbc.queryForObject("SELECT count(*) FROM monitoring.reports", Long.class),
				jdbc.queryForObject("SELECT count(*) FROM monitoring.report_violations", Long.class),
				jdbc.queryForObject("SELECT count(*) FROM monitoring.violation_groups", Long.class));
	}

	private static Connection open(String databaseUrl) throws SQLException {
		return DriverManager.getConnection(databaseUrl, System.getenv("MONITORING_TEST_DATABASE_USER"),
				System.getenv("MONITORING_TEST_DATABASE_PASSWORD"));
	}
}
