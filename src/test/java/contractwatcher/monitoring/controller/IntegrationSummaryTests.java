package contractwatcher.monitoring.controller;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import contractwatcher.monitoring.dto.IntegrationSummary;
import contractwatcher.monitoring.exception.IntegrationSummaryRequestException;
import contractwatcher.monitoring.repository.ReportRepository;
import contractwatcher.monitoring.service.IntegrationSummaryService;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

class IntegrationSummaryTests {

	private static final String ID = "22222222-2222-4222-8222-222222222222";
	private static final String PATH = "/internal/v1/integrations/" + ID + "/summary";

	@Test
	void preservesExactInt64AndExplicitNull() throws Exception {
		var service = mock(IntegrationSummaryService.class);
		var mvc = MockMvcBuilders.standaloneSetup(new IntegrationSummaryController(service)).build();
		when(service.summary(ID)).thenReturn(new IntegrationSummary(UUID.fromString(ID), Long.MAX_VALUE,
				9007199254740993L, 9214364837600034814L, Instant.parse("2026-10-19T09:00:00.123456Z")));
		var response = mvc.perform(get(PATH)).andReturn().getResponse();
		assertThat(response.getStatus()).isEqualTo(200);
		assertThat(response.getContentType()).isEqualTo("application/json");
		assertThat(response.getContentAsString()).contains("\"totalReports\":9223372036854775807",
				"\"passedReports\":9007199254740993", "\"failedReports\":9214364837600034814", "2026-10-19T09:00:00.123456Z");
		assertThat(new JsonMapper().readTree(response.getContentAsString()).path("totalReports").asLong()).isEqualTo(Long.MAX_VALUE);
		when(service.summary(ID)).thenReturn(new IntegrationSummary(UUID.fromString(ID), 0, 0, 0, null));
		assertThat(mvc.perform(get(PATH)).andReturn().getResponse().getContentAsString()).contains("\"lastReceivedAt\":null");
	}

	@Test
	void returnsSafeTechnicalProblemsWithSummaryDetail() throws Exception {
		var service = mock(IntegrationSummaryService.class);
		var mvc = MockMvcBuilders.standaloneSetup(new IntegrationSummaryController(service))
				.setControllerAdvice(new ReportExceptionHandler()).build();
		for (boolean unavailable : List.of(true, false)) {
			RuntimeException failure = unavailable ? new DataAccessResourceFailureException("driver-detail")
					: new IllegalStateException("driver-detail");
			doThrow(failure).when(service).summary(ID);
			var response = mvc.perform(get(PATH + "?ignored=sample")).andReturn().getResponse();
			int status = unavailable ? 503 : 500;
			assertThat(response.getStatus()).isEqualTo(status);
			assertThat(response.getContentType()).isEqualTo("application/problem+json");
			var body = new JsonMapper().readTree(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
			assertThat(body.size()).isEqualTo(7);
			assertThat(body.path("status").asInt()).isEqualTo(status);
			assertThat(body.path("instance").asString()).isEqualTo(PATH);
			assertThat(body.path("errors").isObject()).isTrue();
			assertThat(body.path("errors").size()).isZero();
			assertThat(body.path("detail").asString()).isEqualTo(unavailable
					? "Хранилище отчетов временно недоступно." : "Не удалось прочитать сводку интеграции.");
			assertThat(body.path("code").asString()).isEqualTo(unavailable ? "STORAGE_UNAVAILABLE" : "INTERNAL_ERROR");
			assertThat(response.getContentAsString()).doesNotContain("driver-detail", "Exception", "jdbc:");
		}
	}

	@Test
	void rejectsNonCanonicalUuidBeforeRepository() {
		var repository = mock(ReportRepository.class);
		var service = new IntegrationSummaryService(repository);
		for (String value : List.of("bad", "1-1-1-1-1", " " + ID, "{" + ID + "}")) {
			assertThatThrownBy(() -> service.summary(value)).isInstanceOf(IntegrationSummaryRequestException.class);
		}
		verifyNoInteractions(repository);
	}
}
