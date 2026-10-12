package contractwatcher.monitoring.reports;

import java.time.Instant;
import java.util.UUID;

public record ReportAccepted(UUID integrationId, UUID reportId, Instant receivedAt, boolean duplicate) {
}
