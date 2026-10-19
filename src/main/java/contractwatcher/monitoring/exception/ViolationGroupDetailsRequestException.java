package contractwatcher.monitoring.exception;

import java.util.List;
import java.util.Map;

public final class ViolationGroupDetailsRequestException extends RuntimeException {

	private final Map<String, List<String>> errors;

	public ViolationGroupDetailsRequestException(Map<String, List<String>> errors) {
		super("Некорректные идентификаторы группы нарушений.");
		this.errors = Map.copyOf(errors);
	}

	public Map<String, List<String>> errors() {
		return errors;
	}
}
