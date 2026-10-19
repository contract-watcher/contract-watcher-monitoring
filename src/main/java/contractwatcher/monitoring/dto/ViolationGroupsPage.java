package contractwatcher.monitoring.dto;

import java.util.List;

public record ViolationGroupsPage(List<ViolationGroup> items, long total, int offset, int limit) {
}
