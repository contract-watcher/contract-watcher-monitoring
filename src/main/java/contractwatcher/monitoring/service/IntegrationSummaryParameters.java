package contractwatcher.monitoring.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.util.MultiValueMap;

import contractwatcher.monitoring.exception.IntegrationSummaryRequestException;
import contractwatcher.monitoring.model.IntegrationSummaryQuery;

final class IntegrationSummaryParameters {

	private static final Pattern UUID_FORMAT = Pattern.compile(
			"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

	private IntegrationSummaryParameters() {
	}

	static IntegrationSummaryQuery parse(String integrationId, MultiValueMap<String, String> parameters) {
		Map<String, List<String>> errors = new LinkedHashMap<>();
		UUID integration = null;
		if (UUID_FORMAT.matcher(integrationId).matches()) {
			integration = UUID.fromString(integrationId);
		} else {
			errors.put("integrationId", List.of("Требуется UUID в стандартной строковой записи."));
		}
		var period = ReportPeriodParameters.parse(parameters, errors);
		if (!errors.isEmpty()) {
			throw new IntegrationSummaryRequestException(errors);
		}
		return new IntegrationSummaryQuery(integration, period.from(), period.to());
	}
}
