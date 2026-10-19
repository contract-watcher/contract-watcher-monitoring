package contractwatcher.monitoring.exception;

public final class ViolationGroupNotFoundException extends RuntimeException {

	public ViolationGroupNotFoundException() {
		super("Группа нарушений не найдена в указанной интеграции.");
	}
}
