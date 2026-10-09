package contractwatcher.monitoring;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Savepoint;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

@EnabledIfEnvironmentVariable(named = "MONITORING_TEST_DATABASE_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReportStorageTests {

	private static final UUID INTEGRATION = UUID.fromString("22222222-2222-4222-8222-222222222222");
	private static final UUID CONTRACT = UUID.fromString("33333333-3333-4333-8333-333333333333");
	private static final OffsetDateTime RECEIVED_AT = OffsetDateTime.parse("2026-10-09T09:00:00Z");
	private final List<String> ownedDatabases = new ArrayList<>();
	private String adminUrl;
	private String username;
	private String password;
	private String storageUrl;
	private Connection connection;
	private JdbcTemplate jdbc;

	@BeforeAll
	void migrateEmptyDatabase() throws SQLException {
		adminUrl = System.getenv("MONITORING_TEST_DATABASE_URL");
		username = System.getenv("MONITORING_TEST_DATABASE_USER");
		password = System.getenv("MONITORING_TEST_DATABASE_PASSWORD");
		try (Connection admin = open(adminUrl)) {
			assertThat(jdbc(admin).queryForObject("SELECT current_database()", String.class))
					.as("Нужна отдельная тестовая БД с именем monitoring_test_...")
					.matches("monitoring_test_[a-z0-9_]+");
			assertThat(jdbc(admin).queryForObject("SELECT version()", String.class)).startsWith("PostgreSQL");
		}
		storageUrl = createDatabase();
		try (Connection empty = open(storageUrl)) {
			assertThat(jdbc(empty).queryForObject("""
					SELECT count(*) FROM information_schema.tables
					WHERE table_schema NOT IN ('pg_catalog', 'information_schema')
					""", Integer.class)).isZero();
		}
		assertThat(flyway(storageUrl).migrate().migrationsExecuted).isEqualTo(3);
	}

	@BeforeEach
	void beginFixture() throws SQLException {
		connection = open(storageUrl);
		connection.setAutoCommit(false);
		jdbc = jdbc(connection);
	}

	@AfterEach
	void rollbackFixture() throws SQLException {
		if (connection != null) {
			try (Connection fixture = connection) {
				fixture.rollback();
			} finally {
				connection = null;
			}
		}
	}

	@AfterAll
	void dropOwnedDatabases() throws SQLException {
		if (!ownedDatabases.isEmpty()) {
			try (Connection admin = open(adminUrl); var statement = admin.createStatement()) {
				for (String database : ownedDatabases) {
					assertThat(database).matches("monitoring_test_storage_[a-f0-9]{32}");
					statement.execute("DROP DATABASE " + database);
				}
			}
		}
	}

	@Test
	void createsCompleteSchemaWithoutCore() {
		Flyway migrations = flyway(storageUrl);
		migrations.validate();
		assertThat(migrations.info().pending()).isEmpty();
		assertThat(jdbc.queryForList("""
				SELECT version FROM monitoring.flyway_schema_history
				WHERE version IS NOT NULL AND success ORDER BY installed_rank
				""", String.class)).containsExactly("1", "2", "3");
		assertThat(jdbc.queryForList("""
				SELECT table_name FROM information_schema.tables
				WHERE table_schema = 'monitoring' ORDER BY table_name
				""", String.class)).containsExactly(
				"flyway_schema_history", "report_violations", "reports", "violation_groups");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_namespace WHERE nspname = 'core'", Integer.class))
				.isZero();
		assertThat(jdbc.queryForList("""
				SELECT target_namespace.nspname FROM pg_constraint constraint_info
				JOIN pg_class source_table ON source_table.oid = constraint_info.conrelid
				JOIN pg_namespace source_namespace ON source_namespace.oid = source_table.relnamespace
				JOIN pg_class target_table ON target_table.oid = constraint_info.confrelid
				JOIN pg_namespace target_namespace ON target_namespace.oid = target_table.relnamespace
				WHERE source_namespace.nspname = 'monitoring' AND constraint_info.contype = 'f'
				""", String.class)).containsExactlyInAnyOrder("monitoring", "monitoring");
	}

	@Test
	void upgradesV1AndRepeatedMigrationPreservesHistoryAndData() throws SQLException {
		String upgradeUrl = createDatabase();
		assertThat(Flyway.configure().configuration(flyway(upgradeUrl).getConfiguration())
				.target("1").load().migrate().migrationsExecuted).isEqualTo(1);
		try (Connection upgrade = open(upgradeUrl)) {
			JdbcTemplate upgradeJdbc = jdbc(upgrade);
			var v1History = upgradeJdbc.queryForList("""
					SELECT * FROM monitoring.flyway_schema_history WHERE version = '1'
					""");
			Flyway migrations = flyway(upgradeUrl);
			assertThat(migrations.migrate().migrationsExecuted).isEqualTo(2);
			assertThat(upgradeJdbc.queryForList("""
					SELECT * FROM monitoring.flyway_schema_history WHERE version = '1'
					""")).isEqualTo(v1History);
			long report = upgradeJdbc.queryForObject("""
					INSERT INTO monitoring.reports
					(external_report_id, integration_id, contract_id, contract_version, outcome)
					VALUES (?, ?, ?, 3, 'FAILED') RETURNING report_row_id
					""", Long.class, UUID.randomUUID(), INTEGRATION, CONTRACT);
			UUID group = UUID.randomUUID();
			upgradeJdbc.update("""
					INSERT INTO monitoring.violation_groups
					(group_id, integration_id, contract_id, contract_version, field_name, violation_kind,
					 occurrence_count, first_seen_at, last_seen_at)
					VALUES (?, ?, ?, 3, 'customer_id', 'REQUIRED_FIELD_MISSING', 2, ?, ?)
					""", group, INTEGRATION, CONTRACT, RECEIVED_AT, RECEIVED_AT);
			for (int ordinal = 0; ordinal < 2; ordinal++) {
				upgradeJdbc.update("""
						INSERT INTO monitoring.report_violations
						(report_row_id, ordinal, field_name, violation_kind, group_id)
						VALUES (?, ?, 'customer_id', 'REQUIRED_FIELD_MISSING', ?)
						""", report, ordinal, group);
			}
			var history = upgradeJdbc.queryForList("SELECT * FROM monitoring.flyway_schema_history ORDER BY installed_rank");
			var reports = upgradeJdbc.queryForList("SELECT * FROM monitoring.reports");
			var violations = upgradeJdbc.queryForList("SELECT * FROM monitoring.report_violations ORDER BY ordinal");
			var groups = upgradeJdbc.queryForList("SELECT * FROM monitoring.violation_groups");
			assertThat(migrations.migrate().migrationsExecuted).isZero();
			migrations.validate();
			assertThat(migrations.info().pending()).isEmpty();
			assertThat(upgradeJdbc.queryForList("SELECT * FROM monitoring.flyway_schema_history ORDER BY installed_rank"))
					.isEqualTo(history);
			assertThat(upgradeJdbc.queryForList("SELECT * FROM monitoring.reports")).isEqualTo(reports);
			assertThat(upgradeJdbc.queryForList("SELECT * FROM monitoring.report_violations ORDER BY ordinal"))
					.isEqualTo(violations);
			assertThat(upgradeJdbc.queryForList("SELECT * FROM monitoring.violation_groups")).isEqualTo(groups);
		}
	}

	@Test
	void storesPassedWithDefaultTimeAndNoViolations() {
		long report = jdbc.queryForObject("""
				INSERT INTO monitoring.reports
				(external_report_id, integration_id, contract_id, contract_version, outcome)
				VALUES (?, ?, ?, 3, 'PASSED') RETURNING report_row_id
				""", Long.class, UUID.randomUUID(), INTEGRATION, CONTRACT);
		var saved = jdbc.queryForMap("SELECT * FROM monitoring.reports WHERE report_row_id = ?", report);
		assertThat(saved).containsEntry("integration_id", INTEGRATION).containsEntry("contract_id", CONTRACT)
				.containsEntry("contract_version", 3).containsEntry("outcome", "PASSED");
		assertThat(saved.get("received_at")).isNotNull();
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM monitoring.report_violations WHERE report_row_id = ?
				""", Integer.class, report)).isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM monitoring.violation_groups", Integer.class)).isZero();
	}

	@Test
	void storesMultipleViolationsIncludingIdenticalElements() throws SQLException {
		long report = report(UUID.randomUUID(), INTEGRATION, CONTRACT, 3, "FAILED", RECEIVED_AT);
		UUID missingGroup = group(INTEGRATION, CONTRACT, 3, "customer_id", "REQUIRED_FIELD_MISSING", "string", null);
		UUID typeGroup = group(INTEGRATION, CONTRACT, 3, "amount", "TYPE_MISMATCH", "number", "string");
		violation(report, 0, "customer_id", "REQUIRED_FIELD_MISSING", "string", null, missingGroup);
		violation(report, 1, "amount", "TYPE_MISMATCH", "number", "string", typeGroup);
		violation(report, 2, "amount", "TYPE_MISMATCH", "number", "string", typeGroup);
		var rows = jdbc.queryForList("""
				SELECT * FROM monitoring.report_violations WHERE report_row_id = ? ORDER BY ordinal
				""", report);
		assertThat(rows).hasSize(3);
		assertThat(rows.get(0)).containsEntry("ordinal", 0).containsEntry("field_name", "customer_id")
				.containsEntry("expected_type", "string").containsEntry("actual_type", null)
				.containsEntry("group_id", missingGroup);
		assertThat(rows.get(1)).containsEntry("ordinal", 1).containsEntry("field_name", "amount")
				.containsEntry("expected_type", "number").containsEntry("actual_type", "string")
				.containsEntry("group_id", typeGroup);
		assertThat(rows.get(2)).containsEntry("ordinal", 2).containsEntry("group_id", typeGroup);
		expectSqlState("23505", """
				INSERT INTO monitoring.report_violations (report_row_id, ordinal, field_name, violation_kind)
				VALUES (?, 1, 'different_field', 'NULL_NOT_ALLOWED')
				""", report);
	}

	@Test
	void preservesNullableMetadataAndDistinguishesMissingFromNull() throws SQLException {
		long report = report(UUID.randomUUID(), INTEGRATION, CONTRACT, 3, "FAILED", RECEIVED_AT);
		for (String kind : List.of("REQUIRED_FIELD_MISSING", "NULL_NOT_ALLOWED")) {
			group(INTEGRATION, CONTRACT, 3, "customer_id", kind, null, null);
		}
		violation(report, 0, "customer_id", "REQUIRED_FIELD_MISSING", null, null, null);
		violation(report, 1, "customer_id", "NULL_NOT_ALLOWED", null, null, null);
		var rows = jdbc.queryForList("SELECT * FROM monitoring.report_violations ORDER BY ordinal");
		assertThat(rows).extracting(row -> row.get("violation_kind"))
				.containsExactly("REQUIRED_FIELD_MISSING", "NULL_NOT_ALLOWED");
		for (var row : rows) {
			assertThat(row).containsEntry("expected_type", null).containsEntry("actual_type", null)
					.containsEntry("group_id", null);
		}
		for (var row : jdbc.queryForList("SELECT * FROM monitoring.violation_groups")) {
			assertThat(row).containsEntry("expected_type", null).containsEntry("actual_type", null);
		}
	}

	@Test
	void storesSameInstantAcrossTimezonesAndCounterAboveIntegerLimit() throws SQLException {
		long first = report(UUID.randomUUID(), INTEGRATION, CONTRACT, 3, "PASSED", RECEIVED_AT);
		long second = report(UUID.randomUUID(), INTEGRATION, CONTRACT, 3, "PASSED",
				OffsetDateTime.parse("2026-10-09T12:00:00+03:00"));
		assertThat(jdbc.queryForObject("""
				SELECT a.received_at = b.received_at FROM monitoring.reports a, monitoring.reports b
				WHERE a.report_row_id = ? AND b.report_row_id = ?
				""", Boolean.class, first, second)).isTrue();
		UUID group = group(INTEGRATION, CONTRACT, 3, "value", "NULL_NOT_ALLOWED", null, null);
		long occurrences = (long) Integer.MAX_VALUE + 1;
		execute("UPDATE monitoring.violation_groups SET occurrence_count = ? WHERE group_id = ?", occurrences, group);
		assertThat(jdbc.queryForObject("SELECT occurrence_count FROM monitoring.violation_groups WHERE group_id = ?",
				Long.class, group)).isEqualTo(occurrences);
		try (PreparedStatement statement = connection.prepareStatement(
				"SELECT received_at FROM monitoring.reports WHERE report_row_id = ?")) {
			statement.setLong(1, second);
			try (var result = statement.executeQuery()) {
				assertThat(result.next()).isTrue();
				assertThat(result.getObject(1, OffsetDateTime.class).toInstant()).isEqualTo(RECEIVED_AT.toInstant());
			}
		}
	}

	@Test
	void scopesReportIdToIntegrationAcrossAllItsContracts() throws SQLException {
		UUID external = UUID.randomUUID();
		report(external, INTEGRATION, CONTRACT, 3, "PASSED", RECEIVED_AT);
		for (UUID contract : List.of(CONTRACT, UUID.randomUUID())) {
			expectSqlState("23505", """
					INSERT INTO monitoring.reports
					(external_report_id, integration_id, contract_id, contract_version, outcome)
					VALUES (?, ?, ?, 3, 'FAILED')
					""", external, INTEGRATION, contract);
		}
		report(external, UUID.randomUUID(), CONTRACT, 3, "PASSED", RECEIVED_AT);
		report(UUID.randomUUID(), INTEGRATION, UUID.randomUUID(), 3, "PASSED", RECEIVED_AT);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM monitoring.reports", Integer.class)).isEqualTo(3);
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = "string")
	void enforcesGroupIdentityIncludingNullAndSeparatesContracts(String actual) throws SQLException {
		group(INTEGRATION, CONTRACT, 3, "amount", "TYPE_MISMATCH", "number", actual);
		expectSqlState("23505", """
				INSERT INTO monitoring.violation_groups
				(group_id, integration_id, contract_id, contract_version, field_name, violation_kind,
				 expected_type, actual_type, occurrence_count, first_seen_at, last_seen_at)
				VALUES (?, ?, ?, 3, 'amount', 'TYPE_MISMATCH', 'boolean', ?, 1, ?, ?)
				""", UUID.randomUUID(), INTEGRATION, CONTRACT, actual, RECEIVED_AT, RECEIVED_AT);
		group(INTEGRATION, CONTRACT, 3, "amount", "TYPE_MISMATCH", "number", "number");
		group(INTEGRATION, CONTRACT, 3, "amount", "TYPE_MISMATCH", "number", actual == null ? "string" : null);
		group(INTEGRATION, UUID.randomUUID(), 3, "amount", "TYPE_MISMATCH", "number", actual);
		group(INTEGRATION, CONTRACT, 4, "amount", "TYPE_MISMATCH", "number", actual);
		group(UUID.randomUUID(), CONTRACT, 3, "amount", "TYPE_MISMATCH", "number", actual);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM monitoring.violation_groups", Integer.class)).isEqualTo(6);
	}

	@Test
	void rejectsInvalidStorageValues() throws SQLException {
		long report = report(UUID.randomUUID(), INTEGRATION, CONTRACT, 3, "FAILED", RECEIVED_AT);
		UUID group = group(INTEGRATION, CONTRACT, 3, "amount", "TYPE_MISMATCH", "number", "string");
		violation(report, 0, "amount", "TYPE_MISMATCH", "number", "string", group);
		expectSqlState("23502", "UPDATE monitoring.reports SET contract_id = NULL WHERE report_row_id = ?", report);
		expectSqlState("23514", "UPDATE monitoring.reports SET contract_version = 0 WHERE report_row_id = ?", report);
		expectSqlState("23514", "UPDATE monitoring.report_violations SET violation_kind = 'UNKNOWN'");
		expectSqlState("23503", "UPDATE monitoring.report_violations SET report_row_id = -1");
		expectSqlState("23503", "UPDATE monitoring.report_violations SET group_id = ?", UUID.randomUUID());
		expectSqlState("23514", "UPDATE monitoring.violation_groups SET occurrence_count = 0 WHERE group_id = ?", group);
		expectSqlState("23514", """
				UPDATE monitoring.violation_groups SET first_seen_at = last_seen_at + interval '1 second'
				WHERE group_id = ?
				""", group);
	}

	private Connection open(String url) throws SQLException {
		return DriverManager.getConnection(url, username, password);
	}

	private JdbcTemplate jdbc(Connection database) {
		return new JdbcTemplate(new SingleConnectionDataSource(database, true));
	}

	private String createDatabase() throws SQLException {
		String database = "monitoring_test_storage_" + UUID.randomUUID().toString().replace("-", "");
		try (Connection admin = open(adminUrl); var statement = admin.createStatement()) {
			statement.execute("CREATE DATABASE " + database);
			ownedDatabases.add(database);
		}
		int queryStart = adminUrl.indexOf('?');
		int databaseStart = adminUrl.lastIndexOf('/', queryStart < 0 ? adminUrl.length() : queryStart) + 1;
		assertThat(databaseStart).isPositive();
		return adminUrl.substring(0, databaseStart) + database
				+ (queryStart < 0 ? "" : adminUrl.substring(queryStart));
	}

	private Flyway flyway(String url) {
		return Flyway.configure().dataSource(url, username, password).defaultSchema("monitoring")
				.schemas("monitoring").createSchemas(true).cleanDisabled(true).loggers("slf4j").load();
	}

	private long report(UUID external, UUID integration, UUID contract, int version, String outcome, OffsetDateTime at) {
		return jdbc.queryForObject("""
				INSERT INTO monitoring.reports
				(external_report_id, integration_id, contract_id, contract_version, outcome, received_at)
				VALUES (?, ?, ?, ?, ?, ?) RETURNING report_row_id
				""", Long.class, external, integration, contract, version, outcome, at);
	}

	private UUID group(UUID integration, UUID contract, int version, String field, String kind,
			String expected, String actual) throws SQLException {
		UUID id = UUID.randomUUID();
		execute("""
				INSERT INTO monitoring.violation_groups
				(group_id, integration_id, contract_id, contract_version, field_name, violation_kind,
				 expected_type, actual_type, occurrence_count, first_seen_at, last_seen_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)
				""", id, integration, contract, version, field, kind, expected, actual, RECEIVED_AT, RECEIVED_AT);
		return id;
	}

	private void violation(long report, int ordinal, String field, String kind, String expected, String actual,
			UUID group) throws SQLException {
		execute("""
				INSERT INTO monitoring.report_violations
				(report_row_id, ordinal, field_name, violation_kind, expected_type, actual_type, group_id)
				VALUES (?, ?, ?, ?, ?, ?, ?)
				""", report, ordinal, field, kind, expected, actual, group);
	}

	private void execute(String sql, Object... parameters) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			for (int index = 0; index < parameters.length; index++) {
				statement.setObject(index + 1, parameters[index]);
			}
			statement.executeUpdate();
		}
	}

	private void expectSqlState(String state, String sql, Object... parameters) throws SQLException {
		Savepoint beforeInvalidStatement = connection.setSavepoint();
		try {
			assertThatExceptionOfType(SQLException.class).isThrownBy(() -> execute(sql, parameters))
					.satisfies(error -> assertThat(error.getSQLState()).isEqualTo(state));
		} finally {
			connection.rollback(beforeInvalidStatement);
			connection.releaseSavepoint(beforeInvalidStatement);
		}
	}
}
