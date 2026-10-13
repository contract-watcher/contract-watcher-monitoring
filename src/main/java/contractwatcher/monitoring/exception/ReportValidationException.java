package contractwatcher.monitoring.exception;

import java.util.List;
import java.util.Map;

public final class ReportValidationException extends RuntimeException {

	private final Map<String, List<String>> errors;

	public ReportValidationException(Map<String, List<String>> errors) {
		super("Отчет содержит некорректные данные.");
		this.errors = Map.copyOf(errors);
	}

	public Map<String, List<String>> errors() {
		return errors;
	}
}
