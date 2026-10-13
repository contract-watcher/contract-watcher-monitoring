package contractwatcher.monitoring.model;

import java.time.Instant;

public record StoredReport(long rowId, Instant receivedAt) {
}
