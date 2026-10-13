package contractwatcher.monitoring.controller;

import contractwatcher.monitoring.dto.ReportAccepted;
import contractwatcher.monitoring.dto.ReportRequest;
import contractwatcher.monitoring.service.ReportIngestionService;

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

	@PostMapping(path = "/internal/v1/reports", consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE)
	ResponseEntity<ReportAccepted> ingest(@RequestBody ReportRequest request) {
		ReportAccepted accepted = service.ingest(request);
		return ResponseEntity.status(accepted.duplicate() ? HttpStatus.OK : HttpStatus.CREATED).body(accepted);
	}
}
