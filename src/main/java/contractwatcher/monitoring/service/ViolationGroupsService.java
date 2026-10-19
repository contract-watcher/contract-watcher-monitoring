package contractwatcher.monitoring.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;

import contractwatcher.monitoring.dto.ReportViolation;
import contractwatcher.monitoring.dto.ViolationGroup;
import contractwatcher.monitoring.dto.ViolationGroupsPage;
import contractwatcher.monitoring.exception.ViolationGroupDetailsRequestException;
import contractwatcher.monitoring.exception.ViolationGroupNotFoundException;
import contractwatcher.monitoring.model.ViolationGroupsResult;
import contractwatcher.monitoring.repository.ViolationGroupRepository;

@Service
public final class ViolationGroupsService {

	private static final Pattern UUID_FORMAT = Pattern.compile(
			"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

	private final ViolationGroupRepository groups;

	ViolationGroupsService(ViolationGroupRepository groups) {
		this.groups = groups;
	}

	public ViolationGroupsPage list(String integrationId, MultiValueMap<String, String> parameters) {
		var query = ViolationGroupsParameters.parse(integrationId, parameters);
		var result = groups.findGroups(query);
		var items = result.items().stream().map(ViolationGroupsService::item).toList();
		return new ViolationGroupsPage(items, result.total(), query.offset(), query.limit());
	}

	public ViolationGroup details(String integrationId, String groupId) {
		Map<String, List<String>> errors = new LinkedHashMap<>();
		UUID integration = identifier(integrationId, "integrationId", errors);
		UUID group = identifier(groupId, "groupId", errors);
		if (!errors.isEmpty()) {
			throw new ViolationGroupDetailsRequestException(errors);
		}
		return item(groups.findDetails(integration, group).orElseThrow(ViolationGroupNotFoundException::new));
	}

	private static UUID identifier(String value, String name, Map<String, List<String>> errors) {
		if (UUID_FORMAT.matcher(value).matches()) {
			return UUID.fromString(value);
		}
		errors.put(name, List.of("Требуется UUID в стандартной строковой записи."));
		return null;
	}

	private static ViolationGroup item(ViolationGroupsResult.Item entry) {
		ReportViolation.Kind kind = switch (entry.kind()) {
			case "REQUIRED_FIELD_MISSING" -> ReportViolation.Kind.RequiredFieldMissing;
			case "NULL_NOT_ALLOWED" -> ReportViolation.Kind.NullNotAllowed;
			case "TYPE_MISMATCH" -> ReportViolation.Kind.TypeMismatch;
			default -> throw new IllegalStateException("Неизвестный сохраненный вид нарушения.");
		};
		return new ViolationGroup(entry.groupId(), entry.contractId(), entry.contractVersion(),
				entry.fieldName(), kind, jsonType(entry.expectedType()), jsonType(entry.actualType()),
				entry.occurrenceCount(), entry.firstSeenAt(), entry.lastSeenAt());
	}

	private static ReportViolation.JsonType jsonType(String type) {
		return switch (type) {
			case null -> null;
			case "string" -> ReportViolation.JsonType.String;
			case "number" -> ReportViolation.JsonType.Number;
			case "boolean" -> ReportViolation.JsonType.Boolean;
			case "object" -> ReportViolation.JsonType.Object;
			case "array" -> ReportViolation.JsonType.Array;
			default -> throw new IllegalStateException("Неизвестный сохраненный тип поля.");
		};
	}
}
