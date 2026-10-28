package contractwatcher.monitoring.dto;

import java.util.List;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(requiredProperties = "integrationIds", additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
		description = "Строгий JSON без повторных свойств, до 524288 байт; null недопустим. Все параметры только в теле.")
public record IntegrationSummariesRequest(
		@ArraySchema(maxItems = 10000, schema = @Schema(type = "string", format = "uuid", pattern = ApiSchema.UUID_PATTERN)) List<String> integrationIds,
		@Schema(format = "date-time", pattern = ApiSchema.UTC_PATTERN, maxLength = 27, description = "Включенная граница UTC; отсутствие означает открытую границу.") String from,
		@Schema(format = "date-time", pattern = ApiSchema.UTC_PATTERN, maxLength = 27, description = "Исключенная граница UTC, from < to; отсутствие означает открытую границу.") String to,
		@Schema(defaultValue = "false") Boolean onlyFailed,
		@Schema(minimum = "0", maximum = "2147483647", defaultValue = "0") Integer offset,
		@Schema(minimum = "1", maximum = "100", defaultValue = "20") Integer limit) {
}
