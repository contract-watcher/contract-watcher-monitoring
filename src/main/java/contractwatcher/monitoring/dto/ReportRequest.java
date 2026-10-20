package contractwatcher.monitoring.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(requiredProperties = {"integrationId", "contractId", "report"})
public record ReportRequest(@Schema(format = "uuid", pattern = ApiSchema.UUID_PATTERN) String integrationId,
		@Schema(format = "uuid", pattern = ApiSchema.UUID_PATTERN,
				description = "Самостоятельный контракт интеграции, определенный Core.") String contractId,
		ValidationReport report) {
}
