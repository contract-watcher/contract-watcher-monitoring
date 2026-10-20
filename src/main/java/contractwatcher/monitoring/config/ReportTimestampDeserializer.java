package contractwatcher.monitoring.config;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.regex.Pattern;

import contractwatcher.monitoring.dto.ApiSchema;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.deser.std.StdDeserializer;

final class ReportTimestampDeserializer extends StdDeserializer<Instant> {

	private static final Pattern FORMAT = Pattern.compile(ApiSchema.REPORT_TIMESTAMP_PATTERN);

	ReportTimestampDeserializer() {
		super(Instant.class);
	}

	@Override
	public Instant deserialize(JsonParser parser, DeserializationContext context) {
		if (parser.hasToken(JsonToken.VALUE_STRING) && FORMAT.matcher(parser.getString()).matches()) {
			try {
				var timestamp = OffsetDateTime.parse(parser.getString());
				if (timestamp.getYear() >= 1 && timestamp.getYear() <= 9999) {
					return timestamp.toInstant();
				}
			} catch (DateTimeException ignored) {
				// Reject invalid dates instead of normalizing them.
			}
		}
		return context.reportInputMismatch(Instant.class, "Требуется строка ISO 8601 с датой, временем и часовым поясом.");
	}
}
