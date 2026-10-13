package contractwatcher.monitoring.repository;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import contractwatcher.monitoring.model.ReportToStore;
import contractwatcher.monitoring.model.ReportToStore.Violation;

@Repository
public class ViolationGroupRepository {

	private final JdbcTemplate jdbc;

	ViolationGroupRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public UUID recordOccurrence(ReportToStore report, Violation violation, Instant receivedAt) {
		String conflictTarget = violation.actualType() == null
				? "(integration_id, contract_id, contract_version, field_name, violation_kind) WHERE actual_type IS NULL"
				: "(integration_id, contract_id, contract_version, field_name, violation_kind, actual_type) WHERE actual_type IS NOT NULL";
		var timestamp = receivedAt.atOffset(ZoneOffset.UTC);
		return jdbc.queryForObject("""
				INSERT INTO monitoring.violation_groups AS groups
				(group_id, integration_id, contract_id, contract_version, field_name, violation_kind,
				expected_type, actual_type, occurrence_count, first_seen_at, last_seen_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)
				ON CONFLICT %s DO UPDATE SET
				occurrence_count = groups.occurrence_count + 1,
				first_seen_at = LEAST(groups.first_seen_at, EXCLUDED.first_seen_at),
				last_seen_at = GREATEST(groups.last_seen_at, EXCLUDED.last_seen_at)
				RETURNING group_id
				""".formatted(conflictTarget), (row, index) -> row.getObject("group_id", UUID.class),
				UUID.randomUUID(), report.integrationId(), report.contractId(), report.contractVersion(),
				violation.fieldName(), violation.kind(), violation.expectedType(), violation.actualType(), timestamp, timestamp);
	}
}
