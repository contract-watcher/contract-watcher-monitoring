package contractwatcher.monitoring.model;

import java.time.Instant;
import java.util.UUID;

public record IntegrationSummaryQuery(UUID integrationId, Instant from, Instant to) {
}
