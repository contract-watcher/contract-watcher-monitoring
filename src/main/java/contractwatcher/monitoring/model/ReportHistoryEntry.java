package contractwatcher.monitoring.model;

import java.time.Instant;
import java.util.UUID;

public record ReportHistoryEntry(UUID reportId, UUID contractId, int contractVersion, String outcome,
		Instant receivedAt, long violationCount) {
}
