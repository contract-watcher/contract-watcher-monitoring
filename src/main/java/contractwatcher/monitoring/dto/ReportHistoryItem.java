package contractwatcher.monitoring.dto;

import java.time.Instant;
import java.util.UUID;

public record ReportHistoryItem(UUID reportId, UUID contractId, int contractVersion, ValidationReport.Status status,
		Instant receivedAt, long violationCount) {
}
