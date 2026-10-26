package contractwatcher.monitoring.exception;

import java.util.List;
import java.util.Map;

public final class IntegrationSummaryRequestException extends RuntimeException {

	private final Map<String, List<String>> errors;

	public IntegrationSummaryRequestException(Map<String, List<String>> errors) {
		super(errors.size() == 1 && errors.containsKey("integrationId")
				? "Некорректный идентификатор интеграции." : "Некорректные параметры сводки.");
		this.errors = Map.copyOf(errors);
	}

	public Map<String, List<String>> errors() {
		return errors;
	}
}
