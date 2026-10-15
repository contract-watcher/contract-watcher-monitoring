package contractwatcher.monitoring.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReportDetails(UUID integrationId, UUID reportId, UUID contractId, int contractVersion,
		ValidationReport.Status status, Instant receivedAt, long violationCount, List<Violation> violations) {

	public ReportDetails {
		violations = List.copyOf(violations);
	}

	public record Violation(UUID groupId, String fieldName, ReportViolation.Kind type,
			ReportViolation.JsonType expectedType, ReportViolation.JsonType actualType) {
	}
}
