package contractwatcher.monitoring.dto;

public record ReportRequest(String integrationId, String contractId, ValidationReport report) {
}
