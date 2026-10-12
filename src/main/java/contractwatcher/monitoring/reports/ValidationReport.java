package contractwatcher.monitoring.reports;

import java.util.List;

public record ValidationReport(String reportId, Integer contractVersion, Status status,
		List<ReportViolation> violations) {

	public enum Status {
		Passed("PASSED"), Failed("FAILED");

		private final String databaseValue;

		Status(String databaseValue) {
			this.databaseValue = databaseValue;
		}

		String databaseValue() {
			return databaseValue;
		}
	}
}
