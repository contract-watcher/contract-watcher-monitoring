package contractwatcher.monitoring.reports;

public record ReportRequest(String integrationId, String contractId, ValidationReport report) {
}
