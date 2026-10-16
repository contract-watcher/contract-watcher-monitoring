package contractwatcher.monitoring.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.util.MultiValueMap;

import contractwatcher.monitoring.exception.ViolationGroupsRequestException;
import contractwatcher.monitoring.model.ViolationGroupsQuery;

final class ViolationGroupsParameters {

	private static final Pattern UUID_FORMAT = Pattern.compile(
			"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
	private static final Pattern INTEGER = Pattern.compile("[0-9]+");

	private ViolationGroupsParameters() {
	}

	static ViolationGroupsQuery parse(String integrationId, MultiValueMap<String, String> parameters) {
		Map<String, List<String>> errors = new LinkedHashMap<>();
		UUID integration = null;
		if (UUID_FORMAT.matcher(integrationId).matches()) {
			integration = UUID.fromString(integrationId);
		} else {
			errors.put("integrationId", List.of("Требуется UUID в стандартной строковой записи."));
		}
		int offset = integer(parameters, "offset", 0, 0, Integer.MAX_VALUE, errors);
		int limit = integer(parameters, "limit", 20, 1, 100, errors);
		if (!errors.isEmpty()) {
			throw new ViolationGroupsRequestException(errors);
		}
		return new ViolationGroupsQuery(integration, offset, limit);
	}

	private static int integer(MultiValueMap<String, String> parameters, String name, int defaultValue,
			int min, int max, Map<String, List<String>> errors) {
		List<String> values = parameters.get(name);
		if (values == null) {
			return defaultValue;
		}
		if (values.size() != 1 || values.getFirst() == null || values.getFirst().isEmpty()) {
			errors.put(name, List.of("Параметр должен содержать одно непустое значение."));
			return defaultValue;
		}
		String value = values.getFirst();
		if (INTEGER.matcher(value).matches()) {
			try {
				int number = Integer.parseInt(value);
				if (number >= min && number <= max) {
					return number;
				}
			} catch (NumberFormatException ignored) {
				// Переполнение также является ошибкой параметра.
			}
		}
		errors.put(name, List.of("Требуется целое без знака и пробелов в диапазоне " + min + ".." + max + "."));
		return defaultValue;
	}
}
