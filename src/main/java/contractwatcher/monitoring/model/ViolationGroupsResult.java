package contractwatcher.monitoring.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ViolationGroupsResult(List<Item> items, long total) {

	public record Item(UUID groupId, UUID contractId, int contractVersion, String fieldName,
			String kind, String expectedType, String actualType, long occurrenceCount,
			Instant firstSeenAt, Instant lastSeenAt) {
	}
}
