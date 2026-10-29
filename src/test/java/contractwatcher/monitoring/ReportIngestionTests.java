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
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
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

	private String integration;
	private static final java.util.concurrent.atomic.AtomicLong REPORT_SEQUENCE = new java.util.concurrent.atomic.AtomicLong();
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

	@BeforeEach
	void useSeparateIntegration() {
		integration = UUID.randomUUID().toString();
	}

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
				"--spring.datasource.hikari.connection-init-sql=SET TIME ZONE '"
						+ System.getProperty("monitoring.test.database.timezone", "UTC") + "'",
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
	void acceptsPassedAndPersistsProvidedSdkTime() throws Exception {
		String id = UUID.randomUUID().toString();
		HttpResponse<String> response = post(request(id, "Passed", "", "2026-10-20T13:00:00.1234567+03:00"), "application/json");
		assertThat(response.statusCode()).isEqualTo(201);
		assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json");
		assertThat(response.headers().firstValue("Location")).isEmpty();
		JsonNode body = json.readTree(response.body());
		assertThat(body.size()).isEqualTo(4);
		assertThat(body.path("integrationId").asString()).isEqualTo(integration);
		assertThat(body.path("reportId").asString()).isEqualTo(id);
		assertThat(body.path("duplicate").asBoolean()).isFalse();
		assertThat(body.path("receivedAt").asString()).endsWith("Z");
		var stored = report(id);
		assertThat(stored).containsEntry("outcome", "PASSED").containsEntry("contract_version", 3)
				.containsEntry("contract_id", UUID.fromString(CONTRACT));
		Instant storedTime = receivedAt(integration, id);
		assertThat(storedTime).isEqualTo(Instant.parse("2026-10-20T10:00:00.123456Z"));
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
		assertThat(rows).allSatisfy(row -> assertThat(row.get("group_id")).isInstanceOf(UUID.class));
		assertThat(groups()).hasSize(3).extracting(row -> row.get("occurrence_count"))
				.containsExactlyInAnyOrder(1L, 2L, 1L);
		assertGroupsMatchOccurrences();
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
		for (String invalid : List.of("{}", valid.replace(integration, "1-1-1-1-1"),
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
		for (String field : List.of("status", "violations", "contractVersion")) {
			String token = switch (field) {
				case "status" -> "\"status\":\"Passed\"";
				case "violations" -> "\"violations\":[]";
				default -> "\"contractVersion\":3";
			};
			for (String invalid : List.of(valid.replace(token, "\"" + field + "\":null"), valid.replace(token + ",", "").replace("," + token, ""))) {
				var missing = assertProblem(post(invalid, "application/json"), 400, "VALIDATION_REPORT_INVALID");
				assertThat(missing.path("errors").propertyNames()).containsExactly("report." + field);
			}
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
		JsonNode accepted = confirmation(post(original, "application/json"), 201, integration, id);
		var saved = report(id);
		var savedViolations = violations(id);
		var savedGroups = groups();
		var before = counts();
		for (String repeated : List.of(original, request(id, "Failed", MISMATCH),
				request(id, "Failed", MISSING.replace("\"String\"", "\"Number\"")),
				request(id, "Passed", "").replace(CONTRACT, UUID.randomUUID().toString()))) {
			assertDuplicate(post(repeated, "application/json"), accepted);
			assertThat(report(id)).isEqualTo(saved);
			assertThat(violations(id)).isEqualTo(savedViolations);
			assertThat(groups()).isEqualTo(savedGroups);
			assertThat(counts()).isEqualTo(before);
		}
	}

	@Test
	void acceptsSameReportIdInAnotherIntegration() throws Exception {
		String id = UUID.randomUUID().toString();
		String otherIntegration = UUID.randomUUID().toString();
		String first = request(id, "Failed", MISSING);
		String second = request(id, "Failed", MISMATCH).replace(integration, otherIntegration);
		var before = counts();
		JsonNode firstAccepted = confirmation(post(first, "application/json"), 201, integration, id);
		JsonNode secondAccepted = confirmation(post(second, "application/json"), 201, otherIntegration, id);
		assertDuplicate(post(first, "application/json"), firstAccepted);
		assertDuplicate(post(second, "application/json"), secondAccepted);
		assertThat(counts()).containsExactly(before.get(0) + 2, before.get(1) + 2);
		assertThat(report(integration, id).get("report_row_id"))
				.isNotEqualTo(report(otherIntegration, id).get("report_row_id"));
		assertThat(violations(integration, id)).singleElement()
				.satisfies(row -> assertThat(row).containsEntry("violation_kind", "REQUIRED_FIELD_MISSING"));
		assertThat(violations(otherIntegration, id)).singleElement()
				.satisfies(row -> assertThat(row).containsEntry("violation_kind", "TYPE_MISMATCH"));
		assertGroupsMatchOccurrences();
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
					201, integration, id);
			for (var response : responses) {
				if (response.statusCode() == 200) {
					assertDuplicate(response, accepted);
				}
			}
		}
		assertThat(counts()).containsExactly(before.get(0) + 1, before.get(1) + 3);
		assertThat(violations(id)).extracting(row -> row.get("violation_kind"))
				.containsExactly("REQUIRED_FIELD_MISSING", "TYPE_MISMATCH", "TYPE_MISMATCH");
		assertThat(groups()).hasSize(2).extracting(row -> row.get("occurrence_count"))
				.containsExactlyInAnyOrder(1L, 2L);
		assertGroupsMatchOccurrences();
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
				JsonNode accepted = confirmation(response.get(20, TimeUnit.SECONDS), commit ? 200 : 201, integration, id);
				Instant heldTime = Instant.parse("2000-01-01T00:00:00Z");
				Instant receivedAt = Instant.parse(accepted.path("receivedAt").asString());
				if (commit) {
					assertThat(receivedAt).isEqualTo(heldTime);
					assertThat(violations(id)).singleElement()
							.satisfies(row -> assertThat(row).containsEntry("violation_kind", "TYPE_MISMATCH")
									.containsEntry("group_id", null));
					assertThat(groups()).isEmpty();
				} else {
					assertThat(receivedAt).isAfter(heldTime);
					assertThat(violations(id)).extracting(row -> row.get("violation_kind"))
							.containsExactly("REQUIRED_FIELD_MISSING", "REQUIRED_FIELD_MISSING");
					assertThat(groups()).singleElement()
							.satisfies(row -> assertThat(row).containsEntry("occurrence_count", 2L));
					assertGroupsMatchOccurrences();
				}
				var savedGroups = groups();
				assertDuplicate(post(body, "application/json"), accepted);
				assertThat(groups()).isEqualTo(savedGroups);
				assertThat(counts()).containsExactly(before.get(0) + 1, before.get(1) + (commit ? 1 : 2));
			} finally {
				held.rollback();
			}
		}
	}

	@Test
	void rollsBackReportAndEarlierViolationOnLateInsertFailure(CapturedOutput output) throws Exception {
		String id = UUID.randomUUID().toString();
		assertThat(post(request(UUID.randomUUID().toString(), "Failed", MISMATCH), "application/json").statusCode())
				.isEqualTo(201);
		String newMissing = MISSING.replace("customer_id", "a_new");
		String body = request(id, "Failed", newMissing + "," + MISMATCH);
		var savedGroups = groups();
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
			assertThat(groups()).isEqualTo(savedGroups);
			assertThat(output).doesNotContain("driver-detail", "org.postgresql", "jdbc:");
		} finally {
			jdbc.execute("DROP FUNCTION monitoring.test_reject_late_violation() CASCADE");
		}
		JsonNode accepted = confirmation(post(body, "application/json"), 201, integration, id);
		assertThat(violations(id)).hasSize(2);
		var saved = violations(id);
		assertGroupsMatchOccurrences();
		var committedGroups = groups();
		assertDuplicate(post(body, "application/json"), accepted);
		assertThat(violations(id)).isEqualTo(saved);
		assertThat(groups()).isEqualTo(committedGroups);
	}

	@Test
	void returnsNoConfirmationAndRollsBackOnDeferredCommitFailure(CapturedOutput output) throws Exception {
		assertThat(post(request(UUID.randomUUID().toString(), "Failed", MISMATCH), "application/json").statusCode())
				.isEqualTo(201);
		var savedGroups = groups();
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
			assertThat(groups()).isEqualTo(savedGroups);
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

	@Test
	void separatesGroupIdentityWhileKeepingExpectedTypeAsMetadata() throws Exception {
		String first = UUID.randomUUID().toString();
		assertThat(post(request(first, "Failed", MISSING + "," + MISMATCH), "application/json").statusCode()).isEqualTo(201);
		var original = violations(first);
		String second = UUID.randomUUID().toString();
		assertThat(post(request(second, "Failed", MISSING.replace("\"String\"", "\"Number\"") + ","
				+ MISMATCH.replace("\"expectedType\":\"Number\"", "\"expectedType\":\"Boolean\"")),
				"application/json").statusCode()).isEqualTo(201);
		assertThat(violations(second)).extracting(row -> row.get("group_id"))
				.containsExactlyElementsOf(original.stream().map(row -> row.get("group_id")).toList());
		assertThat(violations(second)).extracting(row -> row.get("expected_type")).containsExactly("number", "boolean");
		assertThat(groups()).extracting(row -> row.get("expected_type")).containsExactlyInAnyOrder("string", "number");

		String variantsId = UUID.randomUUID().toString();
		String variants = String.join(",", MISSING.replace("customer_id", " customer_id "),
				MISSING.replace("customer_id", "Customer_id"), MISSING.replace("RequiredFieldMissing", "NullNotAllowed"),
				MISMATCH.replace("\"actualType\":\"String\"", "\"actualType\":\"Boolean\""));
		assertThat(post(request(variantsId, "Failed", variants), "application/json").statusCode()).isEqualTo(201);
		var variantGroups = violations(variantsId).stream().map(row -> row.get("group_id")).toList();
		assertThat(variantGroups).doesNotHaveDuplicates().doesNotContain(original.get(0).get("group_id"), original.get(1).get("group_id"));

		String otherIntegration = UUID.randomUUID().toString();
		for (String changed : List.of(request(UUID.randomUUID().toString(), "Failed", MISSING + "," + MISMATCH)
				.replace(integration, otherIntegration),
				request(UUID.randomUUID().toString(), "Failed", MISSING + "," + MISMATCH).replace(CONTRACT, UUID.randomUUID().toString()),
				request(UUID.randomUUID().toString(), "Failed", MISSING + "," + MISMATCH).replace(":3,", ":4,"))) {
			JsonNode body = json.readTree(changed);
			String scope = body.path("integrationId").asString();
			String id = body.path("report").path("reportId").asString();
			assertThat(post(changed, "application/json").statusCode()).isEqualTo(201);
			assertThat(violations(scope, id)).extracting(row -> row.get("group_id"))
					.doesNotContain(original.get(0).get("group_id"), original.get(1).get("group_id"));
		}
		assertThat(groups()).hasSize(10);
		assertGroupsMatchOccurrences();
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void concurrentNewReportsCreateAndUpdateGroupsInOneOrder(boolean existing) throws Exception {
		if (existing) {
			assertThat(post(request(UUID.randomUUID().toString(), "Failed", MISSING + "," + MISMATCH),
					"application/json").statusCode()).isEqualTo(201);
		}
		String earlyId = UUID.randomUUID().toString();
		try (Connection held = jdbc.getDataSource().getConnection();
				var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			int blocker = backendPid(held);
			try (var statement = held.createStatement()) {
				statement.execute("SELECT pg_advisory_lock(" + blocker + ")");
			}
			held.setAutoCommit(false);
			jdbc.execute("""
					CREATE FUNCTION monitoring.test_delay_early_report() RETURNS trigger LANGUAGE plpgsql AS $$
					BEGIN
					  IF NEW.external_report_id = '%s'::uuid THEN
					    PERFORM pg_advisory_xact_lock(%d);
					  END IF;
					  RETURN NEW;
					END $$
					""".formatted(earlyId, blocker));
			try {
				jdbc.execute("""
						CREATE TRIGGER test_delay_early AFTER INSERT ON monitoring.reports
						FOR EACH ROW EXECUTE FUNCTION monitoring.test_delay_early_report()
						""");
				var early = executor.submit(() -> post(request(earlyId, "Failed", MISSING + "," + MISMATCH), "application/json"));
				awaitBlockedInsert(blocker);
				try (var statement = held.prepareStatement(existing ? """
						UPDATE monitoring.violation_groups SET occurrence_count = occurrence_count
						WHERE integration_id = ? AND field_name = 'amount'
						""" : """
						INSERT INTO monitoring.violation_groups
						(group_id, integration_id, contract_id, contract_version, field_name, violation_kind,
						expected_type, actual_type, occurrence_count, first_seen_at, last_seen_at)
						VALUES (?, ?, ?, 3, 'amount', 'TYPE_MISMATCH', 'number', 'string', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
						""")) {
					if (existing) {
						statement.setObject(1, UUID.fromString(integration));
					} else {
						statement.setObject(1, UUID.randomUUID());
						statement.setObject(2, UUID.fromString(integration));
						statement.setObject(3, UUID.fromString(CONTRACT));
					}
					assertThat(statement.executeUpdate()).isEqualTo(1);
				}
				List<String> ids = new ArrayList<>();
				List<Future<HttpResponse<String>>> pending = new ArrayList<>();
				for (int index = 0; index < 4; index++) {
					String id = UUID.randomUUID().toString();
					ids.add(id);
					String items = index % 2 == 0 ? MISSING + "," + MISMATCH + "," + MISMATCH
							: MISMATCH + "," + MISMATCH + "," + MISSING;
					pending.add(executor.submit(() -> post(request(id, "Failed", items), "application/json")));
				}
				awaitBlockedGroupRequests(blocker, 4);
				assertThat(early.isDone()).isFalse();
				held.rollback();
				for (int index = 0; index < pending.size(); index++) {
					confirmation(pending.get(index).get(20, TimeUnit.SECONDS), 201, integration, ids.get(index));
					assertThat(violations(ids.get(index))).extracting(row -> row.get("violation_kind"))
							.containsExactlyElementsOf(index % 2 == 0
									? List.of("REQUIRED_FIELD_MISSING", "TYPE_MISMATCH", "TYPE_MISMATCH")
									: List.of("TYPE_MISMATCH", "TYPE_MISMATCH", "REQUIRED_FIELD_MISSING"));
				}
				try (var statement = held.createStatement()) {
					statement.execute("SELECT pg_advisory_unlock(" + blocker + ")");
				}
				confirmation(early.get(20, TimeUnit.SECONDS), 201, integration, earlyId);
				assertThat(receivedAt(integration, earlyId)).isBefore(receivedAt(integration, ids.getFirst()));
				assertThat(groups()).hasSize(2).extracting(row -> row.get("occurrence_count"))
						.containsExactlyInAnyOrder(existing ? 6L : 5L, existing ? 10L : 9L);
				assertGroupsMatchOccurrences();
			} finally {
				held.rollback();
				try (var statement = held.createStatement()) {
					statement.execute("SELECT pg_advisory_unlock_all()");
				}
				jdbc.execute("DROP FUNCTION monitoring.test_delay_early_report() CASCADE");
			}
		}
	}

	@Test
	void historyIsIsolatedAndStableAcrossPagesWithEqualTimes() throws Exception {
		String shared = UUID.randomUUID().toString();
		String failed = UUID.randomUUID().toString();
		String latest = UUID.randomUUID().toString();
		String otherIntegration = UUID.randomUUID().toString();
		String otherContract = UUID.randomUUID().toString();
		assertThat(post(request(shared, "Passed", "").replace(integration, otherIntegration),
				"application/json").statusCode()).isEqualTo(201);
		storeHistoryFixture(shared, "Passed", "", "2026-10-12T00:00:00Z");
		storeHistoryFixture(failed, "Failed", MISSING + "," + MISMATCH + "," + MISMATCH,
				"2026-10-13T09:00:00.123456Z");
		jdbc.update("UPDATE monitoring.report_violations SET group_id = NULL WHERE report_row_id = ? AND ordinal = 0",
				report(failed).get("report_row_id"));
		assertThat(post(request(latest, "Passed", "").replace(CONTRACT, otherContract),
				"application/json").statusCode()).isEqualTo(201);
		setHistoryTime(latest, "2026-10-13T09:00:00.123456Z");
		assertThat((Long) report(latest).get("report_row_id")).isGreaterThan((Long) report(failed).get("report_row_id"));

		JsonNode all = history("", 3, 0, 20);
		assertHistoryIds(all, latest, failed, shared);
		assertThat(history("", 3, 0, 20)).isEqualTo(all);
		JsonNode first = history("?limit=1", 3, 0, 1);
		JsonNode second = history("?limit=1&offset=1", 3, 1, 1);
		assertHistoryIds(first, latest);
		assertHistoryIds(second, failed);
		assertHistoryIds(history("?limit=1&offset=2", 3, 2, 1), shared);
		assertThat(first.path("items").get(0)).isEqualTo(all.path("items").get(0));
		JsonNode item = second.path("items").get(0);
		assertThat(item.size()).isEqualTo(6);
		assertThat(item.path("contractId").asString()).isEqualTo(CONTRACT);
		assertThat(item.path("contractVersion").asInt()).isEqualTo(3);
		assertThat(item.path("status").asString()).isEqualTo("Failed");
		assertThat(item.path("violationCount").asLong()).isEqualTo(3);
		assertThat(item.path("receivedAt").asString()).isEqualTo("2026-10-13T09:00:00.123456Z");
		JsonNode passed = first.path("items").get(0);
		assertThat(passed.size()).isEqualTo(6);
		assertThat(passed.path("contractId").asString()).isEqualTo(otherContract);
		assertThat(passed.path("contractVersion").asInt()).isEqualTo(3);
		assertThat(passed.path("status").asString()).isEqualTo("Passed");
		assertThat(passed.path("violationCount").asLong()).isZero();
	}

	@Test
	void historyAppliesPeriodAndStatusBeforePagination() throws Exception {
		String from = UUID.randomUUID().toString();
		String inside = UUID.randomUUID().toString();
		String passed = UUID.randomUUID().toString();
		String to = UUID.randomUUID().toString();
		String later = UUID.randomUUID().toString();
		storeHistoryFixture(from, "Failed", MISSING, "2026-10-13T00:00:00.123456Z");
		storeHistoryFixture(inside, "Failed", MISSING, "2026-10-13T00:00:00.123457Z");
		storeHistoryFixture(passed, "Passed", "", "2026-10-13T12:00:00Z");
		storeHistoryFixture(to, "Failed", MISSING, "2026-10-14T00:00:00Z");
		storeHistoryFixture(later, "Passed", "", "2026-10-15T00:00:00Z");
		String period = "?from=2026-10-13T00:00:00.123456Z&to=2026-10-14T00:00:00Z&status=Failed";
		assertHistoryIds(history(period + "&limit=1&offset=1", 2, 1, 1), from);
		assertHistoryIds(history(period, 2, 0, 20), inside, from);
		assertHistoryIds(history("?to=2026-10-14T00:00:00Z", 3, 0, 20), passed, inside, from);
		assertHistoryIds(history("?from=2026-10-14T00:00:00Z&status=Passed", 1, 0, 20), later);
	}

	@Test
	void historyReturnsEmptyPagesAndRejectsInvalidParametersWithoutWriting(CapturedOutput output) throws Exception {
		assertHistoryIds(history("", 0, 0, 20));
		String id = UUID.randomUUID().toString();
		storeHistoryFixture(id, "Passed", "", "2026-10-13T00:00:00Z");
		assertHistoryIds(history("?status=Failed", 0, 0, 20));
		assertHistoryIds(history("?offset=2147483647&limit=100", 1, Integer.MAX_VALUE, 100));
		var before = counts();
		var beforeGroups = groups();
		JsonNode invalidId = assertProblem(getHistory("invalid-uuid", ""), 400, "INVALID_REQUEST");
		assertThat(invalidId.path("errors").has("integrationId")).isTrue();
		Map<String, String> invalid = Map.ofEntries(
				Map.entry("?from=2026-02-30T00:00:00Z", "from"),
				Map.entry("?from=2026-10-13T00:00:00.1234567Z", "from"),
				Map.entry("?from=2026-10-13T00:00:00Z&to=2026-10-13T00:00:00Z", "to"),
				Map.entry("?status=private-query-marker", "status"),
				Map.entry("?offset=-1", "offset"),
				Map.entry("?offset=2147483648", "offset"),
				Map.entry("?limit=101", "limit"),
				Map.entry("?to=", "to"),
				Map.entry("?limit=1&limit=1", "limit"));
		for (var entry : invalid.entrySet()) {
			JsonNode problem = assertProblem(getHistory(integration, entry.getKey()), 400, "INVALID_REQUEST");
			assertThat(problem.path("errors").path(entry.getValue()).isArray()).isTrue();
			assertThat(problem.path("errors").path(entry.getValue()).size()).isPositive();
			assertThat(problem.toString()).doesNotContain("private-query-marker");
		}
		assertThat(output.getAll()).doesNotContain("private-query-marker");
		assertThat(counts()).isEqualTo(before);
		assertThat(groups()).isEqualTo(beforeGroups);
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void historyKeepsItemsAndTotalInOneSnapshotDuringConcurrentCommit(boolean filterContracts) throws Exception {
		String query = filterContracts ? "?contractId=" + CONTRACT : "";
		String previous = UUID.randomUUID().toString();
		String committed = UUID.randomUUID().toString();
		storeHistoryFixture(previous, "Passed", "", "1999-01-01T00:00:00Z");
		try (Connection held = jdbc.getDataSource().getConnection();
				var executor = Executors.newSingleThreadExecutor()) {
			held.setAutoCommit(false);
			try {
				try (var statement = held.createStatement()) {
					statement.execute("LOCK TABLE monitoring.report_violations IN ACCESS EXCLUSIVE MODE");
				}
				int blocker = backendPid(held);
				Future<HttpResponse<String>> pending = executor.submit(() -> getHistory(integration, query));
				awaitBlockedHistory(blocker);
				insertHeldReport(held, committed);
				held.commit();
				assertHistoryIds(page(pending.get(10, TimeUnit.SECONDS), 1, 0, 20), previous);
				JsonNode next = history(query, 2, 0, 20);
				assertHistoryIds(next, committed, previous);
				assertThat(next.path("items").get(0).path("violationCount").asLong()).isEqualTo(1);
			} finally {
				held.rollback();
			}
		}
	}

	@Test
	void historyFiltersContractsAcrossVersionsBeforePagesAndCombinesIndependentFilters() throws Exception {
		String a = "abcdefab-cdef-4abc-8def-abcdefabcdef";
		String c = UUID.randomUUID().toString();
		String foreign = UUID.randomUUID().toString();
		String a1 = UUID.randomUUID().toString();
		String a3 = UUID.randomUUID().toString();
		String c1 = UUID.randomUUID().toString();
		String start = "2026-10-13T00:00:00.123456Z";
		String end = "2026-10-14T00:00:00Z";
		assertThat(post(request(a1, "Passed", "", start).replace(CONTRACT, a)
				.replace("\"contractVersion\":3", "\"contractVersion\":1"), "application/json").statusCode()).isEqualTo(201);
		String failed = request(a3, "Failed", MISSING + "," + MISMATCH + "," + MISMATCH, end).replace(CONTRACT, a);
		assertThat(post(failed, "application/json").statusCode()).isEqualTo(201);
		assertThat(post(request(c1, "Failed", MISSING, end).replace(CONTRACT, c)
				.replace("\"contractVersion\":3", "\"contractVersion\":1"), "application/json").statusCode()).isEqualTo(201);
		for (int i = 0; i < 25; i++) {
			assertThat(post(request(UUID.randomUUID().toString(), "Passed", "", "2026-10-15T00:00:00Z"),
					"application/json").statusCode()).isEqualTo(201);
		}
		String other = UUID.randomUUID().toString();
		for (String contract : List.of(a, foreign)) {
			assertThat(post(request(UUID.randomUUID().toString(), "Passed", "", "2026-10-16T00:00:00Z")
					.replace(CONTRACT, contract).replace(integration, other), "application/json").statusCode()).isEqualTo(201);
		}
		var before = summaryTables();
		String selected = "?contractId=" + a + "&contractId=" + c;
		assertThat(history("", 28, 0, 20).path("items").size()).isEqualTo(20);
		JsonNode versions = history("?contractId=" + a, 2, 0, 20);
		assertHistoryIds(versions, a3, a1);
		assertThat(versions.path("items").get(0).path("contractVersion").asInt()).isEqualTo(3);
		assertThat(versions.path("items").get(1).path("contractVersion").asInt()).isEqualTo(1);
		assertThat(versions.path("items").get(0).path("violationCount").asLong()).isEqualTo(3);
		assertThat(versions.path("items").get(1).path("receivedAt").asString()).isEqualTo(start);
		assertHistoryIds(history(selected, 3, 0, 20), c1, a3, a1);
		assertHistoryIds(history(selected + "&contractId=" + a.toUpperCase(Locale.ROOT) + "&ignored=value", 3, 0, 20), c1, a3, a1);
		assertHistoryIds(history(selected + "&limit=1", 3, 0, 1), c1);
		assertHistoryIds(history(selected + "&limit=1&offset=1", 3, 1, 1), a3);
		assertHistoryIds(history(selected + "&limit=1&offset=2", 3, 2, 1), a1);
		assertHistoryIds(history(selected + "&offset=3", 3, 3, 20));
		assertHistoryIds(history(selected + "&status=Passed", 1, 0, 20), a1);
		assertHistoryIds(history(selected + "&from=" + start + "&to=" + end, 1, 0, 20), a1);
		assertHistoryIds(history(selected + "&from=" + end + "&status=Failed", 2, 0, 20), c1, a3);
		assertHistoryIds(history(selected + "&to=" + end + "&status=Failed", 0, 0, 20));
		assertHistoryIds(history(selected + "&from=" + start + "&to=2026-10-15T00:00:00Z&status=Failed&limit=1&offset=1", 2, 1, 1), a3);
		String absent = UUID.randomUUID().toString();
		assertHistoryIds(history("?contractId=" + absent + "&contractId=" + foreign, 0, 0, 20));
		assertHistoryIds(history(selected + "&contractId=" + absent + "&contractId=" + foreign, 3, 0, 20), c1, a3, a1);
		assertHistoryIds(page(getHistory(UUID.randomUUID().toString(), selected), 0, 0, 20));
		assertThat(post(failed, "application/json").statusCode()).isEqualTo(200);
		assertHistoryIds(history(selected, 3, 0, 20), c1, a3, a1);
		assertThat(summaryTables()).isEqualTo(before);
	}

	@Test
	void historyValidatesRepeatedContractIdsWithoutWritingOrEchoingValues(CapturedOutput output) throws Exception {
		String id = UUID.randomUUID().toString();
		assertThat(post(request(id, "Failed", MISSING), "application/json").statusCode()).isEqualTo(201);
		var before = summaryTables();
		String fiftyRepeated = "?" + String.join("&", java.util.Collections.nCopies(50, "contractId=" + CONTRACT));
		assertHistoryIds(history(fiftyRepeated, 1, 0, 20), id);
		List<String> distinct = new ArrayList<>();
		distinct.add("contractId=" + CONTRACT);
		for (int i = 1; i < 50; i++) {
			distinct.add("contractId=" + UUID.randomUUID());
		}
		assertHistoryIds(history("?" + String.join("&", distinct), 1, 0, 20), id);
		for (String query : List.of("?contractId", "?contractId=", "?contractId=null", "?contractId=%5B%5D",
				"?contractId=private-contract-marker", "?contractId=1-1-1-1-1", "?contractId=%20" + CONTRACT,
				"?contractId=" + CONTRACT + "%20", "?contractId=" + CONTRACT + "," + CONTRACT,
				"?contractId=" + CONTRACT + "&contractId=", "?contractId=" + CONTRACT + "&contractId=private-contract-marker",
				fiftyRepeated + "&contractId=" + CONTRACT, "?" + String.join("&", distinct) + "&contractId=" + UUID.randomUUID())) {
			var problem = assertProblem(getHistory(integration, query), 400, "INVALID_REQUEST");
			assertThat(problem.path("errors").propertyNames()).containsExactly("contractId");
			assertThat(problem.path("errors").path("contractId").size()).isEqualTo(1);
			assertThat(problem.toString()).doesNotContain("private-contract-marker");
		}
		for (String parameter : List.of("status=Failed", "from=2026-10-13T00:00:00Z", "limit=1")) {
			var problem = assertProblem(getHistory(integration, "?contractId=" + CONTRACT + "&" + parameter + "&" + parameter), 400, "INVALID_REQUEST");
			assertThat(problem.path("errors").has(parameter.substring(0, parameter.indexOf('=')))).isTrue();
		}
		assertThat(summaryTables()).isEqualTo(before);
		assertThat(output.getAll()).doesNotContain("private-contract-marker");
	}

	@Test
	void detailsReturnSavedHeadersAndAllViolationsInOriginalOrder() throws Exception {
		String passedId = UUID.randomUUID().toString();
		String failedId = UUID.randomUUID().toString();
		String nullValue = """
				{"fieldName":" optional ","type":"NullNotAllowed","expectedType":"Boolean","actualType":null}
				""".strip();
		String objectMismatch = """
				{"fieldName":"object_field","type":"TypeMismatch","expectedType":"Object","actualType":"Array"}
				""".strip();
		String differentMetadata = MISMATCH.replace("\"Number\"", "\"Boolean\"");
		String input = String.join(",", MISMATCH, MISSING, nullValue, MISMATCH, objectMismatch, differentMetadata);
		assertThat(post(request(passedId, "Passed", ""), "application/json").statusCode()).isEqualTo(201);
		assertThat(post(request(failedId, "Failed", input), "application/json").statusCode()).isEqualTo(201);

		JsonNode passed = details(integration, passedId);
		assertThat(passed.path("status").asString()).isEqualTo("Passed");
		assertThat(passed.path("violationCount").asLong()).isZero();
		assertThat(passed.path("violations").isArray()).isTrue();
		assertThat(passed.path("violations").isEmpty()).isTrue();
		JsonNode failed = details(integration, failedId);
		assertThat(failed.path("status").asString()).isEqualTo("Failed");
		assertThat(failed.path("violationCount").asLong()).isEqualTo(6);
		JsonNode expected = json.readTree("[" + input + "]");
		var stored = violations(failedId);
		for (int index = 0; index < expected.size(); index++) {
			JsonNode actual = failed.path("violations").get(index);
			assertThat(actual.size()).isEqualTo(5);
			for (String field : List.of("fieldName", "type", "expectedType", "actualType")) {
				assertThat(actual.get(field)).isEqualTo(expected.get(index).get(field));
			}
			assertThat(actual.path("groupId").asString()).isEqualTo(stored.get(index).get("group_id").toString());
		}
		assertThat(failed.path("violations").get(3)).isEqualTo(failed.path("violations").get(0));
		assertThat(failed.path("violations").get(5).path("groupId"))
				.isEqualTo(failed.path("violations").get(0).path("groupId"));
		JsonNode page = history("", 2, 0, 20);
		for (JsonNode item : page.path("items")) {
			JsonNode detail = item.path("reportId").asString().equals(passedId) ? passed : failed;
			for (String field : List.of("reportId", "contractId", "contractVersion", "status", "receivedAt", "violationCount")) {
				assertThat(detail.get(field)).isEqualTo(item.get(field));
			}
		}
	}

	@Test
	void detailsScopeSharedReportIdsAndHideUnknownOrForeignReports() throws Exception {
		String shared = UUID.randomUUID().toString();
		String onlyHere = UUID.randomUUID().toString();
		String otherIntegration = UUID.randomUUID().toString();
		String otherContract = UUID.randomUUID().toString();
		assertThat(post(request(shared, "Passed", ""), "application/json").statusCode()).isEqualTo(201);
		assertThat(post(request(shared, "Failed", MISSING + "," + MISMATCH)
				.replace(integration, otherIntegration).replace(CONTRACT, otherContract)
				.replace("\"contractVersion\":3", "\"contractVersion\":7"), "application/json").statusCode()).isEqualTo(201);
		assertThat(post(request(onlyHere, "Failed", MISSING), "application/json").statusCode()).isEqualTo(201);
		JsonNode here = details(integration, shared);
		JsonNode there = details(otherIntegration, shared);
		assertThat(here.path("status").asString()).isEqualTo("Passed");
		assertThat(here.path("violations").size()).isZero();
		assertThat(there.path("status").asString()).isEqualTo("Failed");
		assertThat(there.path("contractId").asString()).isEqualTo(otherContract);
		assertThat(there.path("contractVersion").asInt()).isEqualTo(7);
		assertThat(there.path("violations").size()).isEqualTo(2);
		JsonNode foreign = assertProblem(getDetails(otherIntegration, onlyHere, ""), 404, "REPORT_NOT_FOUND");
		JsonNode unknown = assertProblem(getDetails(otherIntegration, UUID.randomUUID().toString(), ""),
				404, "REPORT_NOT_FOUND");
		JsonNode unknownIntegration = assertProblem(getDetails(UUID.randomUUID().toString(), onlyHere, ""),
				404, "REPORT_NOT_FOUND");
		assertThat(foreign.path("title").asString()).isEqualTo("Not Found");
		assertThat(foreign.path("detail").asString()).isEqualTo("Отчет не найден в указанной интеграции.");
		assertThat(foreign.path("errors").isEmpty()).isTrue();
		for (String field : List.of("type", "title", "status", "detail", "code", "errors")) {
			assertThat(unknown.get(field)).isEqualTo(foreign.get(field));
			assertThat(unknownIntegration.get(field)).isEqualTo(foreign.get(field));
		}
	}

	@Test
	void detailsValidateBothIdsAndLeaveStoredDataUnchanged(CapturedOutput output) throws Exception {
		String id = UUID.randomUUID().toString();
		assertThat(post(request(id, "Failed", MISMATCH), "application/json").statusCode()).isEqualTo(201);
		var beforeReports = jdbc.queryForList("SELECT * FROM monitoring.reports ORDER BY report_row_id");
		var beforeViolations = jdbc.queryForList("SELECT * FROM monitoring.report_violations ORDER BY report_row_id, ordinal");
		var beforeGroups = jdbc.queryForList("SELECT * FROM monitoring.violation_groups ORDER BY group_id");
		for (var invalid : List.of(
				List.of("private-integration-marker", id, "integrationId"),
				List.of(integration, "1-1-1-1-1", "reportId"),
				List.of(integration, "gggggggg-gggg-gggg-gggg-gggggggggggg", "reportId"),
				List.of("private-integration-marker", "private-report-marker", "integrationId", "reportId"))) {
			JsonNode problem = assertProblem(getDetails(invalid.get(0), invalid.get(1), "?ignored=private-query-marker"),
					400, "INVALID_REQUEST");
			assertThat(problem.path("errors").size()).isEqualTo(invalid.size() - 2);
			for (String field : invalid.subList(2, invalid.size())) {
				assertThat(problem.path("errors").path(field).isArray()).isTrue();
				assertThat(problem.path("errors").path(field).size()).isPositive();
			}
			assertThat(problem.path("errors").toString()).doesNotContain(invalid.get(0), invalid.get(1));
			assertThat(problem.path("detail").asString()).doesNotContain(invalid.get(0), invalid.get(1));
			assertThat(problem.path("instance").asString()).doesNotContain("?", "private-query-marker");
		}
		JsonNode canonical = details(integration, id);
		var uppercase = getDetails(integration.toUpperCase(Locale.ROOT), id.toUpperCase(Locale.ROOT), "");
		assertThat(uppercase.statusCode()).isEqualTo(200);
		assertThat(json.readTree(uppercase.body())).isEqualTo(canonical);
		assertProblem(getDetails(integration, UUID.randomUUID().toString(), "?ignored=private-query-marker"),
				404, "REPORT_NOT_FOUND");
		assertThat(output.getAll()).doesNotContain("private-integration-marker", "private-report-marker",
				"1-1-1-1-1", "gggggggg-gggg-gggg-gggg-gggggggggggg", "private-query-marker");
		assertThat(jdbc.queryForList("SELECT * FROM monitoring.reports ORDER BY report_row_id")).isEqualTo(beforeReports);
		assertThat(jdbc.queryForList("SELECT * FROM monitoring.report_violations ORDER BY report_row_id, ordinal"))
				.isEqualTo(beforeViolations);
		assertThat(jdbc.queryForList("SELECT * FROM monitoring.violation_groups ORDER BY group_id")).isEqualTo(beforeGroups);
	}

	@Test
	void detailsSeeOnlyCommittedCompleteReportAndPreserveNullableMetadata() throws Exception {
		String id = UUID.randomUUID().toString();
		try (Connection held = jdbc.getDataSource().getConnection()) {
			held.setAutoCommit(false);
			try {
				insertHeldReport(held, id);
				assertProblem(getDetails(integration, id, ""), 404, "REPORT_NOT_FOUND");
				try (var statement = held.prepareStatement("""
						INSERT INTO monitoring.report_violations
						(report_row_id, ordinal, field_name, violation_kind, expected_type, actual_type)
						SELECT report_row_id, 1, 'nullable_field', 'NULL_NOT_ALLOWED', NULL, NULL
						FROM monitoring.reports WHERE integration_id = ? AND external_report_id = ?
						""")) {
					statement.setObject(1, UUID.fromString(integration));
					statement.setObject(2, UUID.fromString(id));
					assertThat(statement.executeUpdate()).isEqualTo(1);
				}
				held.commit();
				JsonNode complete = details(integration, id);
				assertThat(complete.path("status").asString()).isEqualTo("Failed");
				assertThat(complete.path("receivedAt").asString()).isEqualTo("2000-01-01T00:00:00Z");
				assertThat(complete.path("violationCount").asLong()).isEqualTo(2);
				assertThat(complete.path("violations")).isEqualTo(json.readTree("""
						[
						  {"groupId":null,"fieldName":"amount","type":"TypeMismatch","expectedType":"Number","actualType":"String"},
						  {"groupId":null,"fieldName":"nullable_field","type":"NullNotAllowed","expectedType":null,"actualType":null}
						]
						"""));
			} finally {
				held.rollback();
			}
		}
	}

	@Test
	void groupListScopesContractsCountsOccurrencesAndKeepsCreationMetadata() throws Exception {
		String firstId = UUID.randomUUID().toString();
		String secondId = UUID.randomUUID().toString();
		String nullValue = """
				{"fieldName":" optional ","type":"NullNotAllowed","expectedType":"Boolean","actualType":null}
				""".strip();
		assertThat(post(request(UUID.randomUUID().toString(), "Passed", ""), "application/json").statusCode()).isEqualTo(201);
		String first = request(firstId, "Failed", String.join(",", MISMATCH, MISMATCH, MISSING, nullValue));
		assertThat(post(first, "application/json").statusCode()).isEqualTo(201);
		assertThat(post(request(secondId, "Failed", MISMATCH), "application/json").statusCode()).isEqualTo(201);
		UUID mismatchId = (UUID) violations(firstId).getFirst().get("group_id");
		JsonNode initial = groupItem(groupPage("", 3, 0, 20), mismatchId);
		assertThat(initial.path("occurrenceCount").asLong()).isEqualTo(3);
		assertThat(initial.path("expectedType").asString()).isEqualTo("Number");
		assertThat(initial.path("actualType").asString()).isEqualTo("String");
		assertThat(initial.path("type").asString()).isEqualTo("TypeMismatch");
		assertThat(Instant.parse(initial.path("firstSeenAt").asString())).isEqualTo(receivedAt(integration, firstId));
		assertThat(Instant.parse(initial.path("lastSeenAt").asString())).isEqualTo(receivedAt(integration, secondId));

		String changedId = UUID.randomUUID().toString();
		assertThat(post(request(changedId, "Failed", MISMATCH.replace("\"Number\"", "\"Boolean\"")),
				"application/json").statusCode()).isEqualTo(201);
		String otherContract = UUID.randomUUID().toString();
		assertThat(post(request(UUID.randomUUID().toString(), "Failed", MISMATCH).replace(CONTRACT, otherContract),
				"application/json").statusCode()).isEqualTo(201);
		assertThat(post(request(UUID.randomUUID().toString(), "Failed", MISMATCH)
				.replace("\"contractVersion\":3", "\"contractVersion\":4"), "application/json").statusCode()).isEqualTo(201);
		assertThat(post(request(UUID.randomUUID().toString(), "Failed", MISMATCH)
				.replace(integration, UUID.randomUUID().toString()), "application/json").statusCode()).isEqualTo(201);
		JsonNode page = groupPage("", 5, 0, 20);
		assertThat(page.path("items").size()).isEqualTo(5);
		JsonNode mismatch = groupItem(page, mismatchId);
		assertThat(mismatch.path("expectedType").asString()).isEqualTo("Number");
		assertThat(mismatch.path("occurrenceCount").asLong()).isEqualTo(4);
		JsonNode changed = details(integration, changedId).path("violations").get(0);
		assertThat(changed.path("expectedType").asString()).isEqualTo("Boolean");
		assertThat(changed.path("groupId").asString()).isEqualTo(mismatchId.toString());
		for (var stored : groups()) {
			JsonNode item = groupItem(page, (UUID) stored.get("group_id"));
			assertThat(item.size()).isEqualTo(10);
			assertThat(item.path("contractId").asString()).isEqualTo(stored.get("contract_id").toString());
			assertThat(item.path("contractVersion").asInt()).isEqualTo(stored.get("contract_version"));
			assertThat(item.path("fieldName").asString()).isEqualTo(stored.get("field_name"));
			assertThat(item.path("occurrenceCount").asLong()).isEqualTo(stored.get("occurrence_count"));
			for (String field : List.of("firstSeenAt", "lastSeenAt")) {
				assertThat(item.path(field).asString()).endsWith("Z");
				String column = field.equals("firstSeenAt") ? "first_seen_at" : "last_seen_at";
				assertThat(Instant.parse(item.path(field).asString()))
						.isEqualTo(((java.sql.Timestamp) stored.get(column)).toInstant());
			}
		}
		for (int ordinal : List.of(2, 3)) {
			JsonNode item = groupItem(page, (UUID) violations(firstId).get(ordinal).get("group_id"));
			assertThat(item.get("actualType").isNull()).isTrue();
			assertThat(item.path("type").asString()).isEqualTo(ordinal == 2 ? "RequiredFieldMissing" : "NullNotAllowed");
			assertThat(item.path("expectedType").asString()).isEqualTo(ordinal == 2 ? "String" : "Boolean");
		}
		assertGroupsMatchOccurrences();
		assertThat(post(first, "application/json").statusCode()).isEqualTo(200);
		assertThat(groupPage("", 5, 0, 20)).isEqualTo(page);
		assertGroupsMatchOccurrences();

		UUID nullableId = UUID.randomUUID();
		try (Connection connection = jdbc.getDataSource().getConnection()) {
			insertGroup(connection, nullableId, "nullable_field", "2000-01-01T03:00:00.123456+03:00");
		}
		jdbc.update("UPDATE monitoring.violation_groups SET occurrence_count = ? WHERE group_id = ?",
				9007199254740993L, nullableId);
		JsonNode nullable = groupItem(groupPage("", 6, 0, 20), nullableId);
		assertThat(nullable.get("expectedType").isNull()).isTrue();
		assertThat(nullable.get("actualType").isNull()).isTrue();
		assertThat(nullable.path("occurrenceCount").isIntegralNumber()).isTrue();
		assertThat(nullable.path("occurrenceCount").asLong()).isEqualTo(9007199254740993L);
		assertThat(nullable.path("occurrenceCount").toString()).isEqualTo("9007199254740993");
		assertThat(nullable.path("firstSeenAt").asString()).isEqualTo("2000-01-01T00:00:00.123456Z");
	}

	@Test
	void groupListOrdersByTimeThenDescendingUuidAcrossPages() throws Exception {
		UUID low = UUID.fromString("11111111-1111-4111-8111-111111111111");
		UUID middle = UUID.fromString("88888888-8888-4888-8888-888888888888");
		UUID high = UUID.fromString("ffffffff-ffff-4fff-8fff-ffffffffffff");
		UUID latest = UUID.fromString("00000000-0000-4000-8000-000000000000");
		try (Connection connection = jdbc.getDataSource().getConnection()) {
			insertGroup(connection, middle, "middle", "2000-01-01T00:00:00Z");
			insertGroup(connection, low, "low", "2000-01-01T00:00:00Z");
			insertGroup(connection, high, "high", "2000-01-01T00:00:00Z");
			insertGroup(connection, latest, "latest", "2000-01-02T00:00:00Z");
		}
		assertThat(post(request(UUID.randomUUID().toString(), "Failed", MISSING)
				.replace(integration, UUID.randomUUID().toString()), "application/json").statusCode()).isEqualTo(201);
		JsonNode defaults = groupPage("", 4, 0, 20);
		assertGroupIds(defaults, latest, high, middle, low);
		assertThat(groupPage("", 4, 0, 20)).isEqualTo(defaults);
		assertThat(page(getGroups(integration.toUpperCase(Locale.ROOT), ""), 4, 0, 20)).isEqualTo(defaults);
		assertGroupIds(groupPage("?limit=2", 4, 0, 2), latest, high);
		assertGroupIds(groupPage("?offset=2&limit=2", 4, 2, 2), middle, low);
		List<UUID> expected = List.of(latest, high, middle, low);
		for (int offset = 0; offset < expected.size(); offset++) {
			assertGroupIds(groupPage("?offset=" + offset + "&limit=1", 4, offset, 1), expected.get(offset));
		}
	}

	@Test
	void groupListHandlesEmptyPagesAndValidatesParametersWithoutWriting(CapturedOutput output) throws Exception {
		assertThat(groupPage("", 0, 0, 20).path("items").isEmpty()).isTrue();
		assertThat(post(request(UUID.randomUUID().toString(), "Passed", ""), "application/json").statusCode()).isEqualTo(201);
		assertThat(groupPage("", 0, 0, 20).path("items").isEmpty()).isTrue();
		assertThat(post(request(UUID.randomUUID().toString(), "Failed", MISSING), "application/json").statusCode()).isEqualTo(201);
		var beforeReports = jdbc.queryForList("SELECT * FROM monitoring.reports ORDER BY report_row_id");
		var beforeViolations = jdbc.queryForList("SELECT * FROM monitoring.report_violations ORDER BY report_row_id, ordinal");
		var beforeGroups = jdbc.queryForList("SELECT * FROM monitoring.violation_groups ORDER BY group_id");
		assertThat(page(getGroups(UUID.randomUUID().toString(), ""), 0, 0, 20).path("items").isEmpty()).isTrue();
		assertThat(groupPage("?offset=2147483647&limit=100", 1, Integer.MAX_VALUE, 100).path("items").isEmpty()).isTrue();
		assertThat(groupPage("?offset=20", 1, 20, 20).path("items").isEmpty()).isTrue();
		JsonNode normal = groupPage("", 1, 0, 20);
		assertThat(groupPage("?offset=000&limit=020&from=invalid&status=Passed&contractId=unknown&sort=asc", 1, 0, 20))
				.isEqualTo(normal);
		for (var invalid : List.of(
				List.of("offset=", "offset"), List.of("offset=1&offset=2", "offset"),
				List.of("offset=-1", "offset"), List.of("offset=2147483648", "offset"),
				List.of("limit=0", "limit"), List.of("limit=101", "limit"), List.of("limit=", "limit"),
				List.of("limit=1&limit=2", "limit"), List.of("limit=%2B1", "limit"),
				List.of("limit=%201", "limit"), List.of("limit=1.0", "limit"), List.of("limit=1e1", "limit"),
				List.of("limit=999999999999999999999", "limit"), List.of("limit=private-query-marker", "limit"))) {
			JsonNode problem = assertProblem(getGroups(integration, "?" + invalid.getFirst()), 400, "INVALID_REQUEST");
			assertThat(problem.path("title").asString()).isEqualTo("Bad Request");
			assertThat(problem.path("detail").asString()).isEqualTo("Некорректные параметры списка групп.");
			assertThat(problem.path("errors").size()).isEqualTo(1);
			assertThat(problem.path("errors").path(invalid.get(1)).isArray()).isTrue();
			assertThat(problem.toString()).doesNotContain("private-query-marker", "?", invalid.getFirst());
		}
		for (String invalidId : List.of("1-1-1-1-1", "gggggggg-gggg-gggg-gggg-gggggggggggg")) {
			JsonNode problem = assertProblem(getGroups(invalidId, "?offset=-1&limit=101&ignored=private-query-marker"),
					400, "INVALID_REQUEST");
			assertThat(problem.path("errors").size()).isEqualTo(3);
			assertThat(problem.path("errors").path("integrationId").isArray()).isTrue();
			assertThat(problem.path("errors").toString()).doesNotContain(invalidId);
			assertThat(problem.path("instance").asString()).doesNotContain("?", "private-query-marker");
		}
		assertThat(output.getAll()).doesNotContain("private-query-marker", "1-1-1-1-1",
				"gggggggg-gggg-gggg-gggg-gggggggggggg");
		assertThat(jdbc.queryForList("SELECT * FROM monitoring.reports ORDER BY report_row_id")).isEqualTo(beforeReports);
		assertThat(jdbc.queryForList("SELECT * FROM monitoring.report_violations ORDER BY report_row_id, ordinal"))
				.isEqualTo(beforeViolations);
		assertThat(jdbc.queryForList("SELECT * FROM monitoring.violation_groups ORDER BY group_id")).isEqualTo(beforeGroups);
	}

	@Test
	void groupDetailsMatchListAndReportLinksWhileKeepingCreationMetadata() throws Exception {
		String firstId = UUID.randomUUID().toString();
		String secondId = UUID.randomUUID().toString();
		String mismatch = MISMATCH.replace("amount", " amount ");
		String first = request(firstId, "Failed", mismatch + "," + mismatch);
		assertThat(post(first, "application/json").statusCode()).isEqualTo(201);
		assertThat(post(request(secondId, "Failed", mismatch), "application/json").statusCode()).isEqualTo(201);
		UUID id = (UUID) violations(firstId).getFirst().get("group_id");
		JsonNode initial = groupDetails(id);
		assertThat(initial).isEqualTo(groupItem(groupPage("", 1, 0, 20), id));
		assertThat(initial.path("contractId").asString()).isEqualTo(CONTRACT);
		assertThat(initial.path("contractVersion").asInt()).isEqualTo(3);
		assertThat(initial.path("fieldName").asString()).isEqualTo(" amount ");
		assertThat(initial.path("type").asString()).isEqualTo("TypeMismatch");
		assertThat(initial.path("expectedType").asString()).isEqualTo("Number");
		assertThat(initial.path("actualType").asString()).isEqualTo("String");
		assertThat(initial.path("occurrenceCount").asLong()).isEqualTo(3);
		assertThat(Instant.parse(initial.path("firstSeenAt").asString())).isEqualTo(receivedAt(integration, firstId));
		assertThat(Instant.parse(initial.path("lastSeenAt").asString())).isEqualTo(receivedAt(integration, secondId));
		for (String reportId : List.of(firstId, secondId)) {
			for (JsonNode violation : details(integration, reportId).path("violations")) {
				assertThat(violation.path("groupId").asString()).isEqualTo(id.toString());
			}
		}

		String otherContract = UUID.randomUUID().toString();
		String otherId = UUID.randomUUID().toString();
		String nextId = UUID.randomUUID().toString();
		assertThat(post(request(otherId, "Failed", mismatch).replace(CONTRACT, otherContract),
				"application/json").statusCode()).isEqualTo(201);
		assertThat(post(request(nextId, "Failed", mismatch).replace("\"contractVersion\":3", "\"contractVersion\":4"),
				"application/json").statusCode()).isEqualTo(201);
		UUID otherGroup = (UUID) violations(otherId).getFirst().get("group_id");
		UUID nextGroup = (UUID) violations(nextId).getFirst().get("group_id");
		assertThat(List.of(id, otherGroup, nextGroup)).doesNotHaveDuplicates();
		assertThat(groupDetails(otherGroup).path("contractId").asString()).isEqualTo(otherContract);
		assertThat(groupDetails(otherGroup).path("contractVersion").asInt()).isEqualTo(3);
		assertThat(groupDetails(nextGroup).path("contractId").asString()).isEqualTo(CONTRACT);
		assertThat(groupDetails(nextGroup).path("contractVersion").asInt()).isEqualTo(4);

		String changedId = UUID.randomUUID().toString();
		assertThat(post(request(changedId, "Failed", mismatch.replace("\"Number\"", "\"Boolean\"")),
				"application/json").statusCode()).isEqualTo(201);
		JsonNode changed = details(integration, changedId).path("violations").get(0);
		assertThat(changed.path("groupId").asString()).isEqualTo(id.toString());
		assertThat(changed.path("expectedType").asString()).isEqualTo("Boolean");
		JsonNode current = groupDetails(id);
		assertThat(current.path("expectedType").asString()).isEqualTo("Number");
		assertThat(current.path("occurrenceCount").asLong()).isEqualTo(4);
		JsonNode page = groupPage("", 3, 0, 20);
		for (var stored : groups()) {
			UUID groupId = (UUID) stored.get("group_id");
			JsonNode detail = groupDetails(groupId);
			assertThat(detail).isEqualTo(groupItem(page, groupId));
			assertThat(detail.path("occurrenceCount").asLong()).isEqualTo(stored.get("occurrence_count"));
			for (String field : List.of("firstSeenAt", "lastSeenAt")) {
				String column = field.equals("firstSeenAt") ? "first_seen_at" : "last_seen_at";
				assertThat(Instant.parse(detail.path(field).asString()))
						.isEqualTo(((java.sql.Timestamp) stored.get(column)).toInstant());
			}
		}
		assertGroupsMatchOccurrences();
		assertThat(post(first, "application/json").statusCode()).isEqualTo(200);
		assertThat(groupDetails(id)).isEqualTo(current);
		assertThat(groupPage("", 3, 0, 20)).isEqualTo(page);
		assertGroupsMatchOccurrences();
	}

	@Test
	void groupDetailsHideForeignGroupsAndValidateBothIdsWithoutWriting(CapturedOutput output) throws Exception {
		String reportId = UUID.randomUUID().toString();
		assertThat(post(request(reportId, "Failed", MISMATCH), "application/json").statusCode()).isEqualTo(201);
		UUID id = (UUID) violations(reportId).getFirst().get("group_id");
		String otherIntegration = UUID.randomUUID().toString();
		assertThat(post(request(UUID.randomUUID().toString(), "Passed", "").replace(integration, otherIntegration),
				"application/json").statusCode()).isEqualTo(201);
		var beforeReports = jdbc.queryForList("SELECT * FROM monitoring.reports ORDER BY report_row_id");
		var beforeViolations = jdbc.queryForList("SELECT * FROM monitoring.report_violations ORDER BY report_row_id, ordinal");
		var beforeGroups = jdbc.queryForList("SELECT * FROM monitoring.violation_groups ORDER BY group_id");
		JsonNode canonical = groupDetails(id);
		var uppercase = getGroupDetails(integration.toUpperCase(Locale.ROOT), id.toString().toUpperCase(Locale.ROOT),
				"?offset=invalid&ignored=private-query-marker");
		assertThat(uppercase.statusCode()).isEqualTo(200);
		assertThat(json.readTree(uppercase.body())).isEqualTo(canonical);
		JsonNode foreign = assertProblem(getGroupDetails(otherIntegration, id.toString(), ""),
				404, "VIOLATION_GROUP_NOT_FOUND");
		JsonNode unknown = assertProblem(getGroupDetails(integration, "00000000-0000-0000-0000-000000000000", ""),
				404, "VIOLATION_GROUP_NOT_FOUND");
		JsonNode unknownIntegration = assertProblem(getGroupDetails(UUID.randomUUID().toString(), id.toString(),
				"?ignored=private-query-marker"), 404, "VIOLATION_GROUP_NOT_FOUND");
		assertThat(foreign.path("title").asString()).isEqualTo("Not Found");
		assertThat(foreign.path("detail").asString()).isEqualTo("Группа нарушений не найдена в указанной интеграции.");
		assertThat(foreign.path("errors").isEmpty()).isTrue();
		for (String field : List.of("type", "title", "status", "detail", "code", "errors")) {
			assertThat(unknown.get(field)).isEqualTo(foreign.get(field));
			assertThat(unknownIntegration.get(field)).isEqualTo(foreign.get(field));
		}
		for (var invalid : List.of(
				List.of("private-integration-marker", id.toString(), "integrationId"),
				List.of(integration, "1-1-1-1-1", "groupId"),
				List.of(integration, "gggggggg-gggg-gggg-gggg-gggggggggggg", "groupId"),
				List.of("private-integration-marker", "private-group-marker", "integrationId", "groupId"))) {
			JsonNode problem = assertProblem(getGroupDetails(invalid.get(0), invalid.get(1), "?ignored=private-query-marker"),
					400, "INVALID_REQUEST");
			assertThat(problem.path("detail").asString()).isEqualTo("Некорректные идентификаторы группы нарушений.");
			assertThat(problem.path("errors").size()).isEqualTo(invalid.size() - 2);
			for (String field : invalid.subList(2, invalid.size())) {
				assertThat(problem.path("errors").get(field))
						.isEqualTo(json.readTree("[\"Требуется UUID в стандартной строковой записи.\"]"));
			}
			assertThat(problem.path("detail").asString()).doesNotContain(invalid.get(0), invalid.get(1));
			assertThat(problem.path("errors").toString()).doesNotContain(invalid.get(0), invalid.get(1));
		}
		assertThat(output.getAll()).contains(
				"status=400, code=INVALID_REQUEST",
				"status=404, code=VIOLATION_GROUP_NOT_FOUND")
				.doesNotContain("private-integration-marker", "private-group-marker", "private-query-marker",
						"1-1-1-1-1", "gggggggg-gggg-gggg-gggg-gggggggggggg");
		assertThat(jdbc.queryForList("SELECT * FROM monitoring.reports ORDER BY report_row_id")).isEqualTo(beforeReports);
		assertThat(jdbc.queryForList("SELECT * FROM monitoring.report_violations ORDER BY report_row_id, ordinal"))
				.isEqualTo(beforeViolations);
		assertThat(jdbc.queryForList("SELECT * FROM monitoring.violation_groups ORDER BY group_id")).isEqualTo(beforeGroups);
	}

	@Test
	void groupDetailsPreserveNullMetadataAndExactInt64WithoutRecounting() throws Exception {
		UUID missing = UUID.randomUUID();
		UUID nullValue = UUID.randomUUID();
		try (Connection connection = jdbc.getDataSource().getConnection()) {
			insertGroup(connection, missing, "nullable_field", "2000-01-01T03:00:00.123456+03:00");
			insertGroup(connection, nullValue, "null_field", "2000-01-01T00:00:00Z");
		}
		jdbc.update("UPDATE monitoring.violation_groups SET occurrence_count = ? WHERE group_id = ?",
				9007199254740993L, missing);
		jdbc.update("UPDATE monitoring.violation_groups SET violation_kind = 'NULL_NOT_ALLOWED' WHERE group_id = ?", nullValue);
		var beforeReports = jdbc.queryForList("SELECT * FROM monitoring.reports ORDER BY report_row_id");
		var beforeViolations = jdbc.queryForList("SELECT * FROM monitoring.report_violations ORDER BY report_row_id, ordinal");
		var beforeGroups = jdbc.queryForList("SELECT * FROM monitoring.violation_groups ORDER BY group_id");
		JsonNode nullable = groupDetails(missing);
		assertThat(nullable.path("type").asString()).isEqualTo("RequiredFieldMissing");
		assertThat(nullable.path("occurrenceCount").isIntegralNumber()).isTrue();
		assertThat(nullable.path("occurrenceCount").asLong()).isEqualTo(9007199254740993L);
		assertThat(nullable.path("occurrenceCount").toString()).isEqualTo("9007199254740993");
		assertThat(nullable.path("firstSeenAt").asString()).isEqualTo("2000-01-01T00:00:00.123456Z");
		assertThat(nullable.get("firstSeenAt")).isEqualTo(nullable.get("lastSeenAt"));
		JsonNode nullDetail = groupDetails(nullValue);
		assertThat(nullDetail.path("type").asString()).isEqualTo("NullNotAllowed");
		assertThat(nullDetail.path("occurrenceCount").asLong()).isEqualTo(1);
		JsonNode page = groupPage("", 2, 0, 20);
		for (UUID id : List.of(missing, nullValue)) {
			JsonNode detail = groupDetails(id);
			assertThat(detail.get("expectedType").isNull()).isTrue();
			assertThat(detail.get("actualType").isNull()).isTrue();
			assertThat(detail).isEqualTo(groupItem(page, id));
		}
		assertThat(jdbc.queryForList("SELECT * FROM monitoring.reports ORDER BY report_row_id")).isEqualTo(beforeReports);
		assertThat(jdbc.queryForList("SELECT * FROM monitoring.report_violations ORDER BY report_row_id, ordinal"))
				.isEqualTo(beforeViolations);
		assertThat(jdbc.queryForList("SELECT * FROM monitoring.violation_groups ORDER BY group_id")).isEqualTo(beforeGroups);

		String reportId = UUID.randomUUID().toString();
		assertThat(post(request(reportId, "Failed", MISSING.replace("customer_id", "nullable_field")),
				"application/json").statusCode()).isEqualTo(201);
		JsonNode updated = groupDetails(missing);
		assertThat(updated.get("expectedType").isNull()).isTrue();
		assertThat(updated.path("occurrenceCount").asLong()).isEqualTo(9007199254740994L);
		JsonNode violation = details(integration, reportId).path("violations").get(0);
		assertThat(violation.path("expectedType").asString()).isEqualTo("String");
		assertThat(violation.path("groupId").asString()).isEqualTo(missing.toString());
	}

	@Test
	void groupListSeesCommittedCountsTotalAndOrderTogether() throws Exception {
		UUID updated = UUID.randomUUID();
		UUID leader = UUID.randomUUID();
		UUID created = UUID.randomUUID();
		try (Connection connection = jdbc.getDataSource().getConnection()) {
			insertGroup(connection, updated, "updated", "2000-01-01T00:00:00Z");
			insertGroup(connection, leader, "leader", "2000-01-02T00:00:00Z");
		}
		JsonNode before = groupPage("", 2, 0, 20);
		assertGroupIds(before, leader, updated);
		JsonNode previous = groupDetails(updated);
		assertThat(previous).isEqualTo(groupItem(before, updated));
		try (Connection held = jdbc.getDataSource().getConnection()) {
			held.setAutoCommit(false);
			try {
				try (var statement = held.prepareStatement("""
						UPDATE monitoring.violation_groups SET occurrence_count = occurrence_count + 1,
						last_seen_at = '2000-01-04T00:00:00Z' WHERE group_id = ?
						""")) {
					statement.setQueryTimeout(5);
					statement.setObject(1, updated);
					assertThat(statement.executeUpdate()).isEqualTo(1);
				}
				insertGroup(held, created, "created", "2000-01-03T00:00:00Z");
				assertThat(groupPage("", 2, 0, 20)).isEqualTo(before);
				assertThat(groupDetails(updated)).isEqualTo(previous);
				assertProblem(getGroupDetails(integration, created.toString(), ""), 404, "VIOLATION_GROUP_NOT_FOUND");
				held.commit();
				JsonNode after = groupPage("", 3, 0, 20);
				assertGroupIds(after, updated, created, leader);
				JsonNode item = groupItem(after, updated);
				assertThat(item.path("occurrenceCount").asLong()).isEqualTo(2);
				assertThat(item.path("firstSeenAt").asString()).isEqualTo("2000-01-01T00:00:00Z");
				assertThat(item.path("lastSeenAt").asString()).isEqualTo("2000-01-04T00:00:00Z");
				JsonNode detail = groupDetails(updated);
				assertThat(detail).isEqualTo(item);
				for (String field : List.of("groupId", "contractId", "contractVersion", "fieldName", "type",
						"expectedType", "actualType", "firstSeenAt")) {
					assertThat(detail.get(field)).isEqualTo(previous.get(field));
				}
				assertThat(groupDetails(created)).isEqualTo(groupItem(after, created));
			} finally {
				held.rollback();
			}
		}
	}

	@Test
	void summaryCountsReportsAcrossContractsAndVersionsAndIgnoresDuplicate() throws Exception {
		String failed = UUID.randomUUID().toString();
		String passed = UUID.randomUUID().toString();
		assertThat(post(request(passed, "Passed", ""), "application/json").statusCode()).isEqualTo(201);
		var accepted = confirmation(post(request(failed, "Failed", String.join(",", MISMATCH, MISMATCH, MISSING)),
				"application/json"), 201, integration, failed);
		assertThat(violations(failed)).hasSize(3);
		assertThat(groups()).hasSize(2);
		assertThat(groups().stream().mapToLong(row -> (long) row.get("occurrence_count")).sum()).isEqualTo(3);
		assertThat(post(request(UUID.randomUUID().toString(), "Failed", MISSING).replace("\"contractVersion\":3", "\"contractVersion\":4"),
				"application/json").statusCode()).isEqualTo(201);
		assertThat(post(request(UUID.randomUUID().toString(), "Passed", "").replace(CONTRACT, UUID.randomUUID().toString()),
				"application/json").statusCode()).isEqualTo(201);
		String other = UUID.randomUUID().toString();
		assertThat(post(request(failed, "Passed", "").replace(integration, other), "application/json").statusCode()).isEqualTo(201);
		var before = summaryTables();
		var body = summary(integration, 4, 2, 2);
		Instant maximum = jdbc.queryForObject("SELECT max(received_at) FROM monitoring.reports WHERE integration_id = ?",
				(row, index) -> row.getObject(1, OffsetDateTime.class).toInstant(), UUID.fromString(integration));
		assertThat(Instant.parse(body.path("lastReceivedAt").asString())).isEqualTo(maximum);
		summary(other, 1, 1, 0);
		assertThat(json.readTree(getSummary(integration, "?status=Failed&limit=1").body())).isEqualTo(body);
		assertThat(summaryTables()).isEqualTo(before);
		assertDuplicate(post(request(failed, "Passed", ""), "application/json"), accepted);
		assertThat(summary(integration, 4, 2, 2)).isEqualTo(body);
		assertThat(summaryTables()).isEqualTo(before);
	}

	@Test
	void summaryReturnsZerosAndValidatesUuid(CapturedOutput output) throws Exception {
		for (String id : List.of(integration, UUID.randomUUID().toString(), "00000000-0000-0000-0000-000000000000")) {
			assertThat(summary(id, 0, 0, 0).path("lastReceivedAt").isNull()).isTrue();
		}
		assertThat(summary(integration.toUpperCase(Locale.ROOT), 0, 0, 0).path("integrationId").asString()).isEqualTo(integration);
		var before = summaryTables();
		for (String id : List.of("not-a-uuid", "1-1-1-1-1", "%20" + integration, "%7B" + integration + "%7D")) {
			var body = assertProblem(getSummary(id, "?ignored=sample"), 400, "INVALID_REQUEST");
			assertThat(body.size()).isEqualTo(7);
			assertThat(body.path("detail").asString()).isEqualTo("Некорректный идентификатор интеграции.");
			assertThat(body.path("errors").size()).isEqualTo(1);
			assertThat(body.path("errors").path("integrationId").get(0).asString())
					.isEqualTo("Требуется UUID в стандартной строковой записи.");
		}
		assertThat(summaryTables()).isEqualTo(before);
		assertThat(output.getAll()).doesNotContain("not-a-uuid", "ignored=sample");
	}

	@Test
	void summarySeesCommittedAggregateAndMaximumWithoutWaiting() throws Exception {
		assertThat(summary(integration, 0, 0, 0).path("lastReceivedAt").isNull()).isTrue();
		try (Connection held = jdbc.getDataSource().getConnection()) {
			held.setAutoCommit(false);
			try {
				insertSummaryReport(held, "PASSED", "2000-01-02T03:04:05.123456+03:00");
				insertSummaryReport(held, "FAILED", "2000-01-02T00:04:05.123456Z");
				assertThat(summary(integration, 0, 0, 0).path("lastReceivedAt").isNull()).isTrue();
				held.commit();
				assertThat(summary(integration, 2, 1, 1).path("lastReceivedAt").asString()).isEqualTo("2000-01-02T00:04:05.123456Z");
				insertSummaryReport(held, "PASSED", "2000-01-01T00:00:00Z");
				held.commit();
				var committed = summary(integration, 3, 2, 1);
				assertThat(committed.path("lastReceivedAt").asString()).isEqualTo("2000-01-02T00:04:05.123456Z");
				insertSummaryReport(held, "FAILED", "2000-01-03T00:00:00Z");
				held.rollback();
				assertThat(summary(integration, 3, 2, 1)).isEqualTo(committed);
			} finally {
				held.rollback();
			}
		}
	}

	@Test
	void summaryPeriodUsesMicrosecondBoundsAndMatchesHistoryTotals() throws Exception {
		String start = "2026-10-26T00:00:00.123456Z";
		String end = "2026-10-27T00:00:00Z";
		storeHistoryFixture(UUID.randomUUID().toString(), "Failed", MISSING, "2026-10-26T00:00:00.123455Z");
		storeHistoryFixture(UUID.randomUUID().toString(), "Passed", "", start);
		String failed = request(UUID.randomUUID().toString(), "Failed", MISSING + "," + MISMATCH + "," + MISMATCH,
				"2026-10-26T12:00:00Z").replace(CONTRACT, UUID.randomUUID().toString())
				.replace("\"contractVersion\":3", "\"contractVersion\":4");
		assertThat(post(failed, "application/json").statusCode()).isEqualTo(201);
		storeHistoryFixture(UUID.randomUUID().toString(), "Passed", "", "2026-10-26T23:59:59.999999Z");
		storeHistoryFixture(UUID.randomUUID().toString(), "Failed", MISSING, end);
		storeHistoryFixture(UUID.randomUUID().toString(), "Passed", "", "2026-10-27T00:00:00.000001Z");
		assertThat(post(request(UUID.randomUUID().toString(), "Failed", MISSING, start)
				.replace(integration, UUID.randomUUID().toString()), "application/json").statusCode()).isEqualTo(201);
		var before = summaryTables();
		String period = "?from=" + start + "&to=" + end;
		var selected = summary(integration, period, 3, 2, 1);
		assertThat(selected.path("lastReceivedAt").asString()).isEqualTo("2026-10-26T23:59:59.999999Z");
		assertSummaryMatchesHistory(period, selected);
		assertSummaryMatchesHistory("?from=" + start, summary(integration, "?from=" + start, 5, 3, 2));
		assertSummaryMatchesHistory("?to=" + end, summary(integration, "?to=" + end, 4, 2, 2));
		assertSummaryMatchesHistory("", summary(integration, 6, 3, 3));
		assertThat(json.readTree(getSummary(integration, period + "&contractId=invalid&status=invalid&limit=bad&ignored=x").body()))
				.isEqualTo(selected);
		for (String query : List.of("?to=2026-01-01T00:00:00Z", "?from=2027-01-01T00:00:00Z",
				"?from=2026-10-28T00:00:00Z&to=2026-10-29T00:00:00Z")) {
			assertThat(summary(integration, query, 0, 0, 0).path("lastReceivedAt").isNull()).isTrue();
		}
		assertThat(summary(UUID.randomUUID().toString(), period, 0, 0, 0).path("lastReceivedAt").isNull()).isTrue();
		assertThat(summaryTables()).isEqualTo(before);
	}

	@ParameterizedTest
	@CsvSource({
			"UTC,2026-10-26,2026-10-26,2026-10-26T00:00:00Z,2026-10-27T00:00:00Z,24",
			"Europe/Moscow,2026-10-26,2026-10-26,2026-10-25T21:00:00Z,2026-10-26T21:00:00Z,24",
			"Europe/Moscow,2026-10-24,2026-10-26,2026-10-23T21:00:00Z,2026-10-26T21:00:00Z,72",
			"Europe/Berlin,2026-03-29,2026-03-29,2026-03-28T23:00:00Z,2026-03-29T22:00:00Z,23",
			"Europe/Berlin,2026-10-25,2026-10-25,2026-10-24T22:00:00Z,2026-10-25T23:00:00Z,25"})
	void summaryAcceptsConsumerCalendarExamplesIncludingLastDayAndDst(String zone, String first, String last,
			String expectedFrom, String expectedTo, long hours) throws Exception {
		ZoneId browserZone = ZoneId.of(zone);
		Instant from = LocalDate.parse(first).atStartOfDay(browserZone).toInstant();
		Instant to = LocalDate.parse(last).plusDays(1).atStartOfDay(browserZone).toInstant();
		assertThat(from).isEqualTo(Instant.parse(expectedFrom));
		assertThat(to).isEqualTo(Instant.parse(expectedTo));
		assertThat(Duration.between(from, to).toHours()).isEqualTo(hours);
		for (Instant time : List.of(from.minusNanos(1000), from, to.minusNanos(1000), to, to.plusNanos(1000))) {
			storeHistoryFixture(UUID.randomUUID().toString(), "Passed", "", time.toString());
		}
		long extra = 0;
		if (hours == 25) {
			Instant firstHour = Instant.parse("2026-10-25T00:30:00Z");
			Instant repeatedHour = Instant.parse("2026-10-25T01:30:00Z");
			assertThat(firstHour.atZone(browserZone).toLocalDateTime()).isEqualTo(repeatedHour.atZone(browserZone).toLocalDateTime());
			storeHistoryFixture(UUID.randomUUID().toString(), "Passed", "", firstHour.toString());
			storeHistoryFixture(UUID.randomUUID().toString(), "Passed", "", repeatedHour.toString());
			extra = 2;
		}
		String query = "?from=" + from + "&to=" + to;
		var selected = summary(integration, query, 2 + extra, 2 + extra, 0);
		assertThat(Instant.parse(selected.path("lastReceivedAt").asString())).isEqualTo(to.minusNanos(1000));
		assertSummaryMatchesHistory(query, selected);
		assertThat(application.getBean(JdbcTemplate.class).queryForObject("SHOW TimeZone", String.class))
				.isEqualTo(System.getProperty("monitoring.test.database.timezone", "UTC"));
	}

	@Test
	void summaryUsesProvidedTodayBoundsAndStoredSdkTimeWithoutNowCutoff() throws Exception {
		Instant fixedNow = Instant.parse("2026-10-25T21:30:00Z");
		storeHistoryFixture(UUID.randomUUID().toString(), "Passed", "", "2026-10-25T21:15:00Z");
		storeHistoryFixture(UUID.randomUUID().toString(), "Passed", "", "2026-10-25T20:59:59.999999Z");
		storeHistoryFixture(UUID.randomUUID().toString(), "Failed", MISSING, "9999-12-30T23:59:59.999999Z");
		for (String zone : List.of("UTC", "Europe/Moscow")) {
			ZoneId browserZone = ZoneId.of(zone);
			LocalDate today = fixedNow.atZone(browserZone).toLocalDate();
			String query = "?from=" + today.atStartOfDay(browserZone).toInstant()
					+ "&to=" + today.plusDays(1).atStartOfDay(browserZone).toInstant();
			var selected = summary(integration, query, zone.equals("UTC") ? 2 : 1, zone.equals("UTC") ? 2 : 1, 0);
			assertSummaryMatchesHistory(query, selected);
		}
		assertThat(summary(integration, "?from=9999-12-30T00:00:00Z&to=9999-12-31T00:00:00Z", 1, 0, 1)
				.path("lastReceivedAt").asString()).isEqualTo("9999-12-30T23:59:59.999999Z");
	}

	@Test
	void summaryPeriodDuplicateKeepsFirstDayEvenWithConcurrentDifferentTimestamp() throws Exception {
		String id = UUID.randomUUID().toString();
		String original = request(id, "Failed", MISMATCH + "," + MISMATCH + "," + MISSING, "2026-10-25T23:59:59.999999Z");
		var accepted = confirmation(post(original, "application/json"), 201, integration, id);
		String yesterday = "?from=2026-10-25T00:00:00Z&to=2026-10-26T00:00:00Z";
		String today = "?from=2026-10-26T00:00:00Z&to=2026-10-27T00:00:00Z";
		var saved = summary(integration, yesterday, 1, 0, 1);
		var before = summaryTables();
		try (var executor = Executors.newFixedThreadPool(4)) {
			var duplicates = new ArrayList<Future<HttpResponse<String>>>();
			for (int i = 0; i < 4; i++) {
				duplicates.add(executor.submit(() -> post(request(id, "Passed", "", "2026-10-26T12:00:00Z"), "application/json")));
			}
			for (var duplicate : duplicates) {
				assertDuplicate(duplicate.get(10, TimeUnit.SECONDS), accepted);
			}
		}
		assertThat(summary(integration, yesterday, 1, 0, 1)).isEqualTo(saved);
		assertThat(summary(integration, today, 0, 0, 0).path("lastReceivedAt").isNull()).isTrue();
		assertThat(summaryTables()).isEqualTo(before);
	}

	@Test
	void summaryPeriodReadsCommittedCountersAndMaximumTogether() throws Exception {
		String period = "?from=2000-01-02T00:00:00Z&to=2000-01-03T00:00:00Z";
		try (Connection held = jdbc.getDataSource().getConnection()) {
			held.setAutoCommit(false);
			try {
				insertSummaryReport(held, "PASSED", "2000-01-03T00:00:00Z");
				held.commit();
				insertSummaryReport(held, "FAILED", "2000-01-02T12:00:00.123456Z");
				insertSummaryReport(held, "PASSED", "2000-01-02T00:00:00Z");
				insertSummaryReport(held, "PASSED", "2000-01-02T12:00:00.123456Z");
				assertThat(summary(integration, period, 0, 0, 0).path("lastReceivedAt").isNull()).isTrue();
				held.commit();
				var committed = summary(integration, period, 3, 2, 1);
				assertThat(committed.path("lastReceivedAt").asString()).isEqualTo("2000-01-02T12:00:00.123456Z");
				insertSummaryReport(held, "FAILED", "2000-01-02T23:59:59.999999Z");
				assertThat(summary(integration, period, 3, 2, 1)).isEqualTo(committed);
				held.rollback();
				assertThat(summary(integration, period, 3, 2, 1)).isEqualTo(committed);
				assertSummaryMatchesHistory(period, committed);
			} finally {
				held.rollback();
			}
		}
	}

	@Test
	void summaryAndHistoryRejectSamePeriodOverHttpWithoutWriting(CapturedOutput output) throws Exception {
		storeHistoryFixture(UUID.randomUUID().toString(), "Passed", "", "2028-02-29T00:00:00.1Z");
		String leapDay = "?from=2028-02-29T00:00:00.1Z&to=2028-02-29T00:00:00.100001Z";
		assertSummaryMatchesHistory(leapDay, summary(integration, leapDay, 1, 1, 0));
		var before = summaryTables();
		for (String query : List.of("?from=private-period-marker", "?to=%20",
				"?from=2026-10-26T00:00:00Z&from=2026-10-26T00:00:00Z",
				"?from=2026-10-27T00:00:00Z&to=2026-10-26T00:00:00Z")) {
			var problem = assertProblem(getSummary(integration, query), 400, "INVALID_REQUEST");
			assertThat(problem.path("detail").asString()).isEqualTo("Некорректные параметры сводки.");
			assertThat(problem.path("errors")).isEqualTo(assertProblem(getHistory(integration, query), 400, "INVALID_REQUEST").path("errors"));
			assertThat(problem.path("instance").asString()).doesNotContain("?");
			assertThat(problem.toString()).doesNotContain("private-period-marker");
		}
		assertThat(summaryTables()).isEqualTo(before);
		assertThat(output.getAll()).doesNotContain("private-period-marker");
	}

	private void assertSummaryMatchesHistory(String period, JsonNode selected) throws Exception {
		String separator = period.isEmpty() ? "?" : "&";
		history(period + separator + "limit=1&offset=2147483647", selected.path("totalReports").asLong(), Integer.MAX_VALUE, 1);
		history(period + separator + "status=Passed&limit=1", selected.path("passedReports").asLong(), 0, 1);
		history(period + separator + "status=Failed&limit=1", selected.path("failedReports").asLong(), 0, 1);
		var latest = history(period + separator + "limit=1", selected.path("totalReports").asLong(), 0, 1);
		if (selected.path("totalReports").asLong() > 0) {
			assertThat(latest.path("items").get(0).path("receivedAt")).isEqualTo(selected.path("lastReceivedAt"));
		}
	}

	@Test
	void openApiDescribesOnlyImplementedBusinessOperationsAndServesSwagger() throws Exception {
		var response = getResource("/v3/api-docs");
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json");
		var api = json.readTree(response.body());
		assertThat(api.path("openapi").asString()).isEqualTo("3.0.3");
		String root = "/internal/v1/integrations/{integrationId}";
		var expected = Map.of("/internal/v1/reports", List.of("200", "201", "400", "415", "500", "503"),
				"/internal/v1/integration-summaries/query", List.of("200", "400", "413", "415", "500", "503"),
				root + "/reports", List.of("200", "400", "500", "503"),
				root + "/reports/{reportId}", List.of("200", "400", "404", "500", "503"),
				root + "/violation-groups", List.of("200", "400", "500", "503"),
				root + "/violation-groups/{groupId}", List.of("200", "400", "404", "500", "503"),
				root + "/summary", List.of("200", "400", "500", "503"));
		assertThat(api.path("paths").propertyNames()).containsExactlyInAnyOrderElementsOf(expected.keySet());
		var ids = new java.util.HashSet<String>();
		for (var route : expected.entrySet()) {
			var path = api.path("paths").path(route.getKey());
			assertThat(path.size()).isEqualTo(1);
			var operation = path.path(route.getKey().equals("/internal/v1/reports")
					|| route.getKey().equals("/internal/v1/integration-summaries/query") ? "post" : "get");
			assertThat(ids.add(operation.path("operationId").asString())).isTrue();
			assertThat(operation.path("summary").asString()).isNotBlank();
			assertThat(operation.path("description").asString()).isNotBlank();
			assertThat(operation.path("tags").size()).isEqualTo(1);
			assertThat(operation.path("responses").propertyNames()).containsExactlyInAnyOrderElementsOf(route.getValue());
			for (String status : route.getValue()) {
				var content = operation.path("responses").path(status).path("content");
				String media = status.startsWith("2") ? "application/json" : "application/problem+json";
				assertThat(content.propertyNames()).containsExactly(media);
				assertThat(content.path(media).has("schema")).isTrue();
				assertThat(content.path(media).path("examples").size()).isPositive();
			}
			for (var parameter : operation.path("parameters")) {
				assertThat(parameter.path("name").asString()).isNotEqualTo("parameters");
				if (parameter.path("in").asString().equals("path")) {
					assertThat(parameter.path("required").asBoolean()).isTrue();
					assertThat(parameter.path("schema").path("format").asString()).isEqualTo("uuid");
				}
			}
		}
		var schemas = api.path("components").path("schemas");
		var history = api.path("paths").path(root + "/reports").path("get");
		var contractId = history.path("parameters").valueStream()
				.filter(parameter -> parameter.path("name").asString().equals("contractId")).findFirst().orElseThrow();
		assertThat(contractId.path("in").asString()).isEqualTo("query");
		assertThat(contractId.path("style").asString()).isEqualTo("form");
		assertThat(contractId.path("explode").asBoolean()).isTrue();
		assertThat(contractId.path("required").asBoolean()).isFalse();
		assertThat(contractId.path("schema").path("type").asString()).isEqualTo("array");
		assertThat(contractId.path("schema").path("maxItems").asInt()).isEqualTo(50);
		assertThat(contractId.path("schema").path("minItems").asInt()).isEqualTo(1);
		assertThat(contractId.path("schema").path("uniqueItems").asBoolean()).isFalse();
		assertThat(contractId.path("schema").path("items").path("format").asString()).isEqualTo("uuid");
		var contractError = history.path("responses").path("400").path("content").path("application/problem+json")
				.path("examples").path("invalidContractId").path("value");
		assertThat(contractError.path("code").asString()).isEqualTo("INVALID_REQUEST");
		assertThat(contractError.path("errors").has("contractId")).isTrue();
		var report = schemas.path("ValidationReport");
		assertThat(report.path("required").valueStream().map(JsonNode::asString).toList()).contains("timestamp");
		var timestamp = report.path("properties").path("timestamp");
		assertThat(timestamp.path("type").asString()).isEqualTo("string");
		assertThat(timestamp.path("format").asString()).isEqualTo("date-time");
		assertThat(timestamp.path("pattern").asString()).isEqualTo(contractwatcher.monitoring.dto.ApiSchema.REPORT_TIMESTAMP_PATTERN);
		assertThat(timestamp.path("nullable").asBoolean()).isFalse();
		var summary = schemas.path("IntegrationSummary");
		var summaryOperation = api.path("paths").path(root + "/summary").path("get");
		assertThat(summaryOperation.path("parameters").valueStream().filter(p -> p.path("in").asString().equals("query"))
				.map(p -> p.path("name").asString()).toList()).containsExactlyInAnyOrder("from", "to");
		for (var parameter : summaryOperation.path("parameters")) {
			if (parameter.path("in").asString().equals("query")) {
				assertThat(parameter.path("required").asBoolean()).isFalse();
				assertThat(parameter.path("schema").path("pattern").asString())
						.isEqualTo(contractwatcher.monitoring.dto.ApiSchema.UTC_PATTERN);
				assertThat(parameter.path("schema").path("maxLength").asInt()).isEqualTo(27);
			}
		}
		var periodError = summaryOperation.path("responses").path("400").path("content").path("application/problem+json")
				.path("examples").path("invalidPeriod").path("value");
		assertThat(periodError.path("detail").asString()).isEqualTo("Некорректные параметры сводки.");
		assertThat(periodError.path("errors").has("to")).isTrue();
		assertThat(summaryOperation.path("responses").path("200").path("content").path("application/json")
				.path("examples").path("selectedDay").path("value").path("lastReceivedAt").asString())
				.isEqualTo("2026-10-26T20:59:59.999999Z");
		assertThat(summary.path("required").valueStream().map(JsonNode::asString).toList())
				.containsExactlyInAnyOrder("integrationId", "totalReports", "passedReports", "failedReports", "lastReceivedAt");
		assertThat(summary.path("properties").path("lastReceivedAt").path("nullable").asBoolean()).isTrue();
		assertThat(summary.path("properties").path("lastReceivedAt").has("$ref")).isFalse();
		assertThat(summary.path("properties").path("totalReports").path("format").asString()).isEqualTo("int64");
		assertThat(summary.path("properties").path("totalReports").path("maximum").asLong()).isEqualTo(Long.MAX_VALUE);
		assertThat(schemas.path("Problem").path("properties").path("errors").path("additionalProperties").path("type").asString())
				.isEqualTo("array");
		assertThat(schemas.path("ReportDetailsViolation").path("properties").path("groupId").path("nullable").asBoolean()).isTrue();
		assertThat(schemas.path("ViolationGroup").path("properties").path("expectedType").path("nullable").asBoolean()).isTrue();
		assertThat(api.path("components").has("securitySchemes")).isFalse();
		var redirect = getResource("/swagger-ui.html");
		assertThat(redirect.statusCode()).isBetween(301, 308);
		String uiPath = redirect.headers().firstValue("Location").orElseThrow();
		assertThat(getResource(uiPath).body()).contains("Swagger UI", "swagger-ui-bundle.js");
		var config = json.readTree(getResource("/v3/api-docs/swagger-config").body());
		assertThat(config.path("url").asString()).isEqualTo("/v3/api-docs");
		assertThat(getResource("/swagger-ui/swagger-ui-bundle.js").statusCode()).isEqualTo(200);
	}

	@Test
	void rejectsMissingNullAndMalformedTimestampWithoutWriting(CapturedOutput output) throws Exception {
		String timestamp = "2026-10-20T10:00:00Z";
		String valid = request(UUID.randomUUID().toString(), "Passed", "", timestamp);
		String property = "\"timestamp\":\"" + timestamp + "\"";
		var before = summaryTables();
		for (String body : List.of(valid.replace(property + ",", ""), valid.replace(property, "\"timestamp\":null"))) {
			var problem = assertProblem(post(body, "application/json"), 400, "VALIDATION_REPORT_INVALID");
			assertThat(problem.path("errors").propertyNames()).containsExactly("report.timestamp");
			assertThat(problem.path("errors").path("report.timestamp").get(0).asString()).isEqualTo("Обязательное поле.");
		}
		for (String value : List.of("42", "true", "[]", "\"\"", "\"timestamp-invalid-sample\"",
				"\"2026-10-20T10:00:00\"", "\"2026-02-30T10:00:00Z\"", "\"2026-10-20T24:00:00Z\"",
				"\"2026-10-20T10:00:60Z\"", "\"2026-10-20T10:00:00+19:00\"", "\"2026-10-20T10:00:00.1234567890Z\"")) {
			assertProblem(post(valid.replace(property, "\"timestamp\":" + value), "application/json"), 400, "INVALID_REQUEST");
		}
		var range = assertProblem(post(request(UUID.randomUUID().toString(), "Passed", "", "0001-01-01T00:00:00+01:00"),
				"application/json"), 400, "VALIDATION_REPORT_INVALID");
		assertThat(range.path("errors").propertyNames()).containsExactly("report.timestamp");
		assertThat(summaryTables()).isEqualTo(before);
		assertThat(output.getAll()).doesNotContain("timestamp-invalid-sample");
	}

	@Test
	void sdkTimeControlsHistoryGroupsAndSummaryRegardlessOfDeliveryOrder() throws Exception {
		String late = UUID.randomUUID().toString();
		String early = UUID.randomUUID().toString();
		String passed = UUID.randomUUID().toString();
		var accepted = confirmation(post(request(late, "Failed", MISSING, "2026-10-20T12:00:00.1234567+03:00"),
				"application/json"), 201, integration, late);
		assertThat(accepted.path("receivedAt").asString()).isEqualTo("2026-10-20T09:00:00.123456Z");
		assertThat(post(request(early, "Failed", MISSING, "2026-10-19T10:00:00+00:00"), "application/json").statusCode()).isEqualTo(201);
		assertThat(post(request(passed, "Passed", "", "2026-10-20T08:00:00Z"), "application/json").statusCode()).isEqualTo(201);
		assertHistoryIds(history("", 3, 0, 20), late, passed, early);
		assertHistoryIds(history("?from=2026-10-20T00:00:00Z&to=2026-10-21T00:00:00Z", 2, 0, 20), late, passed);
		var group = groupPage("", 1, 0, 20).path("items").get(0);
		assertThat(group.path("occurrenceCount").asLong()).isEqualTo(2);
		assertThat(group.path("firstSeenAt").asString()).isEqualTo("2026-10-19T10:00:00Z");
		assertThat(group.path("lastSeenAt").asString()).isEqualTo("2026-10-20T09:00:00.123456Z");
		assertThat(groupDetails(UUID.fromString(group.path("groupId").asString()))).isEqualTo(group);
		assertThat(details(integration, late).path("receivedAt")).isEqualTo(accepted.path("receivedAt"));
		var summary = summary(integration, 3, 1, 2);
		assertThat(summary.path("lastReceivedAt")).isEqualTo(accepted.path("receivedAt"));
		var before = summaryTables();
		assertDuplicate(post(request(late, "Failed", MISSING, "2030-01-01T00:00:00Z"), "application/json"), accepted);
		assertThat(summary(integration, 3, 1, 2)).isEqualTo(summary);
		assertThat(summaryTables()).isEqualTo(before);
	}

	private HttpResponse<String> getResource(String path) throws Exception {
		return http.send(HttpRequest.newBuilder(endpoint.resolve(path)).timeout(Duration.ofSeconds(30)).GET().build(),
				HttpResponse.BodyHandlers.ofString());
	}

	private void insertSummaryReport(Connection connection, String outcome, String time) throws SQLException {
		try (var statement = connection.prepareStatement("""
				INSERT INTO monitoring.reports (external_report_id, integration_id, contract_id, contract_version, outcome, received_at)
				VALUES (?, ?, ?, 3, ?, ?)
				""")) {
			statement.setQueryTimeout(5);
			statement.setObject(1, UUID.randomUUID());
			statement.setObject(2, UUID.fromString(integration));
			statement.setObject(3, UUID.fromString(CONTRACT));
			statement.setString(4, outcome);
			statement.setObject(5, OffsetDateTime.parse(time));
			assertThat(statement.executeUpdate()).isEqualTo(1);
		}
	}

	private List<List<Map<String, Object>>> summaryTables() {
		return List.of(jdbc.queryForList("SELECT * FROM monitoring.reports ORDER BY report_row_id"),
				jdbc.queryForList("SELECT * FROM monitoring.report_violations ORDER BY report_row_id, ordinal"),
				jdbc.queryForList("SELECT * FROM monitoring.violation_groups ORDER BY group_id"));
	}

	private HttpResponse<String> getSummary(String id, String query) throws Exception {
		return http.send(HttpRequest.newBuilder(endpoint.resolve("/internal/v1/integrations/" + id + "/summary" + query))
				.timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofString());
	}

	private JsonNode summary(String id, long total, long passed, long failed) throws Exception {
		return summary(id, "", total, passed, failed);
	}

	private JsonNode summary(String id, String query, long total, long passed, long failed) throws Exception {
		var response = getSummary(id, query);
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json");
		var body = json.readTree(response.body());
		assertThat(body.size()).isEqualTo(5);
		assertThat(body.path("integrationId").asString()).isEqualTo(id.toLowerCase(Locale.ROOT));
		assertThat(body.path("totalReports").asLong()).isEqualTo(total);
		assertThat(body.path("passedReports").asLong()).isEqualTo(passed);
		assertThat(body.path("failedReports").asLong()).isEqualTo(failed);
		return body;
	}

	private void insertGroup(Connection connection, UUID id, String field, String time) throws SQLException {
		try (var statement = connection.prepareStatement("""
				INSERT INTO monitoring.violation_groups
				(group_id, integration_id, contract_id, contract_version, field_name, violation_kind,
				expected_type, actual_type, occurrence_count, first_seen_at, last_seen_at)
				VALUES (?, ?, ?, 3, ?, 'REQUIRED_FIELD_MISSING', NULL, NULL, 1, ?, ?)
				""")) {
			statement.setQueryTimeout(5);
			statement.setObject(1, id);
			statement.setObject(2, UUID.fromString(integration));
			statement.setObject(3, UUID.fromString(CONTRACT));
			statement.setString(4, field);
			statement.setObject(5, OffsetDateTime.parse(time));
			statement.setObject(6, OffsetDateTime.parse(time));
			assertThat(statement.executeUpdate()).isEqualTo(1);
		}
	}

	private HttpResponse<String> getGroups(String integrationId, String query) throws Exception {
		URI uri = endpoint.resolve("/internal/v1/integrations/" + integrationId + "/violation-groups" + query);
		return http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15)).GET().build(),
				HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> getGroupDetails(String integrationId, String groupId, String query) throws Exception {
		URI uri = endpoint.resolve("/internal/v1/integrations/" + integrationId + "/violation-groups/" + groupId + query);
		return http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15)).GET().build(),
				HttpResponse.BodyHandlers.ofString());
	}

	private JsonNode groupDetails(UUID id) throws Exception {
		var response = getGroupDetails(integration, id.toString(), "");
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json");
		JsonNode body = json.readTree(response.body());
		assertThat(body.size()).isEqualTo(10);
		assertThat(body.path("groupId").asString()).isEqualTo(id.toString());
		assertThat(body.path("firstSeenAt").asString()).endsWith("Z");
		assertThat(body.path("lastSeenAt").asString()).endsWith("Z");
		return body;
	}

	private JsonNode groupPage(String query, long total, int offset, int limit) throws Exception {
		return page(getGroups(integration, query), total, offset, limit);
	}

	private JsonNode groupItem(JsonNode page, UUID id) {
		for (JsonNode item : page.path("items")) {
			if (item.path("groupId").asString().equals(id.toString())) {
				return item;
			}
		}
		throw new AssertionError("Ожидаемая группа отсутствует в странице.");
	}

	private void assertGroupIds(JsonNode page, UUID... expected) {
		List<UUID> actual = new ArrayList<>();
		for (JsonNode item : page.path("items")) {
			actual.add(UUID.fromString(item.path("groupId").asString()));
		}
		assertThat(actual).containsExactly(expected);
	}

	private HttpResponse<String> getDetails(String integrationId, String reportId, String query) throws Exception {
		URI uri = endpoint.resolve("/internal/v1/integrations/" + integrationId + "/reports/" + reportId + query);
		return http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15)).GET().build(),
				HttpResponse.BodyHandlers.ofString());
	}

	private JsonNode details(String integrationId, String reportId) throws Exception {
		var response = getDetails(integrationId, reportId, "");
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json");
		JsonNode body = json.readTree(response.body());
		var stored = report(integrationId, reportId);
		assertThat(body.size()).isEqualTo(8);
		assertThat(body.path("integrationId").asString()).isEqualTo(integrationId);
		assertThat(body.path("reportId").asString()).isEqualTo(reportId);
		assertThat(body.path("contractId").asString()).isEqualTo(stored.get("contract_id").toString());
		assertThat(body.path("contractVersion").asInt()).isEqualTo(stored.get("contract_version"));
		assertThat(body.path("receivedAt").asString()).endsWith("Z");
		assertThat(Instant.parse(body.path("receivedAt").asString())).isEqualTo(receivedAt(integrationId, reportId));
		assertThat(body.path("violations").isArray()).isTrue();
		assertThat(body.path("violationCount").asLong()).isEqualTo((long) violations(integrationId, reportId).size());
		assertThat(body.path("violations").size()).isEqualTo(body.path("violationCount").asLong());
		return body;
	}

	private void awaitBlockedHistory(int blocker) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		do {
			Boolean blocked = jdbc.queryForObject("""
					SELECT EXISTS (SELECT 1 FROM pg_stat_activity
					WHERE datname = current_database() AND ? = ANY(pg_blocking_pids(pid))
					AND wait_event_type = 'Lock' AND query LIKE '%AS violation_count%'
					AND backend_xmin IS NOT NULL)
					""", Boolean.class, blocker);
			if (Boolean.TRUE.equals(blocked)) {
				return;
			}
			Thread.sleep(25);
		} while (System.nanoTime() < deadline);
		throw new AssertionError("Страница истории не дождалась блокировки после COUNT.");
	}

	private void storeHistoryFixture(String id, String status, String violations, String time) throws Exception {
		assertThat(post(request(id, status, violations, time), "application/json").statusCode()).isEqualTo(201);
	}

	private void setHistoryTime(String id, String time) {
		jdbc.update("UPDATE monitoring.reports SET received_at = ? WHERE integration_id = ? AND external_report_id = ?",
				OffsetDateTime.parse(time), UUID.fromString(integration), UUID.fromString(id));
	}

	private HttpResponse<String> getHistory(String integrationId, String query) throws Exception {
		URI uri = endpoint.resolve("/internal/v1/integrations/" + integrationId + "/reports" + query);
		return http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15)).GET().build(),
				HttpResponse.BodyHandlers.ofString());
	}

	private JsonNode history(String query, long total, int offset, int limit) throws Exception {
		return page(getHistory(integration, query), total, offset, limit);
	}

	private JsonNode page(HttpResponse<String> response, long total, int offset, int limit) {
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json");
		JsonNode body = json.readTree(response.body());
		assertThat(body.size()).isEqualTo(4);
		assertThat(body.path("items").isArray()).isTrue();
		assertThat(body.path("total").asLong()).isEqualTo(total);
		assertThat(body.path("offset").asInt()).isEqualTo(offset);
		assertThat(body.path("limit").asInt()).isEqualTo(limit);
		return body;
	}

	private void assertHistoryIds(JsonNode page, String... expected) {
		List<String> actual = new ArrayList<>();
		for (JsonNode item : page.path("items")) {
			actual.add(item.path("reportId").asString());
		}
		assertThat(actual).containsExactly(expected);
	}

	private void awaitBlockedGroupRequests(int blocker, int count) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		do {
			Boolean blocked = jdbc.queryForObject("""
					SELECT count(*) = ? AND bool_or(? = ANY(pg_blocking_pids(pid))) FROM pg_stat_activity
					WHERE datname = current_database() AND wait_event_type = 'Lock'
					AND query LIKE '%INSERT INTO monitoring.violation_groups%'
					""", Boolean.class, count, blocker);
			if (Boolean.TRUE.equals(blocked)) {
				return;
			}
			Thread.sleep(25);
		} while (System.nanoTime() < deadline);
		throw new AssertionError("Новые HTTP-отчеты не дождались общей группы PostgreSQL.");
	}

	private List<Map<String, Object>> groups() {
		return jdbc.queryForList("SELECT * FROM monitoring.violation_groups WHERE integration_id = ? ORDER BY group_id",
				UUID.fromString(integration));
	}

	private void assertGroupsMatchOccurrences() {
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM monitoring.violation_groups g
				LEFT JOIN LATERAL (
				  SELECT count(*) AS occurrences, min(r.received_at) AS first_seen, max(r.received_at) AS last_seen
				  FROM monitoring.report_violations v JOIN monitoring.reports r USING (report_row_id)
				  WHERE v.group_id = g.group_id
				) linked ON TRUE
				WHERE g.integration_id = ? AND (g.occurrence_count <> linked.occurrences
				  OR g.first_seen_at IS DISTINCT FROM linked.first_seen
				  OR g.last_seen_at IS DISTINCT FROM linked.last_seen)
				""", Integer.class, UUID.fromString(integration))).isZero();
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM monitoring.report_violations v
				JOIN monitoring.reports r USING (report_row_id)
				LEFT JOIN monitoring.violation_groups g USING (group_id)
				WHERE r.integration_id = ? AND (g.group_id IS NULL
				  OR (g.integration_id, g.contract_id, g.contract_version, g.field_name, g.violation_kind, g.actual_type)
				  IS DISTINCT FROM (r.integration_id, r.contract_id, r.contract_version, v.field_name, v.violation_kind, v.actual_type))
				""", Integer.class, UUID.fromString(integration))).isZero();
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
			statement.setObject(2, UUID.fromString(integration));
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
		String timestamp = Instant.parse("2026-10-20T00:00:00Z").plusSeconds(REPORT_SEQUENCE.incrementAndGet()).toString();
		return request(id, status, violations, timestamp);
	}

	private String request(String id, String status, String violations, String timestamp) {
		return """
				{"integrationId":"%s","contractId":"%s","report":{
				"reportId":"%s","contractVersion":3,"status":"%s","timestamp":"%s","violations":[%s]}}
				""".formatted(integration, CONTRACT, id, status, timestamp, violations);
	}

	private HttpResponse<String> post(String body, String contentType) throws Exception {
		return http.send(HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(15)).header("Content-Type", contentType)
				.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
	}

	private JsonNode assertProblem(HttpResponse<String> response, int status, String code) {
		assertThat(response.statusCode()).isEqualTo(status);
		assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json");
		JsonNode body = json.readTree(response.body());
		assertThat(body.propertyNames()).containsExactlyInAnyOrder("type", "title", "status", "detail", "instance", "code", "errors");
		assertThat(body.path("title").asString()).isEqualTo(org.springframework.http.HttpStatus.valueOf(status).getReasonPhrase());
		assertThat(body.path("detail").isString()).isTrue();
		assertThat(body.path("detail").asString()).isNotBlank();
		assertThat(body.path("type").asString()).isEqualTo("about:blank");
		assertThat(body.path("status").asInt()).isEqualTo(status);
		assertThat(body.path("code").asString()).isEqualTo(code);
		assertThat(body.path("instance").asString()).isEqualTo(response.request().uri().getRawPath());
		assertThat(body.path("errors").isObject()).isTrue();
		assertThat(response.body()).doesNotContain("driver-detail", "org.postgresql", "SQLException", "jdbc:", "timestamp-invalid-sample");
		assertThat(body.has("receivedAt")).isFalse();
		return body;
	}

	private Map<String, Object> report(String id) {
		return report(integration, id);
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
		return violations(integration, id);
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
