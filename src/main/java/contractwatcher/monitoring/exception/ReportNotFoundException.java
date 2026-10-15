package contractwatcher.monitoring.exception;

public final class ReportNotFoundException extends RuntimeException {

	public ReportNotFoundException() {
		super("Отчет не найден в указанной интеграции.");
	}
}
