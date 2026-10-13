package contractwatcher.monitoring.repository;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import contractwatcher.monitoring.model.ReportToStore.Violation;

@Repository
public class ReportViolationRepository {

	private final JdbcTemplate jdbc;

	ReportViolationRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public void insert(long reportRowId, Violation violation, UUID groupId) {
		jdbc.update("""
				INSERT INTO monitoring.report_violations
				(report_row_id, ordinal, field_name, violation_kind, expected_type, actual_type, group_id)
				VALUES (?, ?, ?, ?, ?, ?, ?)
				""", reportRowId, violation.ordinal(), violation.fieldName(), violation.kind(),
				violation.expectedType(), violation.actualType(), groupId);
	}
}
