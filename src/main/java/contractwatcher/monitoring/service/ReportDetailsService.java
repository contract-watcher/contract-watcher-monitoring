package contractwatcher.monitoring.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import contractwatcher.monitoring.dto.ReportDetails;
import contractwatcher.monitoring.dto.ReportViolation;
import contractwatcher.monitoring.dto.ValidationReport;
import contractwatcher.monitoring.exception.ReportDetailsRequestException;
import contractwatcher.monitoring.exception.ReportNotFoundException;
import contractwatcher.monitoring.model.ReportDetailsEntry;
import contractwatcher.monitoring.repository.ReportRepository;

@Service
public final class ReportDetailsService {

	private static final Pattern UUID_FORMAT = Pattern.compile(
			"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

	private final ReportRepository reports;

	ReportDetailsService(ReportRepository reports) {
		this.reports = reports;
	}

	public ReportDetails details(String integrationId, String reportId) {
		Map<String, List<String>> errors = new LinkedHashMap<>();
		UUID integration = identifier(integrationId, "integrationId", errors);
		UUID report = identifier(reportId, "reportId", errors);
		if (!errors.isEmpty()) {
			throw new ReportDetailsRequestException(errors);
		}
		var entry = reports.findDetails(integration, report).orElseThrow(ReportNotFoundException::new);
		var violations = entry.violations().stream().map(ReportDetailsService::violation).toList();
		ValidationReport.Status status = switch (entry.outcome()) {
			case "PASSED" -> ValidationReport.Status.Passed;
			case "FAILED" -> ValidationReport.Status.Failed;
			default -> throw new IllegalStateException("Неизвестный сохраненный результат проверки.");
		};
		return new ReportDetails(entry.integrationId(), entry.reportId(), entry.contractId(), entry.contractVersion(),
				status, entry.receivedAt(), (long) violations.size(), violations);
	}

	private static UUID identifier(String value, String name, Map<String, List<String>> errors) {
		if (UUID_FORMAT.matcher(value).matches()) {
			return UUID.fromString(value);
		}
		errors.put(name, List.of("Требуется UUID в стандартной строковой записи."));
		return null;
	}

	private static ReportDetails.Violation violation(ReportDetailsEntry.Violation entry) {
		ReportViolation.Kind kind = switch (entry.kind()) {
			case "REQUIRED_FIELD_MISSING" -> ReportViolation.Kind.RequiredFieldMissing;
			case "NULL_NOT_ALLOWED" -> ReportViolation.Kind.NullNotAllowed;
			case "TYPE_MISMATCH" -> ReportViolation.Kind.TypeMismatch;
			default -> throw new IllegalStateException("Неизвестный сохраненный вид нарушения.");
		};
		return new ReportDetails.Violation(entry.groupId(), entry.fieldName(), kind,
				jsonType(entry.expectedType()), jsonType(entry.actualType()));
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
