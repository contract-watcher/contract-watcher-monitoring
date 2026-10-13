package contractwatcher.monitoring.model;

import java.util.List;
import java.util.UUID;

public record ReportToStore(UUID integrationId, UUID contractId, UUID reportId, int contractVersion,
		String outcome, List<Violation> violations) {

	public ReportToStore {
		violations = List.copyOf(violations);
	}

	public record Violation(int ordinal, String fieldName, String kind, String expectedType, String actualType) {
	}
}
