package contractwatcher.monitoring.dto;

public record ReportViolation(String fieldName, Kind type, JsonType expectedType, JsonType actualType) {

	public enum Kind {
		RequiredFieldMissing, NullNotAllowed, TypeMismatch
	}

	public enum JsonType {
		String, Number, Boolean, Object, Array
	}
}
