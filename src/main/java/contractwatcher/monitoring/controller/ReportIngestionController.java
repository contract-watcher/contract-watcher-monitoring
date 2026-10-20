package contractwatcher.monitoring.controller;

import contractwatcher.monitoring.dto.ReportAccepted;
import contractwatcher.monitoring.dto.ReportRequest;
import contractwatcher.monitoring.service.ReportIngestionService;
import contractwatcher.monitoring.config.ApiExamples;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
final class ReportIngestionController {

	private final ReportIngestionService service;

	ReportIngestionController(ReportIngestionService service) {
		this.service = service;
	}

	@Operation(operationId = "ingestReport", tags = "Отчеты", summary = "Сохранить отчет проверки",
			description = "Атомарно сохраняет отчет, нарушения и появления групп; подтверждает после commit. "
					+ "Валидный повтор integrationId + reportId сохраняет содержимое первого отчета и прежний receivedAt, не меняя счетчики.",
			requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
					content = @Content(mediaType = "application/json", schema = @Schema(implementation = ReportRequest.class),
							examples = {@ExampleObject(name = "failedWithRepeatedViolations", value = ApiExamples.REQUEST),
									@ExampleObject(name = "passed", value = ApiExamples.PASSED_REQUEST)})))
	@ApiResponse(responseCode = "201", description = "Новый отчет сохранен.", content = @Content(
			mediaType = "application/json", schema = @Schema(implementation = ReportAccepted.class),
			examples = @ExampleObject(name = "created", value = ApiExamples.ACCEPTED)))
	@ApiResponse(responseCode = "200", description = "Повтор: первый отчет сохранен ранее.", content = @Content(
			mediaType = "application/json", schema = @Schema(implementation = ReportAccepted.class),
			examples = @ExampleObject(name = "duplicate", value = ApiExamples.DUPLICATE)))
	@PostMapping(path = "/internal/v1/reports", consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE)
	ResponseEntity<ReportAccepted> ingest(@RequestBody ReportRequest request) {
		ReportAccepted accepted = service.ingest(request);
		return ResponseEntity.status(accepted.duplicate() ? HttpStatus.OK : HttpStatus.CREATED).body(accepted);
	}
}
