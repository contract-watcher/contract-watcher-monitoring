package contractwatcher.monitoring;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
		Instant storedTime = receivedAt(INTEGRATION, id);
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
		String id = UUID.randomUUID().toString();
		String valid = request(id, "Passed", "");
		assertThat(post(valid, "application/json").statusCode()).isEqualTo(201);
		var saved = report(id);
		var before = counts();
		for (String invalid : List.of("", "{", valid.replace("\"Passed\"", "0"),
				valid.replace("\"Passed\"", "\" Passed \""), valid.replace("\"Passed\"", "\"Unknown\""),
				valid.replace(":3,", ":3.0,"), valid.replace(":3,", ":\"3\","),
				request(UUID.randomUUID().toString(), "Failed", MISSING.replace("\"customer_id\"", "42")))) {
			assertProblem(post(invalid, "application/json"), 400, "INVALID_REQUEST");
		}
		assertThat(counts()).isEqualTo(before);
		assertThat(report(id)).isEqualTo(saved);
		assertThat(violations(id)).isEmpty();
	}

	@Test
	void rejectsRequiredFieldsAndMetadataBeforeWriting() throws Exception {
		String id = UUID.randomUUID().toString();
		String valid = request(id, "Passed", "");
		assertThat(post(valid, "application/json").statusCode()).isEqualTo(201);
		var saved = report(id);
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
		JsonNode problem = assertProblem(post(request(id, "Failed",
				MISMATCH.replace("\"actualType\":\"String\"", "\"actualType\":\"Number\"")), "application/json"),
				400, "VALIDATION_REPORT_INVALID");
		assertThat(problem.path("errors").path("report.violations[0].actualType").isArray()).isTrue();
		assertThat(counts()).isEqualTo(before);
		assertThat(report(id)).isEqualTo(saved);
		assertThat(violations(id)).isEmpty();
	}

	@Test
	void rejectsUnsupportedMediaTypeWithoutWriting() throws Exception {
		String body = request(UUID.randomUUID().toString(), "Passed", "");
		assertThat(post(body, "application/json").statusCode()).isEqualTo(201);
		var before = counts();
		assertProblem(post(body, "text/plain"),
				415, "UNSUPPORTED_MEDIA_TYPE");
		assertThat(counts()).isEqualTo(before);
	}

	@Test
	void confirmsDuplicateWithoutChangingReportViolationsOrCounter() throws Exception {
		String id = UUID.randomUUID().toString();
		String original = request(id, "Failed", MISSING + "," + MISMATCH);
		JsonNode accepted = confirmation(post(original, "application/json"), 201, INTEGRATION, id);
		UUID groupId = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO monitoring.violation_groups
				(group_id, integration_id, contract_id, contract_version, field_name, violation_kind,
				expected_type, occurrence_count, first_seen_at, last_seen_at)
				VALUES (?, ?, ?, 3, 'customer_id', 'REQUIRED_FIELD_MISSING', 'string', 7,
				CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""", groupId, UUID.fromString(INTEGRATION), UUID.fromString(CONTRACT));
		try {
			jdbc.update("UPDATE monitoring.report_violations SET group_id = ? WHERE report_row_id = ? AND ordinal = 0",
					groupId, report(id).get("report_row_id"));
			var saved = report(id);
			var savedViolations = violations(id);
			var savedGroup = jdbc.queryForMap("SELECT * FROM monitoring.violation_groups WHERE group_id = ?", groupId);
			var before = counts();
			for (String repeated : List.of(original, request(id, "Failed", MISMATCH),
					request(id, "Passed", "").replace(CONTRACT, UUID.randomUUID().toString()))) {
				assertDuplicate(post(repeated, "application/json"), accepted);
				assertThat(report(id)).isEqualTo(saved);
				assertThat(violations(id)).isEqualTo(savedViolations);
				assertThat(jdbc.queryForMap("SELECT * FROM monitoring.violation_groups WHERE group_id = ?", groupId))
						.isEqualTo(savedGroup);
				assertThat(counts()).isEqualTo(before);
			}
		} finally {
			jdbc.update("UPDATE monitoring.report_violations SET group_id = NULL WHERE group_id = ?", groupId);
			jdbc.update("DELETE FROM monitoring.violation_groups WHERE group_id = ?", groupId);
		}
	}

	@Test
	void acceptsSameReportIdInAnotherIntegration() throws Exception {
		String id = UUID.randomUUID().toString();
		String otherIntegration = UUID.randomUUID().toString();
		String first = request(id, "Failed", MISSING);
		String second = request(id, "Failed", MISMATCH).replace(INTEGRATION, otherIntegration);
		var before = counts();
		JsonNode firstAccepted = confirmation(post(first, "application/json"), 201, INTEGRATION, id);
		JsonNode secondAccepted = confirmation(post(second, "application/json"), 201, otherIntegration, id);
		assertDuplicate(post(first, "application/json"), firstAccepted);
		assertDuplicate(post(second, "application/json"), secondAccepted);
		assertThat(counts()).containsExactly(before.get(0) + 2, before.get(1) + 2);
		assertThat(report(INTEGRATION, id).get("report_row_id"))
				.isNotEqualTo(report(otherIntegration, id).get("report_row_id"));
		assertThat(violations(INTEGRATION, id)).singleElement()
				.satisfies(row -> assertThat(row).containsEntry("violation_kind", "REQUIRED_FIELD_MISSING"));
		assertThat(violations(otherIntegration, id)).singleElement()
				.satisfies(row -> assertThat(row).containsEntry("violation_kind", "TYPE_MISMATCH"));
	}

	@Test
	void concurrentIdenticalHttpRequestsSaveOnlyOnce() throws Exception {
		String id = UUID.randomUUID().toString();
		String body = request(id, "Failed", MISSING + "," + MISMATCH + "," + MISMATCH);
		var before = counts();
		CountDownLatch ready = new CountDownLatch(4);
		CountDownLatch start = new CountDownLatch(1);
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			List<Future<HttpResponse<String>>> pending = new ArrayList<>();
			for (int index = 0; index < 4; index++) {
				pending.add(executor.submit(() -> {
					ready.countDown();
					assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
					return post(body, "application/json");
				}));
			}
			try {
				assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			} finally {
				start.countDown();
			}
			List<HttpResponse<String>> responses = new ArrayList<>();
			for (var response : pending) {
				responses.add(response.get(20, TimeUnit.SECONDS));
			}
			assertThat(responses).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(201, 200, 200, 200);
			JsonNode accepted = confirmation(responses.stream().filter(r -> r.statusCode() == 201).findFirst().orElseThrow(),
					201, INTEGRATION, id);
			for (var response : responses) {
				if (response.statusCode() == 200) {
					assertDuplicate(response, accepted);
				}
			}
		}
		assertThat(counts()).containsExactly(before.get(0) + 1, before.get(1) + 3);
		assertThat(violations(id)).extracting(row -> row.get("violation_kind"))
				.containsExactly("REQUIRED_FIELD_MISSING", "TYPE_MISMATCH", "TYPE_MISMATCH");
	}

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	void waitsForUncommittedInsertThenUsesItsOutcome(boolean commit) throws Exception {
		String id = UUID.randomUUID().toString();
		String body = request(id, "Failed", MISSING + "," + MISSING);
		var before = counts();
		try (Connection held = jdbc.getDataSource().getConnection();
				var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			held.setAutoCommit(false);
			try {
				int blocker = backendPid(held);
				insertHeldReport(held, id);
				var response = executor.submit(() -> post(body, "application/json"));
				awaitBlockedInsert(blocker);
				assertThat(response.isDone()).isFalse();
				if (commit) {
					held.commit();
				} else {
					held.rollback();
				}
				JsonNode accepted = confirmation(response.get(20, TimeUnit.SECONDS), commit ? 200 : 201, INTEGRATION, id);
				Instant heldTime = Instant.parse("2000-01-01T00:00:00Z");
				Instant receivedAt = Instant.parse(accepted.path("receivedAt").asString());
				if (commit) {
					assertThat(receivedAt).isEqualTo(heldTime);
					assertThat(violations(id)).singleElement()
							.satisfies(row -> assertThat(row).containsEntry("violation_kind", "TYPE_MISMATCH"));
				} else {
					assertThat(receivedAt).isAfter(heldTime);
					assertThat(violations(id)).extracting(row -> row.get("violation_kind"))
							.containsExactly("REQUIRED_FIELD_MISSING", "REQUIRED_FIELD_MISSING");
				}
				assertDuplicate(post(body, "application/json"), accepted);
				assertThat(counts()).containsExactly(before.get(0) + 1, before.get(1) + (commit ? 1 : 2));
			} finally {
				held.rollback();
			}
		}
	}

	@Test
	void rollsBackReportAndEarlierViolationOnLateInsertFailure(CapturedOutput output) throws Exception {
		String id = UUID.randomUUID().toString();
		String body = request(id, "Failed", MISSING + "," + MISMATCH);
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
			assertProblem(post(body, "application/json"), 500, "INTERNAL_ERROR");
			assertThat(counts()).isEqualTo(before);
			assertThat(output).doesNotContain("driver-detail", "org.postgresql", "jdbc:");
		} finally {
			jdbc.execute("DROP FUNCTION monitoring.test_reject_late_violation() CASCADE");
		}
		JsonNode accepted = confirmation(post(body, "application/json"), 201, INTEGRATION, id);
		assertThat(violations(id)).hasSize(2);
		var saved = violations(id);
		assertDuplicate(post(body, "application/json"), accepted);
		assertThat(violations(id)).isEqualTo(saved);
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

	private int backendPid(Connection connection) throws SQLException {
		try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT pg_backend_pid()")) {
			assertThat(result.next()).isTrue();
			return result.getInt(1);
		}
	}

	private void insertHeldReport(Connection connection, String id) throws SQLException {
		long rowId;
		try (var statement = connection.prepareStatement("""
				INSERT INTO monitoring.reports
				(external_report_id, integration_id, contract_id, contract_version, outcome, received_at)
				VALUES (?, ?, ?, 3, 'FAILED', '2000-01-01T00:00:00Z') RETURNING report_row_id
				""")) {
			statement.setObject(1, UUID.fromString(id));
			statement.setObject(2, UUID.fromString(INTEGRATION));
			statement.setObject(3, UUID.fromString(CONTRACT));
			try (var result = statement.executeQuery()) {
				assertThat(result.next()).isTrue();
				rowId = result.getLong(1);
			}
		}
		try (var statement = connection.prepareStatement("""
				INSERT INTO monitoring.report_violations
				(report_row_id, ordinal, field_name, violation_kind, expected_type, actual_type)
				VALUES (?, 0, 'amount', 'TYPE_MISMATCH', 'number', 'string')
				""")) {
			statement.setLong(1, rowId);
			statement.executeUpdate();
		}
	}

	private void awaitBlockedInsert(int blocker) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		do {
			Boolean blocked = jdbc.queryForObject("""
					SELECT EXISTS (SELECT 1 FROM pg_stat_activity
					WHERE datname = current_database() AND ? = ANY(pg_blocking_pids(pid))
					AND wait_event_type = 'Lock' AND query LIKE '%INSERT INTO monitoring.reports%')
					""", Boolean.class, blocker);
			if (Boolean.TRUE.equals(blocked)) {
				return;
			}
			Thread.sleep(25);
		} while (System.nanoTime() < deadline);
		throw new AssertionError("HTTP-вставка не дождалась удержанной транзакции PostgreSQL.");
	}

	private JsonNode confirmation(HttpResponse<String> response, int status, String integration, String id) {
		assertThat(response.statusCode()).isEqualTo(status);
		assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json");
		JsonNode body = json.readTree(response.body());
		assertThat(body.size()).isEqualTo(4);
		assertThat(body.path("integrationId").asString()).isEqualTo(integration);
		assertThat(body.path("reportId").asString()).isEqualTo(id);
		assertThat(body.path("duplicate").asBoolean()).isEqualTo(status == 200);
		assertThat(body.path("receivedAt").asString()).endsWith("Z");
		assertThat(Instant.parse(body.path("receivedAt").asString()))
				.isEqualTo(receivedAt(integration, id));
		return body;
	}

	private void assertDuplicate(HttpResponse<String> response, JsonNode accepted) {
		JsonNode repeated = confirmation(response, 200, accepted.path("integrationId").asString(),
				accepted.path("reportId").asString());
		assertThat(repeated.path("receivedAt")).isEqualTo(accepted.path("receivedAt"));
	}

	private String request(String id, String status, String violations) {
		return """
				{"integrationId":"%s","contractId":"%s","report":{
				"reportId":"%s","contractVersion":3,"status":"%s","violations":[%s]}}
				""".formatted(INTEGRATION, CONTRACT, id, status, violations);
	}

	private HttpResponse<String> post(String body, String contentType) throws Exception {
		return http.send(HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(15)).header("Content-Type", contentType)
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
		return report(INTEGRATION, id);
	}

	private Instant receivedAt(String integration, String id) {
		return jdbc.queryForObject("""
				SELECT received_at FROM monitoring.reports WHERE integration_id = ? AND external_report_id = ?
				""", (row, index) -> row.getObject(1, OffsetDateTime.class).toInstant(),
				UUID.fromString(integration), UUID.fromString(id));
	}

	private Map<String, Object> report(String integration, String id) {
		return jdbc.queryForMap("SELECT * FROM monitoring.reports WHERE integration_id = ? AND external_report_id = ?",
				UUID.fromString(integration), UUID.fromString(id));
	}

	private List<Map<String, Object>> violations(String id) {
		return violations(INTEGRATION, id);
	}

	private List<Map<String, Object>> violations(String integration, String id) {
		return jdbc.queryForList("""
				SELECT v.ordinal, v.field_name, v.violation_kind, v.expected_type, v.actual_type, v.group_id
				FROM monitoring.report_violations v JOIN monitoring.reports r USING (report_row_id)
				WHERE r.integration_id = ? AND r.external_report_id = ? ORDER BY v.ordinal
				""", UUID.fromString(integration), UUID.fromString(id));
	}

	private List<Integer> counts() {
		return List.of(jdbc.queryForObject("SELECT count(*) FROM monitoring.reports", Integer.class),
				jdbc.queryForObject("SELECT count(*) FROM monitoring.report_violations", Integer.class));
	}
}
