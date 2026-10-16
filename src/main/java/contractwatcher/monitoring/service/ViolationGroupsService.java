package contractwatcher.monitoring.service;

import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;

import contractwatcher.monitoring.dto.ReportViolation;
import contractwatcher.monitoring.dto.ViolationGroupsPage;
import contractwatcher.monitoring.model.ViolationGroupsResult;
import contractwatcher.monitoring.repository.ViolationGroupRepository;

@Service
public final class ViolationGroupsService {

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

	private static ViolationGroupsPage.Item item(ViolationGroupsResult.Item entry) {
		ReportViolation.Kind kind = switch (entry.kind()) {
			case "REQUIRED_FIELD_MISSING" -> ReportViolation.Kind.RequiredFieldMissing;
			case "NULL_NOT_ALLOWED" -> ReportViolation.Kind.NullNotAllowed;
			case "TYPE_MISMATCH" -> ReportViolation.Kind.TypeMismatch;
			default -> throw new IllegalStateException("Неизвестный сохраненный вид нарушения.");
		};
		return new ViolationGroupsPage.Item(entry.groupId(), entry.contractId(), entry.contractVersion(),
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
