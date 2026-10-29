package contractwatcher.monitoring.controller;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import contractwatcher.monitoring.config.ApiExamples;
import contractwatcher.monitoring.service.ReportIngestionService;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class ReportJsonErrorsTests {
	static Stream<String> malformed() {
		String valid = ApiExamples.PASSED_REQUEST;
		return Stream.of("", "{", "1", "[]", valid.replace("\"Passed\"", "1"),
				valid.replace("\"Passed\"", "\"unknown\""), valid.replace("\"contractVersion\":3", "\"contractVersion\":2147483648"),
				valid.replace("\"contractVersion\":3", "\"contractVersion\":1.1"),
				ApiExamples.REQUEST.replace("\"TypeMismatch\"", "42"));
	}

	@ParameterizedTest
	@org.junit.jupiter.params.provider.ValueSource(ints = {1, Integer.MAX_VALUE})
	void acceptsIntegerVersionBoundaries(int version) throws Exception {
		var service = mock(ReportIngestionService.class);
		try (var context = new AnnotationConfigApplicationContext(Class.forName("contractwatcher.monitoring.config.ReportJsonConfiguration"))) {
			var builder = JsonMapper.builder(); context.getBean(JsonMapperBuilderCustomizer.class).customize(builder);
			var mvc = MockMvcBuilders.standaloneSetup(new ReportIngestionController(service))
					.setMessageConverters(new JacksonJsonHttpMessageConverter(builder.build())).build();
			when(service.ingest(any())).thenReturn(new contractwatcher.monitoring.dto.ReportAccepted(
					java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), java.time.Instant.parse("2026-10-19T00:00:00Z"), false));
			assertThat(mvc.perform(post("/internal/v1/reports").contentType("application/json")
					.content(ApiExamples.PASSED_REQUEST.replace("\"contractVersion\":3", "\"contractVersion\":" + version)))
					.andReturn().getResponse().getStatus()).isEqualTo(201);
			verify(service).ingest(argThat(report -> report.report().contractVersion() == version));
		}
	}

	@ParameterizedTest
	@MethodSource("malformed")
	void productionMapperRejectsInvalidJsonBeforeService(String body) throws Exception {
		var service = mock(ReportIngestionService.class);
		try (var context = new AnnotationConfigApplicationContext(Class.forName("contractwatcher.monitoring.config.ReportJsonConfiguration"))) {
			var builder = JsonMapper.builder().addMixIn(org.springframework.http.ProblemDetail.class,
					org.springframework.http.converter.json.ProblemDetailJacksonMixin.class);
			context.getBean(JsonMapperBuilderCustomizer.class).customize(builder);
			var mvc = MockMvcBuilders.standaloneSetup(new ReportIngestionController(service))
					.setMessageConverters(new JacksonJsonHttpMessageConverter(builder.build()))
					.setControllerAdvice(new ReportExceptionHandler()).build();
			var response = mvc.perform(post("/internal/v1/reports").contentType("application/json").content(body)).andReturn().getResponse();
			assertThat(response.getStatus()).isEqualTo(400);
			assertThat(response.getContentType()).isEqualTo("application/problem+json");
			var problem = new JsonMapper().readTree(response.getContentAsString());
			assertThat(problem.path("code").asString()).isEqualTo("INVALID_REQUEST");
			assertThat(problem.path("errors").isEmpty()).isTrue();
			assertThat(problem.size()).isEqualTo(7);
			verifyNoInteractions(service);
		}
	}
}
