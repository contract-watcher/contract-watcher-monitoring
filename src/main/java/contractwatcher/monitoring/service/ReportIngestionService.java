package contractwatcher.monitoring.service;

import java.util.Comparator;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import contractwatcher.monitoring.dto.ReportAccepted;
import contractwatcher.monitoring.dto.ReportRequest;
import contractwatcher.monitoring.mapper.ReportMapper;
import contractwatcher.monitoring.model.ReportToStore;
import contractwatcher.monitoring.model.ReportToStore.Violation;
import contractwatcher.monitoring.repository.ReportRepository;
import contractwatcher.monitoring.repository.ReportViolationRepository;
import contractwatcher.monitoring.repository.ViolationGroupRepository;

@Service
public final class ReportIngestionService {

	private static final Comparator<Violation> GROUP_LOCK_ORDER = Comparator.comparing(Violation::fieldName)
			.thenComparing(Violation::kind)
			.thenComparing(Violation::actualType, Comparator.nullsFirst(Comparator.naturalOrder()))
			.thenComparingInt(Violation::ordinal);

	private final ReportValidator validator;
	private final ReportRepository reports;
	private final ReportViolationRepository violations;
	private final ViolationGroupRepository groups;
	private final TransactionTemplate transaction;

	ReportIngestionService(ReportValidator validator, ReportRepository reports,
			ReportViolationRepository violations, ViolationGroupRepository groups,
			PlatformTransactionManager transactionManager) {
		this.validator = validator;
		this.reports = reports;
		this.violations = violations;
		this.groups = groups;
		this.transaction = new TransactionTemplate(transactionManager);
		this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
	}

	public ReportAccepted ingest(ReportRequest request) {
		validator.validate(request);
		ReportToStore report = ReportMapper.toStorage(request);
		return transaction.execute(status -> save(report));
	}

	private ReportAccepted save(ReportToStore report) {
		var inserted = reports.insertIfNew(report);
		if (inserted.isEmpty()) {
			var previous = reports.find(report.integrationId(), report.reportId());
			return new ReportAccepted(report.integrationId(), report.reportId(), previous.receivedAt(), true);
		}
		var stored = inserted.orElseThrow();
		// Общий порядок групп исключает противоположный захват блокировок; ordinal остается исходным.
		for (Violation violation : report.violations().stream().sorted(GROUP_LOCK_ORDER).toList()) {
			var groupId = groups.recordOccurrence(report, violation, stored.receivedAt());
			violations.insert(stored.rowId(), violation, groupId);
		}
		return new ReportAccepted(report.integrationId(), report.reportId(), stored.receivedAt(), false);
	}
}
