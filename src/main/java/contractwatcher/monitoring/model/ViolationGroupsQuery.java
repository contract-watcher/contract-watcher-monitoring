package contractwatcher.monitoring.model;

import java.util.UUID;

public record ViolationGroupsQuery(UUID integrationId, int offset, int limit) {
}
