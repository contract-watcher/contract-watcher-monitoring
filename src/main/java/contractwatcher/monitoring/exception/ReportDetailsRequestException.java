package contractwatcher.monitoring.exception;

import java.util.List;
import java.util.Map;

public final class ReportDetailsRequestException extends RuntimeException {

	private final Map<String, List<String>> errors;

	public ReportDetailsRequestException(Map<String, List<String>> errors) {
		super("Некорректные идентификаторы отчета.");
		this.errors = Map.copyOf(errors);
	}

	public Map<String, List<String>> errors() {
		return errors;
	}
}
