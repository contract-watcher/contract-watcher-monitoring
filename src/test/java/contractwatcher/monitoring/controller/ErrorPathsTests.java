package contractwatcher.monitoring.controller;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import java.util.List;
import java.util.stream.Stream;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.Level;
import ch.qos.logback.core.read.ListAppender;
import ch.qos.logback.classic.spi.ILoggingEvent;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import contractwatcher.monitoring.service.*;
import contractwatcher.monitoring.config.ApiExamples;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class ErrorPathsTests {
	private static final String ID = "22222222-2222-4222-8222-222222222222";
	private static final String ROOT = "/internal/v1/integrations/" + ID;
	private static final String MARKER = "private-exception-marker";

	static Stream<org.junit.jupiter.params.provider.Arguments> failures() {
		var transaction = new TransactionSystemException(MARKER, new SQLException(MARKER, "23514"));
		transaction.initApplicationException(new SQLTimeoutException(MARKER));
		return Stream.of(
				org.junit.jupiter.params.provider.Arguments.of(new IllegalStateException(MARKER), 500, false),
				org.junit.jupiter.params.provider.Arguments.of(new SQLException(MARKER, "23514"), 500, false),
				org.junit.jupiter.params.provider.Arguments.of(new SQLException(MARKER, "57014"), 500, false),
				org.junit.jupiter.params.provider.Arguments.of(new QueryTimeoutException(MARKER), 503, true),
				org.junit.jupiter.params.provider.Arguments.of(new SQLTimeoutException(MARKER), 503, true),
				org.junit.jupiter.params.provider.Arguments.of(new SQLTransientConnectionException(MARKER), 503, true),
				org.junit.jupiter.params.provider.Arguments.of(transaction, 503, true),
				org.junit.jupiter.params.provider.Arguments.of(new DataAccessResourceFailureException(MARKER), 503, false),
				org.junit.jupiter.params.provider.Arguments.of(new SQLException(MARKER, "08006"), 503, false),
				org.junit.jupiter.params.provider.Arguments.of(new SQLException(MARKER, "57P01"), 503, false));
	}

	@ParameterizedTest
	@MethodSource("failures")
	void classifiesCausesAndLogsOneSafeErrorForEveryOperation(Exception failure, int status, boolean timeout) throws Exception {
		boolean unavailable = status == 503;
		for (int operation = 0; operation < 7; operation++) {
			var history = mock(ReportHistoryService.class);
			var details = mock(ReportDetailsService.class);
			var groups = mock(ViolationGroupsService.class);
			var summary = mock(IntegrationSummaryService.class);
			var bulk = mock(ProjectSummariesService.class);
			var ingest = mock(ReportIngestionService.class);
			// Answer позволяет передать JDBC-причину через границу сервиса.
			doAnswer(call -> { throw failure; }).when(history).history(any(), any());
			doAnswer(call -> { throw failure; }).when(details).details(any(), any());
			doAnswer(call -> { throw failure; }).when(groups).list(any(), any());
			doAnswer(call -> { throw failure; }).when(groups).details(any(), any());
			doAnswer(call -> { throw failure; }).when(summary).summary(any(), any());
			doAnswer(call -> { throw failure; }).when(bulk).query(any());
			doAnswer(call -> { throw failure; }).when(ingest).ingest(any());
			var mvc = MockMvcBuilders.standaloneSetup(new ReportHistoryController(history), new ReportDetailsController(details),
					new ViolationGroupsController(groups), new IntegrationSummaryController(summary),
					new ProjectSummariesController(bulk), new ReportIngestionController(ingest))
					.setControllerAdvice(new ReportExceptionHandler()).build();
			List<MockHttpServletRequestBuilder> requests = List.of(get(ROOT + "/reports"), get(ROOT + "/reports/" + ID),
					get(ROOT + "/violation-groups"), get(ROOT + "/violation-groups/" + ID), get(ROOT + "/summary"),
					post(ProjectSummariesController.PATH).contentType("application/json").content("{\"integrationIds\":[]}"),
					post("/internal/v1/reports").contentType("application/json").content(ApiExamples.REQUEST.replace("sample_field", "private-body-marker")));
			var logger = (Logger) LoggerFactory.getLogger(ReportExceptionHandler.class);
			var appender = new ListAppender<ILoggingEvent>(); appender.start(); logger.addAppender(appender);
			try {
				var response = mvc.perform(operation < 5 ? requests.get(operation).queryParam("ignored", "private-query-marker") : requests.get(operation)).andReturn().getResponse();
				assertThat(response.getStatus()).isEqualTo(status);
				assertThat(response.getContentType()).isEqualTo("application/problem+json");
				var body = new JsonMapper().readTree(response.getContentAsString());
				assertThat(body.propertyNames()).containsExactlyInAnyOrder("type", "title", "status", "detail", "instance", "code", "errors");
				assertThat(body.path("status").asInt()).isEqualTo(status);
				assertThat(body.path("code").asString()).isEqualTo(unavailable ? "STORAGE_UNAVAILABLE" : "INTERNAL_ERROR");
				assertThat(body.path("errors").isEmpty()).isTrue();
				assertThat(body.path("type").asString()).isEqualTo("about:blank");
				assertThat(body.path("title").asString()).isEqualTo(org.springframework.http.HttpStatus.valueOf(status).getReasonPhrase());
				assertThat(body.path("instance").asString()).isEqualTo(requests.get(operation).buildRequest(new org.springframework.mock.web.MockServletContext()).getRequestURI());
				if (unavailable) { assertThat(body.path("detail").asString()).isEqualTo("Хранилище отчетов временно недоступно."); }
				if (!unavailable) { assertThat(body.path("detail").asString()).isEqualTo(List.of("Не удалось прочитать историю.",
						"Не удалось прочитать отчет.", "Не удалось прочитать группы нарушений.", "Не удалось прочитать группу нарушений.",
						"Не удалось прочитать сводку интеграции.", "Не удалось прочитать сводки интеграций.", "Не удалось обработать отчет.").get(operation)); }
				assertThat(response.getContentAsString()).doesNotContain(MARKER, "private-body-marker", "private-query-marker", "SQLException", "jdbc:");
				assertThat(appender.list).hasSize(1);
				var event = appender.list.getFirst();
				assertThat(event.getLevel()).isEqualTo(Level.ERROR);
				assertThat(event.getFormattedMessage()).contains("status=" + status, "code=", "timeout=" + timeout);
				if (operation < 5) { assertThat(event.getFormattedMessage()).contains("integrationId=" + ID); }
				assertThat(event.getFormattedMessage()).doesNotContain(MARKER, "private-body-marker", "private-query-marker", "jdbc:");
				assertThat(event.getThrowableProxy()).isNull();
				assertThat(event.getMDCPropertyMap()).isEmpty();
				assertThat(java.util.Arrays.toString(event.getArgumentArray())).doesNotContain(MARKER, "private-query-marker");
			} finally { logger.detachAppender(appender); appender.stop(); }
		}
	}

	@Test
	void causeCycleTerminatesInsideClassifier() {
		var cycle = new IllegalStateException(MARKER);
		cycle.initCause(new IllegalArgumentException(MARKER, cycle));
		org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> {
			assertThat(new ReportExceptionHandler().processingFailure(cycle, new org.springframework.mock.web.MockHttpServletRequest()).getStatusCode().value()).isEqualTo(500);
		});
	}

	@Test
	void validationAndMissingObjectsUseSafeLevelsAndIds() throws Exception {
		var service = mock(ReportDetailsService.class);
		var mvc = MockMvcBuilders.standaloneSetup(new ReportDetailsController(service)).setControllerAdvice(new ReportExceptionHandler()).build();
		var logger = (Logger) LoggerFactory.getLogger(ReportExceptionHandler.class);
		var appender = new ListAppender<ILoggingEvent>(); appender.start(); logger.addAppender(appender);
		try {
			doThrow(new contractwatcher.monitoring.exception.ReportDetailsRequestException(java.util.Map.of("reportId", List.of("Требуется UUID."))))
					.when(service).details(any(), any());
			assertThat(mvc.perform(get(ROOT + "/reports/private-id-marker")).andReturn().getResponse().getStatus()).isEqualTo(400);
			assertThat(appender.list).hasSize(1);
			assertThat(appender.list.getFirst().getLevel()).isEqualTo(Level.WARN);
			assertThat(appender.list.getFirst().getFormattedMessage()).contains("reportId=null").doesNotContain("private-id-marker");
			appender.list.clear();
			doThrow(new contractwatcher.monitoring.exception.ReportNotFoundException()).when(service).details(any(), any());
			assertThat(mvc.perform(get(ROOT + "/reports/" + ID)).andReturn().getResponse().getStatus()).isEqualTo(404);
			assertThat(appender.list).hasSize(1);
			assertThat(appender.list.getFirst().getLevel()).isEqualTo(Level.INFO);
			assertThat(appender.list.getFirst().getFormattedMessage()).contains("reportId=" + ID);
		} finally { logger.detachAppender(appender); appender.stop(); }
	}
}
