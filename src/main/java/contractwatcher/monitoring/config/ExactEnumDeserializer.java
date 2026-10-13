package contractwatcher.monitoring.config;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.deser.std.StdDeserializer;

final class ExactEnumDeserializer<T extends Enum<T>> extends StdDeserializer<T> {

	private final Class<T> enumType;

	ExactEnumDeserializer(Class<T> enumType) {
		super(enumType);
		this.enumType = enumType;
	}

	@Override
	public T deserialize(JsonParser parser, DeserializationContext context) {
		if (parser.hasToken(JsonToken.VALUE_STRING)) {
			try {
				return Enum.valueOf(enumType, parser.getString());
			} catch (IllegalArgumentException exception) {
				return context.reportInputMismatch(enumType, "Неизвестное строковое значение enum.");
			}
		}
		return context.reportInputMismatch(enumType, "Требуется строковое значение enum.");
	}
}
