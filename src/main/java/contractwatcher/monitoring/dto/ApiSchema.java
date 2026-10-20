package contractwatcher.monitoring.dto;

public final class ApiSchema {

	public static final String UUID_PATTERN = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";
	public static final String MAX_LONG = "9223372036854775807";
	public static final String RECEIVED_AT = "Время начала сохраняющей транзакции PostgreSQL, не время SDK или commit. При duplicate сохраняется.";
	public static final String UTC_PATTERN = "^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-5][0-9](\\.[0-9]{1,6})?Z$";

	private ApiSchema() {
	}
}
