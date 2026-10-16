package contractwatcher.monitoring.exception;

import java.util.List;
import java.util.Map;

public final class ViolationGroupsRequestException extends RuntimeException {

	private final Map<String, List<String>> errors;

	public ViolationGroupsRequestException(Map<String, List<String>> errors) {
		super("Некорректные параметры списка групп.");
		this.errors = Map.copyOf(errors);
	}

	public Map<String, List<String>> errors() {
		return errors;
	}
}
