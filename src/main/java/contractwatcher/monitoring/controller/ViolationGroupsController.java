package contractwatcher.monitoring.controller;

import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import contractwatcher.monitoring.dto.ViolationGroup;
import contractwatcher.monitoring.dto.ViolationGroupsPage;
import contractwatcher.monitoring.service.ViolationGroupsService;

@RestController
final class ViolationGroupsController {

	static final String PATH = "/internal/v1/integrations/{integrationId}/violation-groups";
	static final String DETAILS_PATH = PATH + "/{groupId}";

	private final ViolationGroupsService service;

	ViolationGroupsController(ViolationGroupsService service) {
		this.service = service;
	}

	@GetMapping(path = PATH, produces = MediaType.APPLICATION_JSON_VALUE)
	ViolationGroupsPage list(@PathVariable String integrationId, @RequestParam MultiValueMap<String, String> parameters) {
		return service.list(integrationId, parameters);
	}

	@GetMapping(path = DETAILS_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
	ViolationGroup details(@PathVariable String integrationId, @PathVariable String groupId) {
		return service.details(integrationId, groupId);
	}
}
