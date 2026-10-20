package contractwatcher.monitoring.dto;

import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(requiredProperties = {"reportId", "contractVersion", "status", "violations"})
public record ValidationReport(@Schema(format = "uuid", pattern = ApiSchema.UUID_PATTERN,
			description = "Уникален внутри integrationId. При повторной доставке сохраняйте этот ID.") String reportId,
		@Schema(minimum = "1", description = "Версия внутри contractId, не всей интеграции.") Integer contractVersion,
		Status status,
		@Schema(description = "Для Passed пустой, для Failed непустой. Порядок и одинаковые элементы сохраняются.")
		List<ReportViolation> violations) {

	public enum Status {
		Passed, Failed
	}
}
