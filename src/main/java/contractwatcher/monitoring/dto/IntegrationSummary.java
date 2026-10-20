package contractwatcher.monitoring.dto;

import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(requiredProperties = {"integrationId", "totalReports", "passedReports", "failedReports", "lastReceivedAt"},
		additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record IntegrationSummary(UUID integrationId,
		@Schema(description = "Число сохраненных отчетов всех контрактов и версий; равно passedReports + failedReports.", minimum = "0") long totalReports,
		@Schema(description = "Число отчетов Passed.", minimum = "0") long passedReports,
		@Schema(description = "Число отчетов Failed, независимо от количества нарушений.", minimum = "0") long failedReports,
		@Schema(description = "Максимум сохраненного времени формирования отчетов SDK из report.timestamp; null без отчетов.", nullable = true)
		Instant lastReceivedAt) {
}
