package contractwatcher.monitoring.dto;

import java.time.Instant;
import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(requiredProperties = {"reportId", "contractVersion", "status", "timestamp", "violations"})
public record ValidationReport(@Schema(format = "uuid", pattern = ApiSchema.UUID_PATTERN,
			description = "Уникален внутри integrationId. При повторной доставке сохраняйте этот ID.") String reportId,
		@Schema(minimum = "1", description = "Версия внутри contractId, не всей интеграции.") Integer contractVersion,
		Status status,
		@Schema(type = "string", format = "date-time", pattern = ApiSchema.REPORT_TIMESTAMP_PATTERN,
				description = "Время формирования отчета SDK, с часовым поясом. При повторе не меняется. "
						+ "Сохраняется в UTC; знаки после микросекунд отбрасываются.", example = "2026-10-20T10:00:00.1234567+00:00")
		Instant timestamp,
		@Schema(description = "Для Passed пустой, для Failed непустой. Порядок и одинаковые элементы сохраняются.")
		List<ReportViolation> violations) {

	public enum Status {
		Passed, Failed
	}
}
