package contractwatcher.monitoring.reports;

final class ReportIdConflictException extends RuntimeException {

	ReportIdConflictException() {
		super("Отчет с таким идентификатором уже сохранен в интеграции.");
	}
}
