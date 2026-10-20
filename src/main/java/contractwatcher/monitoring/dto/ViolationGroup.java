package contractwatcher.monitoring.dto;

import java.time.Instant;
import java.util.UUID;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(requiredProperties = {"groupId", "contractId", "contractVersion", "fieldName", "type", "expectedType", "actualType", "occurrenceCount", "firstSeenAt", "lastSeenAt"},
		additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
		description = "Ключ: integrationId + contractId + contractVersion + fieldName + type + actualType (включая null).")
public record ViolationGroup(UUID groupId, UUID contractId,
		@Schema(minimum = "1", description = "Версия внутри contractId.") int contractVersion,
		@Schema(description = "Точное имя, включая пробелы и регистр.") String fieldName,
		ReportViolation.Kind type,
		@Schema(nullable = true, description = "Metadata создавшего группу появления; не входит в ключ и не обновляется.") ReportViolation.JsonType expectedType,
		@Schema(nullable = true) ReportViolation.JsonType actualType,
		@Schema(minimum = "1", maximum = ApiSchema.MAX_LONG, description = "Каждый элемент violations нового отчета, включая одинаковые, дает одно появление.") long occurrenceCount,
		@Schema(description = "Минимум сохраненного времени SDK связанных отчетов.") Instant firstSeenAt,
		@Schema(description = "Максимум сохраненного времени SDK связанных отчетов; firstSeenAt <= lastSeenAt.") Instant lastSeenAt) {
}
