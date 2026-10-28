package contractwatcher.monitoring.controller;

import java.io.IOException;
import java.util.List;
import java.util.Collections;

import org.springframework.http.MediaType;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import contractwatcher.monitoring.dto.IntegrationSummariesPage;
import contractwatcher.monitoring.dto.IntegrationSummariesRequest;
import contractwatcher.monitoring.service.ProjectSummariesParameters;
import contractwatcher.monitoring.service.ProjectSummariesService;
import contractwatcher.monitoring.config.ApiExamples;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import jakarta.servlet.http.HttpServletRequest;

@RestController
final class ProjectSummariesController {

	static final String PATH = "/internal/v1/integration-summaries/query";
	private final ProjectSummariesService service;

	ProjectSummariesController(ProjectSummariesService service) {
		this.service = service;
	}

	@Operation(operationId = "queryIntegrationSummaries", tags = "Сводка", summary = "Получить страницу сводок проверенного набора интеграций",
			description = "Core передает весь проверенный набор проекта либо подмножество, до 10000 элементов до дедупликации. "
					+ "[] означает пустой набор. Один снимок items/total; total после onlyFailed до страницы. "
					+ "Порядок failedReports DESC, integrationId ASC по PostgreSQL uuid. Нули/null без проверок не означают исправность. "
					+ "Период [from,to), без границ all-time; Сегодня передает потребитель по поясу браузера. "
					+ "Неизвестные/повторные свойства и непустой query отклоняются. Только несжатый JSON. "
					+ "Повтор чтения безопасен; разные страницы могут изменяться при поступлении отчетов.",
			requestBody = @RequestBody(required = true, content = @Content(mediaType = "application/json",
					schema = @Schema(implementation = IntegrationSummariesRequest.class),
					examples = @ExampleObject(value = ApiExamples.PROJECT_SUMMARIES_REQUEST))))
	@ApiResponse(responseCode = "200", description = "Страница сводок; total сохраняется и за концом набора.", useReturnTypeSchema = true,
			content = @Content(mediaType = "application/json", examples = {
					@ExampleObject(name = "mixed", value = ApiExamples.PROJECT_SUMMARIES),
					@ExampleObject(name = "empty", value = ApiExamples.EMPTY_PAGE),
					@ExampleObject(name = "outsidePage", value = ApiExamples.OUTSIDE_PAGE)}))
	@PostMapping(path = PATH, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	IntegrationSummariesPage query(HttpServletRequest request) throws IOException, HttpMediaTypeNotSupportedException {
		for (String encoding : Collections.list(request.getHeaders("Content-Encoding"))) {
			if (!"identity".equalsIgnoreCase(encoding.trim())) {
				throw new HttpMediaTypeNotSupportedException("Требуется несжатый JSON.", List.of(MediaType.APPLICATION_JSON));
			}
		}
		return service.query(ProjectSummariesParameters.parse(request.getInputStream(), request.getQueryString()));
	}
}
