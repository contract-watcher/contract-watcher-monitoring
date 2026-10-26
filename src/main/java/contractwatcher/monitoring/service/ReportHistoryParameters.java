package contractwatcher.monitoring.service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.util.MultiValueMap;

import contractwatcher.monitoring.exception.ReportHistoryRequestException;
import contractwatcher.monitoring.model.ReportHistoryQuery;

final class ReportHistoryParameters {

	private static final Pattern UUID_FORMAT = Pattern.compile(
			"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
	private static final Pattern UTC_TIME = Pattern.compile(
			"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-5][0-9](\\.[0-9]{1,6})?Z");
	private static final Pattern INTEGER = Pattern.compile("[0-9]+");

	private ReportHistoryParameters() {
	}

	static ReportHistoryQuery parse(String integrationId, MultiValueMap<String, String> parameters) {
		Map<String, List<String>> errors = new LinkedHashMap<>();
		UUID integration = null;
		if (UUID_FORMAT.matcher(integrationId).matches()) {
			integration = UUID.fromString(integrationId);
		} else {
			invalid(errors, "integrationId", "Требуется UUID в стандартной строковой записи.");
		}
		Instant from = time(single(parameters, "from", errors), "from", errors);
		Instant to = time(single(parameters, "to", errors), "to", errors);
		if (from != null && to != null && !from.isBefore(to)) {
			invalid(errors, "to", "Верхняя граница должна быть позже нижней.");
		}
		String outcome = outcome(single(parameters, "status", errors), errors);
		List<UUID> contractIds = contracts(parameters, errors);
		int offset = integer(single(parameters, "offset", errors), "offset", 0, 0, Integer.MAX_VALUE, errors);
		int limit = integer(single(parameters, "limit", errors), "limit", 20, 1, 100, errors);
		if (!errors.isEmpty()) {
			throw new ReportHistoryRequestException(errors);
		}
		return new ReportHistoryQuery(integration, from, to, outcome, contractIds, offset, limit);
	}

	private static List<UUID> contracts(MultiValueMap<String, String> parameters,
			Map<String, List<String>> errors) {
		List<String> values = parameters.get("contractId");
		if (values == null) {
			return List.of();
		}
		if (values.isEmpty() || values.size() > 50) {
			invalid(errors, "contractId", "Требуется от 1 до 50 вхождений contractId до удаления повторов.");
			return List.of();
		}
		var ids = new LinkedHashSet<UUID>();
		for (String value : values) {
			if (value == null || !UUID_FORMAT.matcher(value).matches()) {
				invalid(errors, "contractId", "Каждое значение должно быть UUID в стандартной строковой записи.");
				return List.of();
			}
			ids.add(UUID.fromString(value));
		}
		return List.copyOf(ids);
	}

	private static String single(MultiValueMap<String, String> parameters, String name,
			Map<String, List<String>> errors) {
		List<String> values = parameters.get(name);
		if (values == null) {
			return null;
		}
		if (values.size() != 1 || values.getFirst() == null || values.getFirst().isEmpty()) {
			invalid(errors, name, "Параметр должен содержать одно непустое значение.");
			return null;
		}
		return values.getFirst();
	}

	private static Instant time(String value, String name, Map<String, List<String>> errors) {
		if (value == null) {
			return null;
		}
		if (UTC_TIME.matcher(value).matches()) {
			try {
				OffsetDateTime time = OffsetDateTime.parse(value);
				if (time.getYear() >= 1) {
					return time.toInstant();
				}
			} catch (DateTimeParseException ignored) {
				// Проверка календаря не допускает нормализацию некорректной даты.
			}
		}
		invalid(errors, name, "Требуется корректное время UTC с Z, годом 0001-9999 и дробью до 6 знаков.");
		return null;
	}

	private static String outcome(String value, Map<String, List<String>> errors) {
		return switch (value) {
			case null -> null;
			case "Passed" -> "PASSED";
			case "Failed" -> "FAILED";
			default -> {
				invalid(errors, "status", "Допустимы только Passed или Failed.");
				yield null;
			}
		};
	}

	private static int integer(String value, String name, int defaultValue, int min, int max,
			Map<String, List<String>> errors) {
		if (value == null) {
			return defaultValue;
		}
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
		invalid(errors, name, "Требуется целое без знака и пробелов в диапазоне " + min + ".." + max + ".");
		return defaultValue;
	}

	private static void invalid(Map<String, List<String>> errors, String name, String message) {
		errors.put(name, List.of(message));
	}
}
