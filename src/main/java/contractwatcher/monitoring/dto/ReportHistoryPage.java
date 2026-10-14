package contractwatcher.monitoring.dto;

import java.util.List;

public record ReportHistoryPage(List<ReportHistoryItem> items, long total, int offset, int limit) {
}
