package contractwatcher.monitoring.dto;

import java.time.Instant;
import java.util.UUID;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(requiredProperties = {"reportId", "contractId", "contractVersion", "status", "receivedAt", "violationCount"},
		additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record ReportHistoryItem(UUID reportId, UUID contractId,
		@Schema(minimum = "1", description = "Версия внутри contractId.") int contractVersion, ValidationReport.Status status,
		@Schema(description = ApiSchema.RECEIVED_AT) Instant receivedAt,
		@Schema(minimum = "0", maximum = ApiSchema.MAX_LONG, description = "Число элементов violations, включая одинаковые.") long violationCount) {
}
