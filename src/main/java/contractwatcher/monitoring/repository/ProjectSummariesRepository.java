package contractwatcher.monitoring.repository;

import java.sql.Array;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.UUID;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import contractwatcher.monitoring.dto.IntegrationSummariesPage;
import contractwatcher.monitoring.dto.IntegrationSummary;
import contractwatcher.monitoring.model.ProjectSummariesQuery;

@Repository
public class ProjectSummariesRepository {

	private final JdbcTemplate jdbc;

	public ProjectSummariesRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public IntegrationSummariesPage query(ProjectSummariesQuery query) {
		return jdbc.execute((ConnectionCallback<IntegrationSummariesPage>) connection -> {
			Array ids = connection.createArrayOf("uuid", query.integrationIds().toArray(UUID[]::new));
			try (var statement = connection.prepareStatement(sql(query))) {
				statement.setArray(1, ids);
				int index = 2;
				if (query.from() != null) {
					statement.setObject(index++, query.from().atOffset(ZoneOffset.UTC));
				}
				if (query.to() != null) {
					statement.setObject(index++, query.to().atOffset(ZoneOffset.UTC));
				}
				statement.setInt(index++, query.limit());
				statement.setInt(index, query.offset());
				try (var rows = statement.executeQuery()) {
					var items = new ArrayList<IntegrationSummary>();
					long total = 0;
					while (rows.next()) {
						total = rows.getLong("total");
						UUID id = rows.getObject("integration_id", UUID.class);
						if (id != null) {
							var last = rows.getObject("last_received_at", OffsetDateTime.class);
							items.add(new IntegrationSummary(id, rows.getLong("total_reports"), rows.getLong("passed_reports"),
									rows.getLong("failed_reports"), last == null ? null : last.toInstant()));
						}
					}
					return new IntegrationSummariesPage(java.util.List.copyOf(items), total, query.offset(), query.limit());
				}
			} finally {
				ids.free();
			}
		});
	}

	static String sql(ProjectSummariesQuery query) {
		String period = (query.from() == null ? "" : " AND r.received_at >= ?")
				+ (query.to() == null ? "" : " AND r.received_at < ?");
		return """
				WITH requested AS MATERIALIZED (
				    SELECT DISTINCT unnest(?::uuid[]) AS integration_id
				), aggregated AS (
				    SELECT r.integration_id, count(*) AS total_reports,
				           count(*) FILTER (WHERE r.outcome = 'PASSED') AS passed_reports,
				           count(*) FILTER (WHERE r.outcome = 'FAILED') AS failed_reports,
				           max(r.received_at) AS last_received_at
				    FROM monitoring.reports r JOIN requested q USING (integration_id)
				    WHERE TRUE %s GROUP BY r.integration_id
				), filtered AS MATERIALIZED (
				    SELECT q.integration_id, coalesce(a.total_reports, 0::bigint) AS total_reports,
				           coalesce(a.passed_reports, 0::bigint) AS passed_reports,
				           coalesce(a.failed_reports, 0::bigint) AS failed_reports, a.last_received_at
				    FROM requested q LEFT JOIN aggregated a USING (integration_id) %s
				), totals AS (SELECT count(*) AS total FROM filtered), page AS (
				    SELECT * FROM filtered ORDER BY failed_reports DESC, integration_id ASC LIMIT ? OFFSET ?
				)
				SELECT t.total, p.* FROM totals t LEFT JOIN page p ON TRUE
				ORDER BY p.failed_reports DESC, p.integration_id ASC
				""".formatted(period, query.onlyFailed() ? "WHERE a.failed_reports > 0" : "");
	}
}
