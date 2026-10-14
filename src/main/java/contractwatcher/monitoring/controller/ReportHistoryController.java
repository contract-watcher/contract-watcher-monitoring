package contractwatcher.monitoring.controller;

import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import contractwatcher.monitoring.dto.ReportHistoryPage;
import contractwatcher.monitoring.service.ReportHistoryService;

@RestController
final class ReportHistoryController {

	private final ReportHistoryService service;

	ReportHistoryController(ReportHistoryService service) {
		this.service = service;
	}

	@GetMapping(path = "/internal/v1/integrations/{integrationId}/reports", produces = MediaType.APPLICATION_JSON_VALUE)
	ReportHistoryPage history(@PathVariable String integrationId, @RequestParam MultiValueMap<String, String> parameters) {
		return service.history(integrationId, parameters);
	}
}
