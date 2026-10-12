package contractwatcher.monitoring.reports;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class ReportRepository {

	private final JdbcTemplate jdbc;

	ReportRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	ReportAccepted insert(ReportRequest request) {
		ValidationReport report = request.report();
		UUID integrationId = UUID.fromString(request.integrationId());
		UUID reportId = UUID.fromString(report.reportId());
		StoredReport stored;
		try {
			stored = jdbc.queryForObject("""
					INSERT INTO monitoring.reports
					(external_report_id, integration_id, contract_id, contract_version, outcome)
					VALUES (?, ?, ?, ?, ?) RETURNING report_row_id, received_at
					""", (row, index) -> new StoredReport(row.getLong("report_row_id"),
							row.getObject("received_at", OffsetDateTime.class).toInstant()),
					reportId, integrationId, UUID.fromString(request.contractId()),
					report.contractVersion(), report.status().databaseValue());
		} catch (DuplicateKeyException exception) {
			// Только конфликт вставки отчета означает повтор reportId.
			throw new ReportIdConflictException();
		}
		for (int ordinal = 0; ordinal < report.violations().size(); ordinal++) {
			ReportViolation violation = report.violations().get(ordinal);
			jdbc.update("""
					INSERT INTO monitoring.report_violations
					(report_row_id, ordinal, field_name, violation_kind, expected_type, actual_type, group_id)
					VALUES (?, ?, ?, ?, ?, ?, NULL)
					""", stored.rowId(), ordinal, violation.fieldName(), violation.type().databaseValue(),
					violation.expectedType().databaseValue(),
					violation.actualType() == null ? null : violation.actualType().databaseValue());
		}
		return new ReportAccepted(integrationId, reportId, stored.receivedAt(), false);
	}

	private record StoredReport(long rowId, Instant receivedAt) {
	}
}
