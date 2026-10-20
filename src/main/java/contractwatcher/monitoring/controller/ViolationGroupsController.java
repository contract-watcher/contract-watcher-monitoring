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
import contractwatcher.monitoring.config.ApiExamples;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;

@RestController
final class ViolationGroupsController {

	static final String PATH = "/internal/v1/integrations/{integrationId}/violation-groups";
	static final String DETAILS_PATH = PATH + "/{groupId}";

	private final ViolationGroupsService service;

	ViolationGroupsController(ViolationGroupsService service) {
		this.service = service;
	}

	@Operation(operationId = "getViolationGroups", tags = "Группы нарушений", summary = "Получить группы нарушений интеграции",
			description = "Порядок lastSeenAt DESC, groupId DESC; при новых отчетах страницы могут сдвигаться. "
					+ "Пустая или неизвестная интеграция дает items=[] и total=0; страница за пределами результата сохраняет total.",
			parameters = {
			@Parameter(name = "offset", in = ParameterIn.QUERY, description = "Пропустить группы. Одно непустое значение, только цифры без знака и пробелов; ведущие нули допустимы.",
					schema = @Schema(type = "integer", format = "int32", defaultValue = "0", minimum = "0", maximum = "2147483647")),
			@Parameter(name = "limit", in = ParameterIn.QUERY, description = "Размер страницы. Одно непустое значение, только цифры без знака и пробелов; ведущие нули допустимы.",
					schema = @Schema(type = "integer", format = "int32", defaultValue = "20", minimum = "1", maximum = "100"))})
	@ApiResponse(responseCode = "200", description = "Страница групп без дополнительных фильтров. Известные query-параметры однократные и непустые.",
			useReturnTypeSchema = true, content = @Content(mediaType = "application/json", examples = {
					@ExampleObject(name = "page", value = ApiExamples.GROUPS), @ExampleObject(name = "empty", value = ApiExamples.EMPTY_PAGE),
					@ExampleObject(name = "outsidePage", value = ApiExamples.OUTSIDE_PAGE)}))
	@GetMapping(path = PATH, produces = MediaType.APPLICATION_JSON_VALUE)
	ViolationGroupsPage list(@PathVariable String integrationId,
			@Parameter(hidden = true) @RequestParam MultiValueMap<String, String> parameters) {
		return service.list(integrationId, parameters);
	}

	@Operation(operationId = "getViolationGroup", tags = "Группы нарушений", summary = "Получить подробности группы нарушений",
			description = "Возвращает сохраненные metadata и число появлений одной группы без пересчета. "
					+ "Неизвестная или чужая группа и неизвестная интеграция дают одинаковый 404.")
	@ApiResponse(responseCode = "200", description = "Группа, на которую ссылается groupId нарушения отчета.", useReturnTypeSchema = true,
			content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "group", value = ApiExamples.GROUP)))
	@GetMapping(path = DETAILS_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
	ViolationGroup details(@PathVariable String integrationId, @PathVariable String groupId) {
		return service.details(integrationId, groupId);
	}
}
