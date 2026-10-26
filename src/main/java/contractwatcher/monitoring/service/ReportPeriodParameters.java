package contractwatcher.monitoring.service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.util.MultiValueMap;

final class ReportPeriodParameters {

	private static final Pattern UTC_TIME = Pattern.compile(
			"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-5][0-9](\\.[0-9]{1,6})?Z");

	private ReportPeriodParameters() {
	}

	static Period parse(MultiValueMap<String, String> parameters, Map<String, List<String>> errors) {
		Instant from = time(parameters, "from", errors);
		Instant to = time(parameters, "to", errors);
		if (from != null && to != null && !from.isBefore(to)) {
			errors.put("to", List.of("Верхняя граница должна быть позже нижней."));
		}
		return new Period(from, to);
	}

	private static Instant time(MultiValueMap<String, String> parameters, String name,
			Map<String, List<String>> errors) {
		List<String> values = parameters.get(name);
		if (values == null) {
			return null;
		}
		if (values.size() != 1 || values.getFirst() == null || values.getFirst().isEmpty()) {
			errors.put(name, List.of("Параметр должен содержать одно непустое значение."));
			return null;
		}
		String value = values.getFirst();
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
		errors.put(name, List.of("Требуется корректное время UTC с Z, годом 0001-9999 и дробью до 6 знаков."));
		return null;
	}

	record Period(Instant from, Instant to) {
	}
}
