package contractwatcher.monitoring.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.util.LinkedMultiValueMap;

import contractwatcher.monitoring.exception.IntegrationSummaryRequestException;
import contractwatcher.monitoring.exception.ReportHistoryRequestException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class ReportPeriodParametersTests {

	private static final String ID = "22222222-2222-4222-8222-222222222222";

	@ParameterizedTest
	@ValueSource(strings = {"2026-02-30T00:00:00Z", "2026-02-29T00:00:00Z", "0000-01-01T00:00:00Z",
			"10000-01-01T00:00:00Z", "2026-10-26T24:00:00Z", "2026-10-26T00:00:60Z", "2026-10-26",
			"2026-10-26T00:00:00", "2026-10-26T00:00:00z", "2026-10-26T00:00:00+00:00",
			"2026-10-26T00:00:00.1234567Z", " 2026-10-26T00:00:00Z", "2026-10-26T00:00:00Z ", ""})
	void rejectsSameInvalidTimeInHistoryAndSummary(String value) {
		for (String field : List.of("from", "to")) {
			var parameters = new LinkedMultiValueMap<String, String>();
			parameters.add(field, value);
			assertSameErrors(parameters, field);
		}
	}

	@Test
	void rejectsEmptyNullRepeatedAndReversedBoundsEqually() {
		for (String field : List.of("from", "to")) {
			for (List<String> values : List.of(List.<String>of(), java.util.Arrays.<String>asList((String) null),
					List.of("2026-10-26T00:00:00Z", "2026-10-26T00:00:00Z"))) {
				var parameters = new LinkedMultiValueMap<String, String>();
				parameters.put(field, values);
				assertSameErrors(parameters, field);
			}
		}
		for (String to : List.of("2026-10-26T00:00:00Z", "2026-10-25T00:00:00Z")) {
			var parameters = new LinkedMultiValueMap<String, String>();
			parameters.add("from", "2026-10-26T00:00:00Z");
			parameters.add("to", to);
			assertSameErrors(parameters, "to");
		}
	}

	@Test
	void preservesValidCalendarPrecisionOpenBoundsAndHistoryFilters() {
		for (String time : List.of("0001-01-01T00:00:00Z", "9999-12-31T23:59:59.999999Z",
				"2028-02-29T00:00:00.1Z", "2028-02-29T00:00:00.123456Z")) {
			for (String field : List.of("from", "to")) {
				var parameters = new LinkedMultiValueMap<String, String>();
				parameters.add(field, time);
				parameters.add("status", "Failed");
				parameters.add("contractId", ID);
				parameters.add("offset", "2");
				parameters.add("limit", "1");
				var summary = IntegrationSummaryParameters.parse(ID, parameters);
				var history = ReportHistoryParameters.parse(ID, parameters);
				assertThat(summary.from()).isEqualTo(history.from());
				assertThat(summary.to()).isEqualTo(history.to());
				assertThat(field.equals("from") ? summary.from() : summary.to()).isEqualTo(Instant.parse(time));
				assertThat(field.equals("from") ? summary.to() : summary.from()).isNull();
				assertThat(history.contractIds()).containsExactly(java.util.UUID.fromString(ID));
				assertThat(history.outcome()).isEqualTo("FAILED");
				assertThat(history.offset()).isEqualTo(2);
				assertThat(history.limit()).isEqualTo(1);
			}
		}
		assertThat(IntegrationSummaryParameters.parse(ID, new LinkedMultiValueMap<>()).from()).isNull();
		assertThat(IntegrationSummaryParameters.parse(ID, new LinkedMultiValueMap<>()).to()).isNull();
	}

	private static void assertSameErrors(LinkedMultiValueMap<String, String> parameters, String field) {
		var summary = catchThrowableOfType(IntegrationSummaryRequestException.class,
				() -> IntegrationSummaryParameters.parse(ID, parameters));
		var history = catchThrowableOfType(ReportHistoryRequestException.class,
				() -> ReportHistoryParameters.parse(ID, parameters));
		assertThat(summary.errors()).containsOnlyKeys(field).isEqualTo(history.errors());
		assertThat(summary.errors()).isNotEqualTo(Map.of());
	}
}
