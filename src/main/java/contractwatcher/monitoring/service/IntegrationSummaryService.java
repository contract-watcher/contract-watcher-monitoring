package contractwatcher.monitoring.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import contractwatcher.monitoring.dto.IntegrationSummary;
import contractwatcher.monitoring.exception.IntegrationSummaryRequestException;
import contractwatcher.monitoring.repository.ReportRepository;

@Service
public final class IntegrationSummaryService {

	private static final Pattern UUID_FORMAT = Pattern.compile(
			"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

	private final ReportRepository reports;

	public IntegrationSummaryService(ReportRepository reports) {
		this.reports = reports;
	}

	public IntegrationSummary summary(String integrationId) {
		if (!UUID_FORMAT.matcher(integrationId).matches()) {
			throw new IntegrationSummaryRequestException(Map.of(
					"integrationId", List.of("Требуется UUID в стандартной строковой записи.")));
		}
		return reports.summary(UUID.fromString(integrationId));
	}
}
