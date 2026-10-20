package contractwatcher.monitoring.dto;

import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(requiredProperties = {"items", "total", "offset", "limit"}, additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record ViolationGroupsPage(List<ViolationGroup> items,
		@Schema(minimum = "0", maximum = ApiSchema.MAX_LONG, description = "Число групп до пагинации, не отчетов или появлений.") long total,
		@Schema(minimum = "0") int offset, @Schema(minimum = "1", maximum = "100") int limit) {
}
