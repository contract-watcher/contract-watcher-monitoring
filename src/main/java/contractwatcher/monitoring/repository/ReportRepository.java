package contractwatcher.monitoring.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import contractwatcher.monitoring.model.ReportToStore;
import contractwatcher.monitoring.model.ReportHistoryEntry;
import contractwatcher.monitoring.model.ReportHistoryQuery;
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

	public long countHistory(ReportHistoryQuery query) {
		var filter = historyFilter(query);
		return jdbc.queryForObject("SELECT count(*) FROM monitoring.reports r WHERE " + filter.sql(),
				Long.class, filter.values().toArray());
	}

	public List<ReportHistoryEntry> findHistory(ReportHistoryQuery query) {
		var filter = historyFilter(query);
		var values = new ArrayList<>(filter.values());
		values.add(query.limit());
		values.add(query.offset());
		return jdbc.query("""
				SELECT page.external_report_id, page.contract_id, page.contract_version, page.outcome, page.received_at,
				       (SELECT count(*) FROM monitoring.report_violations v
				        WHERE v.report_row_id = page.report_row_id) AS violation_count
				FROM (
				    SELECT r.report_row_id, r.external_report_id, r.contract_id, r.contract_version, r.outcome, r.received_at
				    FROM monitoring.reports r WHERE %s
				    ORDER BY r.received_at DESC, r.report_row_id DESC LIMIT ? OFFSET ?
				) page
				ORDER BY page.received_at DESC, page.report_row_id DESC
				""".formatted(filter.sql()), ReportRepository::historyEntry, values.toArray());
	}

	private static HistoryFilter historyFilter(ReportHistoryQuery query) {
		StringBuilder sql = new StringBuilder("r.integration_id = ?");
		List<Object> values = new ArrayList<>();
		values.add(query.integrationId());
		if (query.from() != null) {
			sql.append(" AND r.received_at >= ?");
			values.add(query.from().atOffset(ZoneOffset.UTC));
		}
		if (query.to() != null) {
			sql.append(" AND r.received_at < ?");
			values.add(query.to().atOffset(ZoneOffset.UTC));
		}
		if (query.outcome() != null) {
			sql.append(" AND r.outcome = ?");
			values.add(query.outcome());
		}
		return new HistoryFilter(sql.toString(), values);
	}

	private record HistoryFilter(String sql, List<Object> values) {
	}

	private static ReportHistoryEntry historyEntry(ResultSet row, int index) throws SQLException {
		return new ReportHistoryEntry(row.getObject("external_report_id", UUID.class),
				row.getObject("contract_id", UUID.class), row.getInt("contract_version"), row.getString("outcome"),
				row.getObject("received_at", OffsetDateTime.class).toInstant(), row.getLong("violation_count"));
	}

	private static StoredReport storedReport(ResultSet row, int index) throws SQLException {
		return new StoredReport(row.getLong("report_row_id"),
				row.getObject("received_at", OffsetDateTime.class).toInstant());
	}
}
