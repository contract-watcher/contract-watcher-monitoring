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
import org.junit.jupiter.api.BeforeEach;
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

	private String integration;
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
		assertThat(body.path("integrationId").asString()).isEqualTo(integration);
		assertThat(body.path("reportId").asString()).isEqualTo(id);
		assertThat(body.path("duplicate").asBoolean()).isFalse();
		assertThat(body.path("receivedAt").asString()).endsWith("Z");
		var stored = report(id);
		assertThat(stored).containsEntry("outcome", "PASSED").containsEntry("contract_version", 3)
				.containsEntry("contract_id", UUID.fromString(CONTRACT));
		Instant storedTime = receivedAt(integration, id);
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

	@Test
	void historyKeepsItemsAndTotalInOneSnapshotDuringConcurrentCommit() throws Exception {
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
				Future<HttpResponse<String>> pending = executor.submit(() -> getHistory(integration, ""));
				awaitBlockedHistory(blocker);
				insertHeldReport(held, committed);
				held.commit();
				assertHistoryIds(historyPage(pending.get(10, TimeUnit.SECONDS), 1, 0, 20), previous);
				JsonNode next = history("", 2, 0, 20);
				assertHistoryIds(next, committed, previous);
				assertThat(next.path("items").get(0).path("violationCount").asLong()).isEqualTo(1);
			} finally {
				held.rollback();
			}
		}
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
		assertThat(post(request(id, status, violations), "application/json").statusCode()).isEqualTo(201);
		setHistoryTime(id, time);
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
		return historyPage(getHistory(integration, query), total, offset, limit);
	}

	private JsonNode historyPage(HttpResponse<String> response, long total, int offset, int limit) {
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
		return """
				{"integrationId":"%s","contractId":"%s","report":{
				"reportId":"%s","contractVersion":3,"status":"%s","violations":[%s]}}
				""".formatted(integration, CONTRACT, id, status, violations);
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
		assertThat(body.path("instance").asString()).isEqualTo(response.request().uri().getRawPath());
		assertThat(body.path("errors").isObject()).isTrue();
		assertThat(response.body()).doesNotContain("driver-detail", "org.postgresql", "SQLException", "jdbc:");
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
