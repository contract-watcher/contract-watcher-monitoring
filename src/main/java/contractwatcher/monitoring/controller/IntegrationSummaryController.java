package contractwatcher.monitoring.controller;

import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import contractwatcher.monitoring.dto.IntegrationSummary;
import contractwatcher.monitoring.dto.ApiSchema;
import contractwatcher.monitoring.service.IntegrationSummaryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
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
			description = "Считает отчеты всех контрактов и версий и MAX(receivedAt) в одном снимке выбранного периода [from,to). "
					+ "Без границ возвращает все время; каждая граница необязательна независимо. "
					+ "Потребитель переводит начало первого дня и начало дня после последнего из пояса браузера в UTC. "
					+ "Duplicate не меняет показатели; без отчетов в периоде нули и lastReceivedAt=null. "
					+ "Неизвестные параметры, включая status/contractId/limit, игнорируются.", parameters = {
			@Parameter(name = "from", in = ParameterIn.QUERY,
					description = "Включенная нижняя граница сохраненного receivedAt. UTC с Z, год 0001-9999, секунды и до 6 знаков дроби. "
							+ "Одно непустое значение; при обеих границах from < to. Неверный формат, календарь или повтор: 400 INVALID_REQUEST до SQL.",
					schema = @Schema(type = "string", format = "date-time", pattern = ApiSchema.UTC_PATTERN, maxLength = 27),
					example = "2026-10-25T21:00:00Z"),
			@Parameter(name = "to", in = ParameterIn.QUERY,
					description = "Исключенная верхняя граница receivedAt. Те же правила UTC и единственного непустого значения, что у from; "
							+ "без нее нет скрытого ограничения now. Для целого дня передайте начало следующего дня.",
					schema = @Schema(type = "string", format = "date-time", pattern = ApiSchema.UTC_PATTERN, maxLength = 27),
					example = "2026-10-26T21:00:00Z")})
	@ApiResponse(responseCode = "200", description = "Сводка отчетов периода; без from/to все время.", useReturnTypeSchema = true,
			content = @Content(mediaType = "application/json", examples = {
					@ExampleObject(name = "mixed", value = ApiExamples.SUMMARY),
					@ExampleObject(name = "selectedDay", value = ApiExamples.PERIOD_SUMMARY),
					@ExampleObject(name = "emptyOrUnknown", value = ApiExamples.EMPTY_SUMMARY)}))
	@GetMapping(path = PATH, produces = MediaType.APPLICATION_JSON_VALUE)
	IntegrationSummary summary(@PathVariable String integrationId,
			@Parameter(hidden = true) @RequestParam MultiValueMap<String, String> parameters) {
		return service.summary(integrationId, parameters);
	}
}
