package contractwatcher.monitoring.controller;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import contractwatcher.monitoring.dto.IntegrationSummary;
import contractwatcher.monitoring.service.IntegrationSummaryService;
import io.swagger.v3.oas.annotations.Operation;
import contractwatcher.monitoring.config.ApiExamples;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;

@RestController
final class IntegrationSummaryController {

	static final String PATH = "/internal/v1/integrations/{integrationId}/summary";

	private final IntegrationSummaryService service;

	IntegrationSummaryController(IntegrationSummaryService service) {
		this.service = service;
	}

	@Operation(operationId = "getIntegrationSummary", tags = "Сводка", summary = "Получить сводку проверок интеграции",
			description = "Считает сохраненные отчеты всех контрактов и версий за весь период в одном снимке. "
					+ "Duplicate не меняет показатели; пустой или неизвестный Monitoring UUID дает нули и lastReceivedAt=null.")
	@ApiResponse(responseCode = "200", description = "Сводка сохраненных отчетов, без фильтров.", useReturnTypeSchema = true,
			content = @Content(mediaType = "application/json", examples = {
					@ExampleObject(name = "mixed", value = ApiExamples.SUMMARY),
					@ExampleObject(name = "emptyOrUnknown", value = ApiExamples.EMPTY_SUMMARY)}))
	@GetMapping(path = PATH, produces = MediaType.APPLICATION_JSON_VALUE)
	IntegrationSummary summary(@PathVariable String integrationId) {
		return service.summary(integrationId);
	}
}
