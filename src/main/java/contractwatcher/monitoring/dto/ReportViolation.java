package contractwatcher.monitoring.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(requiredProperties = {"fieldName", "type", "expectedType"})
public record ReportViolation(@Schema(minLength = 1, description = "Точное имя, включая пробелы и регистр.") String fieldName,
		Kind type, JsonType expectedType,
		@Schema(nullable = true, description = "Для TypeMismatch обязателен и отличается от expectedType; "
				+ "для остальных видов отсутствует или null.") JsonType actualType) {

	public enum Kind {
		RequiredFieldMissing, NullNotAllowed, TypeMismatch
	}

	public enum JsonType {
		String, Number, Boolean, Object, Array
	}
}
