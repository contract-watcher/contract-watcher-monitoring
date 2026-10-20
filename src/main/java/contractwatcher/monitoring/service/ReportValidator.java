package contractwatcher.monitoring.service;

import contractwatcher.monitoring.dto.ReportRequest;
import contractwatcher.monitoring.dto.ReportViolation;
import contractwatcher.monitoring.dto.ValidationReport;
import contractwatcher.monitoring.exception.ReportValidationException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

@Component
final class ReportValidator {

	private static final Pattern UUID_FORMAT = Pattern.compile(
			"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
	private static final Instant FIRST_TIMESTAMP = Instant.parse("0001-01-01T00:00:00Z");
	private static final Instant AFTER_LAST_TIMESTAMP = Instant.parse("+10000-01-01T00:00:00Z");

	void validate(ReportRequest request) {
		Map<String, List<String>> errors = new LinkedHashMap<>();
		uuid(request.integrationId(), "integrationId", errors);
		uuid(request.contractId(), "contractId", errors);
		if (required(request.report(), "report", errors)) {
			validateReport(request.report(), errors);
		}
		if (!errors.isEmpty()) {
			throw new ReportValidationException(errors);
		}
	}

	private void validateReport(ValidationReport report, Map<String, List<String>> errors) {
		uuid(report.reportId(), "report.reportId", errors);
		if (required(report.contractVersion(), "report.contractVersion", errors) && report.contractVersion() <= 0) {
			add(errors, "report.contractVersion", "Версия должна быть положительным целым числом.");
		}
		required(report.status(), "report.status", errors);
		if (required(report.timestamp(), "report.timestamp", errors)
				&& (report.timestamp().isBefore(FIRST_TIMESTAMP) || !report.timestamp().isBefore(AFTER_LAST_TIMESTAMP))) {
			add(errors, "report.timestamp", "Требуется время с годом 0001-9999 после приведения к UTC.");
		}
		if (!required(report.violations(), "report.violations", errors)) {
			return;
		}
		if (report.status() == ValidationReport.Status.Passed && !report.violations().isEmpty()) {
			add(errors, "report.violations", "Для Passed список нарушений должен быть пустым.");
		}
		if (report.status() == ValidationReport.Status.Failed && report.violations().isEmpty()) {
			add(errors, "report.violations", "Для Failed требуется хотя бы одно нарушение.");
		}
		for (int index = 0; index < report.violations().size(); index++) {
			String path = "report.violations[" + index + "]";
			ReportViolation violation = report.violations().get(index);
			if (required(violation, path, errors)) {
				validateViolation(violation, path, errors);
			}
		}
	}

	private void validateViolation(ReportViolation violation, String path, Map<String, List<String>> errors) {
		if (required(violation.fieldName(), path + ".fieldName", errors) && violation.fieldName().isEmpty()) {
			add(errors, path + ".fieldName", "Имя поля должно быть непустой строкой.");
		}
		required(violation.type(), path + ".type", errors);
		required(violation.expectedType(), path + ".expectedType", errors);
		if (violation.type() == ReportViolation.Kind.TypeMismatch) {
			if (required(violation.actualType(), path + ".actualType", errors)
					&& violation.actualType() == violation.expectedType()) {
				add(errors, path + ".actualType", "Фактический тип должен отличаться от ожидаемого.");
			}
		} else if (violation.type() != null && violation.actualType() != null) {
			add(errors, path + ".actualType", "Фактический тип должен отсутствовать или быть null.");
		}
	}

	private void uuid(String value, String path, Map<String, List<String>> errors) {
		if (required(value, path, errors) && !UUID_FORMAT.matcher(value).matches()) {
			add(errors, path, "Требуется UUID в стандартной строковой записи.");
		}
	}

	private boolean required(Object value, String path, Map<String, List<String>> errors) {
		if (value == null) {
			add(errors, path, "Обязательное поле.");
			return false;
		}
		return true;
	}

	private void add(Map<String, List<String>> errors, String path, String message) {
		errors.computeIfAbsent(path, ignored -> new ArrayList<>()).add(message);
	}
}
