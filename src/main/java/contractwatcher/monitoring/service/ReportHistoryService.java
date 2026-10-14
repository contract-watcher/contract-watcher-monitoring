package contractwatcher.monitoring.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.MultiValueMap;

import contractwatcher.monitoring.dto.ReportHistoryItem;
import contractwatcher.monitoring.dto.ReportHistoryPage;
import contractwatcher.monitoring.dto.ValidationReport;
import contractwatcher.monitoring.model.ReportHistoryEntry;
import contractwatcher.monitoring.repository.ReportRepository;

@Service
public final class ReportHistoryService {

	private final ReportRepository reports;
	private final TransactionTemplate transaction;

	ReportHistoryService(ReportRepository reports, PlatformTransactionManager transactionManager) {
		this.reports = reports;
		this.transaction = new TransactionTemplate(transactionManager);
		this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
		this.transaction.setReadOnly(true);
	}

	public ReportHistoryPage history(String integrationId, MultiValueMap<String, String> parameters) {
		var query = ReportHistoryParameters.parse(integrationId, parameters);
		return transaction.execute(status -> {
			long total = reports.countHistory(query);
			var items = reports.findHistory(query).stream().map(ReportHistoryService::item).toList();
			return new ReportHistoryPage(items, total, query.offset(), query.limit());
		});
	}

	private static ReportHistoryItem item(ReportHistoryEntry entry) {
		ValidationReport.Status status = switch (entry.outcome()) {
			case "PASSED" -> ValidationReport.Status.Passed;
			case "FAILED" -> ValidationReport.Status.Failed;
			default -> throw new IllegalStateException("Неизвестный сохраненный результат проверки.");
		};
		return new ReportHistoryItem(entry.reportId(), entry.contractId(), entry.contractVersion(), status,
				entry.receivedAt(), entry.violationCount());
	}
}
