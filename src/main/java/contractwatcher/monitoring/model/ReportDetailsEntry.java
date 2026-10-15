package contractwatcher.monitoring.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReportDetailsEntry(UUID integrationId, UUID reportId, UUID contractId, int contractVersion,
		String outcome, Instant receivedAt, List<Violation> violations) {

	public record Violation(UUID groupId, String fieldName, String kind, String expectedType, String actualType) {
	}
}
