package contractwatcher.monitoring.reports;

import java.util.List;
import java.util.Map;

final class ReportValidationException extends RuntimeException {

	private final Map<String, List<String>> errors;

	ReportValidationException(Map<String, List<String>> errors) {
		super("Отчет содержит некорректные данные.");
		this.errors = Map.copyOf(errors);
	}

	Map<String, List<String>> errors() {
		return errors;
	}
}
