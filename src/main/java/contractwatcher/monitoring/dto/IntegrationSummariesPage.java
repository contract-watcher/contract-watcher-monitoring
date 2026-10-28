package contractwatcher.monitoring.dto;

import java.util.List;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(requiredProperties = {"items", "total", "offset", "limit"}, additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record IntegrationSummariesPage(@ArraySchema(maxItems = 100) List<IntegrationSummary> items,
		@Schema(minimum = "0", maximum = ApiSchema.MAX_LONG, description = "Уникальные интеграции после onlyFailed, до страницы.") long total,
		@Schema(minimum = "0") int offset, @Schema(minimum = "1", maximum = "100") int limit) {
}
