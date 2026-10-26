package contractwatcher.monitoring.service;

import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;

import contractwatcher.monitoring.dto.IntegrationSummary;
import contractwatcher.monitoring.repository.ReportRepository;

@Service
public final class IntegrationSummaryService {

	private final ReportRepository reports;

	public IntegrationSummaryService(ReportRepository reports) {
		this.reports = reports;
	}

	public IntegrationSummary summary(String integrationId, MultiValueMap<String, String> parameters) {
		return reports.summary(IntegrationSummaryParameters.parse(integrationId, parameters));
	}
}
