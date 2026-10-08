package contractwatcher.monitoring;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "MONITORING_TEST_DATABASE_URL", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"spring.datasource.url=${MONITORING_TEST_DATABASE_URL}",
		"spring.datasource.username=${MONITORING_TEST_DATABASE_USER}",
		"spring.datasource.password=${MONITORING_TEST_DATABASE_PASSWORD}"
})
class ApplicationTests {

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private Flyway flyway;

	@Test
	void startsWithPostgresqlAndAppliesMigrations() {
		assertThat(jdbc.queryForObject("SELECT current_schema()", String.class)).isEqualTo("monitoring");
		assertThat(jdbc.queryForObject("SELECT version()", String.class)).startsWith("PostgreSQL");
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM monitoring.flyway_schema_history
				WHERE version = '1' AND success
				""", Integer.class)).isEqualTo(1);
		assertThat(flyway.info().pending()).isEmpty();
		flyway.validate();
	}

	@Test
	void repeatedMigrationDoesNotChangeHistory() {
		Integer appliedBefore = jdbc.queryForObject(
				"SELECT count(*) FROM monitoring.flyway_schema_history", Integer.class);

		assertThat(flyway.migrate().migrationsExecuted).isZero();

		assertThat(jdbc.queryForObject("SELECT count(*) FROM monitoring.flyway_schema_history", Integer.class))
				.isEqualTo(appliedBefore);
	}

}
