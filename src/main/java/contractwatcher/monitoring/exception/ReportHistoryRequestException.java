package contractwatcher.monitoring.exception;

import java.util.List;
import java.util.Map;

public final class ReportHistoryRequestException extends RuntimeException {

	private final Map<String, List<String>> errors;

	public ReportHistoryRequestException(Map<String, List<String>> errors) {
		super("Некорректные параметры истории.");
		this.errors = Map.copyOf(errors);
	}

	public Map<String, List<String>> errors() {
		return errors;
	}
}
