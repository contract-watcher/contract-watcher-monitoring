package contractwatcher.monitoring.dto;

import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(requiredProperties = {"integrationId", "totalReports", "passedReports", "failedReports", "lastReceivedAt"},
		additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record IntegrationSummary(UUID integrationId,
		@Schema(description = "Число отчетов всех контрактов и версий в выбранном периоде; равно passedReports + failedReports.", minimum = "0") long totalReports,
		@Schema(description = "Число отчетов Passed в выбранном периоде.", minimum = "0") long passedReports,
		@Schema(description = "Число отчетов Failed в выбранном периоде, независимо от количества нарушений.", minimum = "0") long failedReports,
		@Schema(description = "MAX сохраненного receivedAt только в выбранном периоде; null без отчетов в нем. "
				+ "У новых отчетов это timestamp SDK, старые даты сохранены. Без границ максимум за все время.", nullable = true)
		Instant lastReceivedAt) {
}
