package contractwatcher.monitoring.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ViolationGroupsPage(List<Item> items, long total, int offset, int limit) {

	public record Item(UUID groupId, UUID contractId, int contractVersion, String fieldName,
			ReportViolation.Kind type, ReportViolation.JsonType expectedType, ReportViolation.JsonType actualType,
			long occurrenceCount, Instant firstSeenAt, Instant lastSeenAt) {
	}
}
