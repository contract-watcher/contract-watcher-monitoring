package contractwatcher.monitoring.reports;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class ReportRepository {

	private final JdbcTemplate jdbc;

	ReportRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	ReportAccepted save(ReportRequest request) {
		ValidationReport report = request.report();
		UUID integrationId = UUID.fromString(request.integrationId());
		UUID reportId = UUID.fromString(report.reportId());
		List<StoredReport> inserted = jdbc.query("""
				INSERT INTO monitoring.reports
				(external_report_id, integration_id, contract_id, contract_version, outcome)
				VALUES (?, ?, ?, ?, ?)
				ON CONFLICT ON CONSTRAINT reports_integration_report_unique DO NOTHING
				RETURNING report_row_id, received_at
				""", (row, index) -> new StoredReport(row.getLong("report_row_id"),
						row.getObject("received_at", OffsetDateTime.class).toInstant()),
				reportId, integrationId, UUID.fromString(request.contractId()),
				report.contractVersion(), report.status().databaseValue());
		if (inserted.isEmpty()) {
			return previousConfirmation(integrationId, reportId);
		}
		if (inserted.size() != 1) {
			throw new IllegalStateException("Неожиданное число сохраненных отчетов.");
		}
		StoredReport stored = inserted.getFirst();
		saveViolations(stored.rowId(), report.violations());
		return new ReportAccepted(integrationId, reportId, stored.receivedAt(), false);
	}

	private ReportAccepted previousConfirmation(UUID integrationId, UUID reportId) {
		// Отдельный SELECT видит commit конкурента, которого мог не видеть INSERT.
		return jdbc.queryForObject("""
				SELECT integration_id, external_report_id, received_at
				FROM monitoring.reports WHERE integration_id = ? AND external_report_id = ?
				""", (row, index) -> new ReportAccepted(row.getObject("integration_id", UUID.class),
						row.getObject("external_report_id", UUID.class),
						row.getObject("received_at", OffsetDateTime.class).toInstant(), true),
				integrationId, reportId);
	}

	private void saveViolations(long rowId, List<ReportViolation> violations) {
		for (int ordinal = 0; ordinal < violations.size(); ordinal++) {
			ReportViolation violation = violations.get(ordinal);
			jdbc.update("""
					INSERT INTO monitoring.report_violations
					(report_row_id, ordinal, field_name, violation_kind, expected_type, actual_type, group_id)
					VALUES (?, ?, ?, ?, ?, ?, NULL)
					""", rowId, ordinal, violation.fieldName(), violation.type().databaseValue(),
					violation.expectedType().databaseValue(),
					violation.actualType() == null ? null : violation.actualType().databaseValue());
		}
	}

	private record StoredReport(long rowId, Instant receivedAt) {
	}
}
