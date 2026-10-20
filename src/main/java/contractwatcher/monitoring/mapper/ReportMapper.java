package contractwatcher.monitoring.mapper;

import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.stream.IntStream;

import contractwatcher.monitoring.dto.ReportRequest;
import contractwatcher.monitoring.dto.ReportViolation;
import contractwatcher.monitoring.model.ReportToStore;

public final class ReportMapper {

	private ReportMapper() {
	}

	public static ReportToStore toStorage(ReportRequest request) {
		var report = request.report();
		var violations = IntStream.range(0, report.violations().size())
				.mapToObj(ordinal -> toStorage(ordinal, report.violations().get(ordinal)))
				.toList();
		String outcome = switch (report.status()) {
			case Passed -> "PASSED";
			case Failed -> "FAILED";
		};
		// Truncate before JDBC to avoid rounding beyond microseconds.
		return new ReportToStore(UUID.fromString(request.integrationId()), UUID.fromString(request.contractId()),
				UUID.fromString(report.reportId()), report.contractVersion(), outcome,
				report.timestamp().truncatedTo(ChronoUnit.MICROS), violations);
	}

	private static ReportToStore.Violation toStorage(int ordinal, ReportViolation violation) {
		String kind = switch (violation.type()) {
			case RequiredFieldMissing -> "REQUIRED_FIELD_MISSING";
			case NullNotAllowed -> "NULL_NOT_ALLOWED";
			case TypeMismatch -> "TYPE_MISMATCH";
		};
		return new ReportToStore.Violation(ordinal, violation.fieldName(), kind,
				jsonType(violation.expectedType()), jsonType(violation.actualType()));
	}

	private static String jsonType(ReportViolation.JsonType type) {
		return switch (type) {
			case String -> "string";
			case Number -> "number";
			case Boolean -> "boolean";
			case Object -> "object";
			case Array -> "array";
			case null -> null;
		};
	}
}
