package contractwatcher.monitoring.dto;

import java.util.List;

public record ValidationReport(String reportId, Integer contractVersion, Status status,
		List<ReportViolation> violations) {

	public enum Status {
		Passed, Failed
	}
}
