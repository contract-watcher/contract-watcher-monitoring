package contractwatcher.monitoring.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import contractwatcher.monitoring.model.ReportToStore;
import contractwatcher.monitoring.model.StoredReport;

@Repository
public class ReportRepository {

	private final JdbcTemplate jdbc;

	ReportRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public Optional<StoredReport> insertIfNew(ReportToStore report) {
		var inserted = jdbc.query("""
				INSERT INTO monitoring.reports
				(external_report_id, integration_id, contract_id, contract_version, outcome)
				VALUES (?, ?, ?, ?, ?)
				ON CONFLICT ON CONSTRAINT reports_integration_report_unique DO NOTHING
				RETURNING report_row_id, received_at
				""", ReportRepository::storedReport, report.reportId(), report.integrationId(), report.contractId(),
				report.contractVersion(), report.outcome());
		if (inserted.size() > 1) {
			throw new IllegalStateException("Неожиданное число сохраненных отчетов.");
		}
		return inserted.stream().findFirst();
	}

	public StoredReport find(UUID integrationId, UUID reportId) {
		// Отдельный SELECT видит commit конкурента, которого мог не видеть INSERT.
		return jdbc.queryForObject("""
				SELECT report_row_id, received_at FROM monitoring.reports
				WHERE integration_id = ? AND external_report_id = ?
				""", ReportRepository::storedReport, integrationId, reportId);
	}

	private static StoredReport storedReport(ResultSet row, int index) throws SQLException {
		return new StoredReport(row.getLong("report_row_id"),
				row.getObject("received_at", OffsetDateTime.class).toInstant());
	}
}
