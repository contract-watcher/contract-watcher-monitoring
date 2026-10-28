package contractwatcher.monitoring.service;

import java.util.List;
import org.springframework.stereotype.Service;
import contractwatcher.monitoring.dto.IntegrationSummariesPage;
import contractwatcher.monitoring.model.ProjectSummariesQuery;
import contractwatcher.monitoring.repository.ProjectSummariesRepository;

@Service
public class ProjectSummariesService {
	private final ProjectSummariesRepository repository;

	public ProjectSummariesService(ProjectSummariesRepository repository) {
		this.repository = repository;
	}

	public IntegrationSummariesPage query(ProjectSummariesQuery query) {
		return query.integrationIds().isEmpty() ? new IntegrationSummariesPage(List.of(), 0, query.offset(), query.limit())
				: repository.query(query);
	}
}
