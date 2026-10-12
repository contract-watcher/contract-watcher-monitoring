package contractwatcher.monitoring.reports;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class ReportIngestionService {

	private final ReportValidator validator;
	private final ReportRepository repository;
	private final TransactionTemplate transaction;

	ReportIngestionService(ReportValidator validator, ReportRepository repository,
			PlatformTransactionManager transactionManager) {
		this.validator = validator;
		this.repository = repository;
		this.transaction = new TransactionTemplate(transactionManager);
		this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
	}

	ReportAccepted ingest(ReportRequest request) {
		validator.validate(request);
		return transaction.execute(status -> repository.save(request));
	}
}
