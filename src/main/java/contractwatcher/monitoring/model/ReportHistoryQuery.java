package contractwatcher.monitoring.model;

import java.time.Instant;
import java.util.UUID;

public record ReportHistoryQuery(UUID integrationId, Instant from, Instant to, String outcome,
		int offset, int limit) {
}
