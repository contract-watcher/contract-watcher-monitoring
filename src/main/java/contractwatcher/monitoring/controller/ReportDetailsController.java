package contractwatcher.monitoring.controller;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import contractwatcher.monitoring.dto.ReportDetails;
import contractwatcher.monitoring.service.ReportDetailsService;
import contractwatcher.monitoring.config.ApiExamples;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;

@RestController
final class ReportDetailsController {

	static final String PATH = "/internal/v1/integrations/{integrationId}/reports/{reportId}";

	private final ReportDetailsService service;

	ReportDetailsController(ReportDetailsService service) {
		this.service = service;
	}

	@Operation(operationId = "getReportDetails", tags = "Отчеты", summary = "Получить сохраненный отчет с нарушениями",
			description = "Возвращает отчет в указанной интеграции и все его нарушения в исходном порядке, включая одинаковые. "
					+ "Неизвестный или чужой reportId и неизвестная интеграция дают одинаковый 404.")
	@ApiResponse(responseCode = "200", description = "Сохраненный отчет.", useReturnTypeSchema = true,
			content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "failed", value = ApiExamples.DETAILS)))
	@GetMapping(path = PATH, produces = MediaType.APPLICATION_JSON_VALUE)
	ReportDetails details(@PathVariable String integrationId, @PathVariable String reportId) {
		return service.details(integrationId, reportId);
	}
}
