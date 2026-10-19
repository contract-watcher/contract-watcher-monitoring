package contractwatcher.monitoring.dto;

import java.time.Instant;
import java.util.UUID;

public record ViolationGroup(UUID groupId, UUID contractId, int contractVersion, String fieldName,
		ReportViolation.Kind type, ReportViolation.JsonType expectedType, ReportViolation.JsonType actualType,
		long occurrenceCount, Instant firstSeenAt, Instant lastSeenAt) {
}
