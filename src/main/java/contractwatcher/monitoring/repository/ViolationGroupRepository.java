package contractwatcher.monitoring.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import contractwatcher.monitoring.model.ReportToStore;
import contractwatcher.monitoring.model.ReportToStore.Violation;
import contractwatcher.monitoring.model.ViolationGroupsQuery;
import contractwatcher.monitoring.model.ViolationGroupsResult;

@Repository
public class ViolationGroupRepository {

	private final JdbcTemplate jdbc;

	ViolationGroupRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public ViolationGroupsResult findGroups(ViolationGroupsQuery query) {
		// COUNT и страница видят один снимок, включая страницу за пределами списка.
		return jdbc.query("""
				SELECT totals.total,
				       page.group_id, page.contract_id, page.contract_version,
				       page.field_name, page.violation_kind, page.expected_type, page.actual_type,
				       page.occurrence_count, page.first_seen_at, page.last_seen_at
				FROM (
				    SELECT count(*) AS total FROM monitoring.violation_groups WHERE integration_id = ?
				) totals
				LEFT JOIN (
				    SELECT group_id, contract_id, contract_version, field_name, violation_kind,
				           expected_type, actual_type, occurrence_count, first_seen_at, last_seen_at
				    FROM monitoring.violation_groups WHERE integration_id = ?
				    ORDER BY last_seen_at DESC, group_id DESC LIMIT ? OFFSET ?
				) page ON TRUE
				ORDER BY page.last_seen_at DESC, page.group_id DESC
				""", ViolationGroupRepository::groupsResult, query.integrationId(), query.integrationId(),
				query.limit(), query.offset());
	}

	private static ViolationGroupsResult groupsResult(ResultSet rows) throws SQLException {
		if (!rows.next()) {
			throw new IllegalStateException("Отсутствует итог списка групп.");
		}
		long total = rows.getLong("total");
		var items = new ArrayList<ViolationGroupsResult.Item>();
		do {
			UUID groupId = rows.getObject("group_id", UUID.class);
			if (groupId != null) {
				items.add(new ViolationGroupsResult.Item(groupId, rows.getObject("contract_id", UUID.class),
						rows.getInt("contract_version"), rows.getString("field_name"), rows.getString("violation_kind"),
						rows.getString("expected_type"), rows.getString("actual_type"), rows.getLong("occurrence_count"),
						rows.getObject("first_seen_at", OffsetDateTime.class).toInstant(),
						rows.getObject("last_seen_at", OffsetDateTime.class).toInstant()));
			}
		} while (rows.next());
		return new ViolationGroupsResult(List.copyOf(items), total);
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
