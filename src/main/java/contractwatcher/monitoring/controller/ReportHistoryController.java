package contractwatcher.monitoring.controller;

import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import contractwatcher.monitoring.dto.ReportHistoryPage;
import contractwatcher.monitoring.service.ReportHistoryService;
import contractwatcher.monitoring.config.ApiExamples;
import contractwatcher.monitoring.dto.ApiSchema;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.enums.ParameterStyle;
import io.swagger.v3.oas.annotations.enums.Explode;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;

@RestController
final class ReportHistoryController {

	private final ReportHistoryService service;

	ReportHistoryController(ReportHistoryService service) {
		this.service = service;
	}

	@Operation(operationId = "getReportHistory", tags = "Отчеты", summary = "Получить историю проверок",
			description = "Применяет фильтры до пагинации; порядок receivedAt DESC, затем внутренний ID отчета DESC. "
					+ "Пустая или неизвестная интеграция дает items=[] и total=0; страница за пределами результата сохраняет total, при новых отчетах страницы могут сдвигаться.", parameters = {
			@Parameter(name = "contractId", in = ParameterIn.QUERY, style = ParameterStyle.FORM, explode = Explode.TRUE,
					description = "Повторяемый UUID: ИЛИ внутри набора, И со статусом и периодом. Без параметра все контракты; выбранный ID включает все версии. "
							+ "До 50 вхождений до удаления повторов UUID, включая разный регистр. Пустое/неверное значение или превышение: 400 INVALID_REQUEST, errors.contractId. "
							+ "Неизвестный или чужой ID не дает совпадений; смешанный набор возвращает только отчеты указанной интеграции. CSV не поддерживается.",
					array = @ArraySchema(minItems = 1, maxItems = 50,
							schema = @Schema(type = "string", format = "uuid", pattern = ApiSchema.UUID_PATTERN)),
					example = "[\"33333333-3333-4333-8333-333333333333\",\"44444444-4444-4444-8444-444444444444\"]"),
			@Parameter(name = "from", in = ParameterIn.QUERY, description = "Включенная нижняя граница receivedAt; год 0001-9999, при двух границах from < to. Одно непустое значение.",
					schema = @Schema(type = "string", format = "date-time", pattern = ApiSchema.UTC_PATTERN), example = "2026-10-19T00:00:00Z"),
			@Parameter(name = "to", in = ParameterIn.QUERY, description = "Исключенная верхняя граница receivedAt; год 0001-9999. Одно непустое значение.",
					schema = @Schema(type = "string", format = "date-time", pattern = ApiSchema.UTC_PATTERN), example = "2026-10-20T00:00:00Z"),
			@Parameter(name = "status", in = ParameterIn.QUERY, description = "Одно непустое значение; без параметра оба результата.", schema = @Schema(allowableValues = {"Passed", "Failed"})),
			@Parameter(name = "offset", in = ParameterIn.QUERY, description = "Пропустить отчеты. Одно непустое значение, только цифры без знака и пробелов; ведущие нули допустимы.",
					schema = @Schema(type = "integer", format = "int32", defaultValue = "0", minimum = "0", maximum = "2147483647")),
			@Parameter(name = "limit", in = ParameterIn.QUERY, description = "Размер страницы. Одно непустое значение, только цифры без знака и пробелов; ведущие нули допустимы.",
					schema = @Schema(type = "integer", format = "int32", defaultValue = "20", minimum = "1", maximum = "100"))})
	@ApiResponse(responseCode = "200", description = "Страница истории. Кроме повторяемого contractId, каждый известный query-параметр передается один раз и непустым.",
			useReturnTypeSchema = true, content = @Content(mediaType = "application/json", examples = {
					@ExampleObject(name = "page", value = ApiExamples.HISTORY), @ExampleObject(name = "empty", value = ApiExamples.EMPTY_PAGE),
					@ExampleObject(name = "outsidePage", value = ApiExamples.OUTSIDE_PAGE)}))
	@GetMapping(path = "/internal/v1/integrations/{integrationId}/reports", produces = MediaType.APPLICATION_JSON_VALUE)
	ReportHistoryPage history(@PathVariable String integrationId,
			@Parameter(hidden = true) @RequestParam MultiValueMap<String, String> parameters) {
		return service.history(integrationId, parameters);
	}
}
