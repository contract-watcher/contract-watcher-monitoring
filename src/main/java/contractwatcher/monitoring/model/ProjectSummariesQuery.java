package contractwatcher.monitoring.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ProjectSummariesQuery(List<UUID> integrationIds, Instant from, Instant to,
		boolean onlyFailed, int offset, int limit) {
}
