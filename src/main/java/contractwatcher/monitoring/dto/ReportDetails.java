package contractwatcher.monitoring.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(requiredProperties = {"integrationId", "reportId", "contractId", "contractVersion", "status", "receivedAt", "violationCount", "violations"},
		additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record ReportDetails(UUID integrationId, UUID reportId, UUID contractId,
		@Schema(minimum = "1", description = "Версия внутри contractId.") int contractVersion,
		ValidationReport.Status status, @Schema(description = ApiSchema.RECEIVED_AT) Instant receivedAt,
		@Schema(minimum = "0", maximum = ApiSchema.MAX_LONG, description = "Число элементов violations, включая одинаковые.") long violationCount,
		@Schema(description = "В исходном порядке. Для Passed пустой; metadata каждого появления сохраняется отдельно.") List<Violation> violations) {

	public ReportDetails {
		violations = List.copyOf(violations);
	}

	@Schema(name = "ReportDetailsViolation", requiredProperties = {"groupId", "fieldName", "type", "expectedType", "actualType"},
			additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
	public record Violation(@Schema(nullable = true, description = "Ссылка на группу. При null переход к группе недоступен.") UUID groupId,
			String fieldName, ReportViolation.Kind type,
			@Schema(nullable = true) ReportViolation.JsonType expectedType,
			@Schema(nullable = true) ReportViolation.JsonType actualType) {
	}
}
