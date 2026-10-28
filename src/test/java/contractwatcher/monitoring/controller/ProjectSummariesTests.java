package contractwatcher.monitoring.controller;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import contractwatcher.monitoring.dto.IntegrationSummariesPage;
import contractwatcher.monitoring.dto.IntegrationSummary;
import contractwatcher.monitoring.exception.IntegrationSummaryRequestException;
import contractwatcher.monitoring.exception.SummaryBodyTooLargeException;
import contractwatcher.monitoring.repository.ProjectSummariesRepository;
import contractwatcher.monitoring.service.ProjectSummariesParameters;
import contractwatcher.monitoring.service.ProjectSummariesService;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class ProjectSummariesTests {
	private static final String ID = "ffffffff-ffff-ffff-ffff-ffffffffffff";
	private static final String PATH = ProjectSummariesController.PATH;

	@Test
	void validatesAllFieldsBeforeEmptyShortcutAndDeduplicatesAfterInputLimit() throws Exception {
		var repository = mock(ProjectSummariesRepository.class);
		var service = new ProjectSummariesService(repository);
		var empty = parse("{\"integrationIds\":[]}");
		assertThat(service.query(empty)).isEqualTo(new IntegrationSummariesPage(List.of(), 0, 0, 20));
		assertThat(parse("{\"integrationIds\":[\"" + ID + "\",\"" + ID.toUpperCase() + "\"]}").integrationIds())
				.containsExactly(UUID.fromString(ID));
		assertThat(parse("{\"integrationIds\":[" + String.join(",", java.util.Collections.nCopies(10000, "\"" + ID + "\"")) + "]}")
				.integrationIds()).hasSize(1);
		assertThatThrownBy(() -> parse("{\"integrationIds\":[" + String.join(",", java.util.Collections.nCopies(10001, "\"" + ID + "\"")) + "]}"))
				.isInstanceOf(IntegrationSummaryRequestException.class);
		for (String body : List.of("", "[]", "{}", "{", "{\"integrationIds\":null}", "{\"integrationIds\":{}}",
				"{\"integrationIds\":[null]}", "{\"integrationIds\":[1]}", "{\"integrationIds\":[\"1-1-1-1-1\"]}",
				"{\"integrationIds\":[],\"extra\":1}", "{\"integrationIds\":[],\"integrationIds\":[]}",
				"{\"integrationIds\":[]} {}")) {
			assertThatThrownBy(() -> parse(body)).as(body).isInstanceOf(IntegrationSummaryRequestException.class);
		}
		for (String field : List.of("from", "to", "onlyFailed", "offset", "limit")) {
			assertThatThrownBy(() -> parse("{\"integrationIds\":[],\"" + field + "\":null}"))
					.isInstanceOf(IntegrationSummaryRequestException.class);
		}
		for (String value : List.of("false", "true", "1.0", "-1", "2147483648", "\"2\"")) {
			assertThatThrownBy(() -> parse("{\"integrationIds\":[],\"offset\":" + value + "}"))
					.isInstanceOf(IntegrationSummaryRequestException.class);
		}
		for (String field : List.of("\"limit\":0", "\"limit\":101", "\"onlyFailed\":1", "\"onlyFailed\":\"true\"")) {
			assertThatThrownBy(() -> parse("{\"integrationIds\":[]," + field + "}"))
					.isInstanceOf(IntegrationSummaryRequestException.class);
		}
		verifyNoInteractions(repository);
	}

	@Test
	void sharesExactPeriodRules() throws Exception {
		for (String value : List.of("", "2026-10-28T00:00Z", "2026-10-28T00:00:00+00:00", "2026-02-29T00:00:00Z",
				"0000-01-01T00:00:00Z", "10000-01-01T00:00:00Z", "2026-10-28T00:00:00.1234567Z")) {
			assertThatThrownBy(() -> parse("{\"integrationIds\":[],\"from\":\"" + value + "\"}"))
					.isInstanceOf(IntegrationSummaryRequestException.class);
		}
		for (String value : List.of("2026-10-28T00:00:00Z", "2026-10-28T00:00:00.1Z", "2026-10-28T00:00:00.123456Z")) {
			assertThat(parse("{\"integrationIds\":[],\"from\":\"" + value + "\"}").from()).isEqualTo(Instant.parse(value));
		}
		assertThatThrownBy(() -> parse("{\"integrationIds\":[],\"from\":\"2026-10-28T00:00:00Z\",\"to\":\"2026-10-28T00:00:00Z\"}"))
				.isInstanceOf(IntegrationSummaryRequestException.class);
	}

	@Test
	void boundsActuallyReadBytesAndAcceptsExactLimit() throws Exception {
		String body = "{\"integrationIds\":[]}";
		int max = ProjectSummariesParameters.MAX_BODY_BYTES;
		assertThat(parse(body + " ".repeat(max - body.length())).integrationIds()).isEmpty();
		AtomicInteger read = new AtomicInteger();
		InputStream endless = new InputStream() {
			@Override public int read() { read.incrementAndGet(); return ' '; }
		};
		assertThatThrownBy(() -> ProjectSummariesParameters.parse(endless, null)).isInstanceOf(SummaryBodyTooLargeException.class);
		assertThat(read.get()).isEqualTo(max + 1);
	}

	@Test
	void httpErrorsAreLocalSafeAndBounded() throws Exception {
		var service = mock(ProjectSummariesService.class);
		var mvc = MockMvcBuilders.standaloneSetup(new ProjectSummariesController(service))
				.setControllerAdvice(new ReportExceptionHandler()).build();
		for (var request : List.of(post(PATH).contentType("application/json").content("{}"),
				post(PATH + "?private-marker=value").contentType("application/json").content("{\"integrationIds\":[]}"))) {
			var response = mvc.perform(request).andReturn().getResponse();
			assertThat(response.getStatus()).isEqualTo(400);
			assertThat(response.getContentAsString()).doesNotContain("private-marker");
		}
		assertThat(mvc.perform(post(PATH).contentType("application/json").content(" ".repeat(524289)))
				.andReturn().getResponse().getStatus()).isEqualTo(413);
		assertThat(mvc.perform(post(PATH).contentType("application/json").header("Content-Encoding", "gzip").content("{}"))
				.andReturn().getResponse().getStatus()).isEqualTo(415);
		assertThat(mvc.perform(post(PATH).contentType("text/plain").content("{}"))
				.andReturn().getResponse().getStatus()).isEqualTo(415);
		verifyNoInteractions(service);
		for (var failure : List.of(new IllegalStateException("private-marker"), new DataAccessResourceFailureException("private-marker"))) {
			doThrow(failure).when(service).query(any());
			var response = mvc.perform(post(PATH).contentType("application/json").content("{\"integrationIds\":[]}"))
					.andReturn().getResponse();
			assertThat(response.getStatus()).isEqualTo(failure instanceof DataAccessResourceFailureException ? 503 : 500);
			assertThat(response.getContentType()).isEqualTo("application/problem+json");
			assertThat(response.getContentAsString(StandardCharsets.UTF_8)).doesNotContain("private-marker", "обработать отчет");
			assertThat(new JsonMapper().readTree(response.getContentAsString()).size()).isEqualTo(7);
		}
	}

	@Test
	@EnabledIfEnvironmentVariable(named = "MONITORING_TEST_DATABASE_URL", matches = ".+")
	void actualLostPostgresConnectionReturns503() throws Exception {
		String url = System.getenv("MONITORING_TEST_DATABASE_URL");
		try (var reader = java.sql.DriverManager.getConnection(url, System.getenv("MONITORING_TEST_DATABASE_USER"),
				System.getenv("MONITORING_TEST_DATABASE_PASSWORD"));
				var admin = java.sql.DriverManager.getConnection(url, System.getenv("MONITORING_TEST_DATABASE_USER"),
						System.getenv("MONITORING_TEST_DATABASE_PASSWORD"))) {
			var readerJdbc = new org.springframework.jdbc.core.JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(reader, true));
			int pid = readerJdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
			new org.springframework.jdbc.core.JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(admin, true))
					.queryForObject("SELECT pg_terminate_backend(?)", Boolean.class, pid);
			var service = new ProjectSummariesService(new ProjectSummariesRepository(readerJdbc));
			var mvc = MockMvcBuilders.standaloneSetup(new ProjectSummariesController(service))
					.setControllerAdvice(new ReportExceptionHandler()).build();
			var response = mvc.perform(post(PATH).contentType("application/json").content("{\"integrationIds\":[\"" + ID + "\"]}"))
					.andReturn().getResponse();
			assertThat(response.getStatus()).isEqualTo(503);
			assertThat(response.getContentAsString()).contains("STORAGE_UNAVAILABLE").doesNotContain("jdbc:", "org.postgresql", "SELECT");
		}
	}

	@Test
	void maximumPagePreservesInt64NullAndFits32KiB() throws Exception {
		var service = mock(ProjectSummariesService.class);
		var mvc = MockMvcBuilders.standaloneSetup(new ProjectSummariesController(service)).build();
		var max = new IntegrationSummary(UUID.fromString(ID), Long.MAX_VALUE, 9007199254740993L,
				Long.MAX_VALUE - 9007199254740993L, Instant.parse("9999-12-31T23:59:59.999999Z"));
		var items = new java.util.ArrayList<>(java.util.Collections.nCopies(99, max));
		items.add(new IntegrationSummary(UUID.fromString(ID), 0, 0, 0, null));
		when(service.query(any())).thenReturn(new IntegrationSummariesPage(items, 10000, Integer.MAX_VALUE, 100));
		byte[] body = mvc.perform(post(PATH).contentType("application/json").content("{\"integrationIds\":[]}"))
				.andReturn().getResponse().getContentAsByteArray();
		assertThat(body.length).isLessThanOrEqualTo(32768);
		assertThat(new String(body, StandardCharsets.UTF_8)).contains("9007199254740993", "9223372036854775807", "\"lastReceivedAt\":null");
	}

	private static contractwatcher.monitoring.model.ProjectSummariesQuery parse(String body) throws Exception {
		return ProjectSummariesParameters.parse(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)), null);
	}
}
