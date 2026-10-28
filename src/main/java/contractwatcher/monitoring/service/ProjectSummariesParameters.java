package contractwatcher.monitoring.service;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.util.LinkedMultiValueMap;

import contractwatcher.monitoring.dto.ApiSchema;
import contractwatcher.monitoring.exception.IntegrationSummaryRequestException;
import contractwatcher.monitoring.exception.SummaryBodyTooLargeException;
import contractwatcher.monitoring.model.ProjectSummariesQuery;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class ProjectSummariesParameters {

	public static final int MAX_BODY_BYTES = 524288;
	private static final Pattern UUID_TEXT = Pattern.compile(ApiSchema.UUID_PATTERN);
	private static final Set<String> FIELDS = Set.of("integrationIds", "from", "to", "onlyFailed", "offset", "limit");
	private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
			.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

	private ProjectSummariesParameters() {
	}

	public static ProjectSummariesQuery parse(InputStream input, String queryString) throws IOException {
		// Читаем не более первого лишнего байта, в том числе без Content-Length.
		byte[] body = input.readNBytes(MAX_BODY_BYTES + 1);
		if (body.length > MAX_BODY_BYTES) {
			throw new SummaryBodyTooLargeException();
		}
		JsonNode root;
		try {
			root = JSON.readTree(body);
		} catch (JacksonException exception) {
			throw new IntegrationSummaryRequestException(Map.of());
		}
		if (root == null || !root.isObject() || (queryString != null && !queryString.isEmpty())
				|| !FIELDS.containsAll(root.propertyNames())) {
			throw new IntegrationSummaryRequestException(Map.of());
		}
		Map<String, List<String>> errors = new LinkedHashMap<>();
		var ids = identifiers(root.get("integrationIds"), errors);
		var periodValues = new LinkedMultiValueMap<String, String>();
		for (String name : List.of("from", "to")) {
			if (root.has(name)) {
				var value = root.get(name);
				periodValues.add(name, value.isString() ? value.asString() : null);
			}
		}
		var period = ReportPeriodParameters.parse(periodValues, errors);
		boolean onlyFailed = false;
		if (root.has("onlyFailed")) {
			if (!root.get("onlyFailed").isBoolean()) {
				errors.put("onlyFailed", List.of("Требуется JSON boolean."));
			} else {
				onlyFailed = root.get("onlyFailed").asBoolean();
			}
		}
		int offset = integer(root, "offset", 0, 0, Integer.MAX_VALUE, errors);
		int limit = integer(root, "limit", 20, 1, 100, errors);
		if (!errors.isEmpty()) {
			throw new IntegrationSummaryRequestException(errors);
		}
		return new ProjectSummariesQuery(ids, period.from(), period.to(), onlyFailed, offset, limit);
	}

	private static List<UUID> identifiers(JsonNode values, Map<String, List<String>> errors) {
		if (values == null || !values.isArray() || values.size() > 10000) {
			errors.put("integrationIds", List.of("Требуется массив от 0 до 10000 UUID до удаления повторов."));
			return List.of();
		}
		var ids = new LinkedHashSet<UUID>();
		for (var value : values) {
			if (!value.isString() || !UUID_TEXT.matcher(value.asString()).matches()) {
				errors.put("integrationIds", List.of("Каждый элемент должен быть UUID в стандартной строковой записи."));
				return List.of();
			}
			ids.add(UUID.fromString(value.asString()));
		}
		return List.copyOf(ids);
	}

	private static int integer(JsonNode root, String name, int fallback, int min, int max,
			Map<String, List<String>> errors) {
		if (!root.has(name)) {
			return fallback;
		}
		var value = root.get(name);
		if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < min || value.intValue() > max) {
			errors.put(name, List.of("Требуется целое число от " + min + " до " + max + "."));
			return fallback;
		}
		return value.intValue();
	}
}
