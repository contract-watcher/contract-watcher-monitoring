package contractwatcher.monitoring.dto;

import java.time.Instant;
import java.util.UUID;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(requiredProperties = {"integrationId", "reportId", "receivedAt", "duplicate"}, additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record ReportAccepted(UUID integrationId, UUID reportId,
		@Schema(description = ApiSchema.RECEIVED_AT) Instant receivedAt,
		@Schema(description = "true при повторе integrationId + reportId; возвращается прежний receivedAt.") boolean duplicate) {
}
