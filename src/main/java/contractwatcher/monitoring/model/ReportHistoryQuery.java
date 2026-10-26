package contractwatcher.monitoring.model;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

public record ReportHistoryQuery(UUID integrationId, Instant from, Instant to, String outcome,
		List<UUID> contractIds, int offset, int limit) {
	public ReportHistoryQuery {
		contractIds = List.copyOf(new LinkedHashSet<>(contractIds));
	}
}
