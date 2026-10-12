package contractwatcher.monitoring.reports;

public record ReportViolation(String fieldName, Kind type, JsonType expectedType, JsonType actualType) {

	public enum Kind {
		RequiredFieldMissing("REQUIRED_FIELD_MISSING"), NullNotAllowed("NULL_NOT_ALLOWED"),
		TypeMismatch("TYPE_MISMATCH");

		private final String databaseValue;

		Kind(String databaseValue) {
			this.databaseValue = databaseValue;
		}

		String databaseValue() {
			return databaseValue;
		}
	}

	public enum JsonType {
		String("string"), Number("number"), Boolean("boolean"), Object("object"), Array("array");

		private final String databaseValue;

		JsonType(String databaseValue) {
			this.databaseValue = databaseValue;
		}

		String databaseValue() {
			return databaseValue;
		}
	}
}
