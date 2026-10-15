package contractwatcher.monitoring.controller;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import contractwatcher.monitoring.dto.ReportDetails;
import contractwatcher.monitoring.service.ReportDetailsService;

@RestController
final class ReportDetailsController {

	static final String PATH = "/internal/v1/integrations/{integrationId}/reports/{reportId}";

	private final ReportDetailsService service;

	ReportDetailsController(ReportDetailsService service) {
		this.service = service;
	}

	@GetMapping(path = PATH, produces = MediaType.APPLICATION_JSON_VALUE)
	ReportDetails details(@PathVariable String integrationId, @PathVariable String reportId) {
		return service.details(integrationId, reportId);
	}
}
