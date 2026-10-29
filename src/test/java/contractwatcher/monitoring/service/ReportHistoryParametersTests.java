package contractwatcher.monitoring.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.util.LinkedMultiValueMap;

import contractwatcher.monitoring.exception.ReportHistoryRequestException;
import contractwatcher.monitoring.model.ReportHistoryQuery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportHistoryParametersTests {

	private static final String INTEGRATION = "22222222-2222-4222-8222-222222222222";
	private static final String CONTRACT = "abcdefab-cdef-4abc-8def-abcdefabcdef";

	@Test
	void absentFilterPreservesDefaultsAndUnknownParameters() {
		var parameters = new LinkedMultiValueMap<String, String>();
		parameters.add("ignored", "anything");
		var query = ReportHistoryParameters.parse(INTEGRATION, parameters);
		assertThat(query.contractIds()).isEmpty();
		assertThat(query.offset()).isZero();
		assertThat(query.limit()).isEqualTo(20);
	}

	@Test
	void limitsOccurrencesBeforeCaseInsensitiveDeduplicationAndCopiesModel() {
		var parameters = new LinkedMultiValueMap<String, String>();
		parameters.put("contractId", new ArrayList<>(java.util.Collections.nCopies(49, CONTRACT)));
		parameters.add("contractId", CONTRACT.toUpperCase(java.util.Locale.ROOT));
		var query = ReportHistoryParameters.parse(INTEGRATION, parameters);
		assertThat(query.contractIds()).containsExactly(UUID.fromString(CONTRACT));
		parameters.add("contractId", CONTRACT);
		assertInvalid(parameters);
		var mutable = new ArrayList<>(query.contractIds());
		var copy = new ReportHistoryQuery(query.integrationId(), null, null, null, mutable, 0, 20);
		mutable.clear();
		assertThat(copy.contractIds()).containsExactly(UUID.fromString(CONTRACT));
		assertThatThrownBy(() -> copy.contractIds().clear()).isInstanceOf(UnsupportedOperationException.class);
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {"null", "[]", "1-1-1-1-1", "private-query-marker", " abcdefab-cdef-4abc-8def-abcdefabcdef",
			"abcdefab-cdef-4abc-8def-abcdefabcdef ", "abcdefab-cdef-4abc-8def-abcdefabcdef,abcdefab-cdef-4abc-8def-abcdefabcdef"})
	void rejectsWholeListWithInvalidElement(String value) {
		var parameters = new LinkedMultiValueMap<String, String>();
		parameters.add("contractId", CONTRACT);
		parameters.add("contractId", value);
		assertInvalid(parameters);
	}

	@Test
	void rejectsExplicitEmptyList() {
		var parameters = new LinkedMultiValueMap<String, String>();
		parameters.put("contractId", List.of());
		assertInvalid(parameters);
	}

	@ParameterizedTest
	@ValueSource(strings = {"limit=0", "limit=101", "limit=+1", "limit= 1", "limit=2147483648", "offset=-1", "offset=2147483648", "offset=1&offset=2", "status=Passed&status=Failed"})
	void rejectsInvalidPaginationAndRepeatedScalars(String query) {
		var parameters = new LinkedMultiValueMap<String, String>();
		for (String pair : query.split("&")) { String[] parts = pair.split("=", 2); parameters.add(parts[0], parts[1]); }
		assertThatThrownBy(() -> ReportHistoryParameters.parse(INTEGRATION, parameters)).isInstanceOf(ReportHistoryRequestException.class);
	}

	@Test
	void acceptsMaximumAndLeadingZeroPagination() {
		var parameters = new LinkedMultiValueMap<String, String>(); parameters.add("limit", "0100"); parameters.add("offset", "2147483647");
		var query = ReportHistoryParameters.parse(INTEGRATION, parameters);
		assertThat(query.limit()).isEqualTo(100); assertThat(query.offset()).isEqualTo(Integer.MAX_VALUE);
	}

	private static void assertInvalid(LinkedMultiValueMap<String, String> parameters) {
		assertThatThrownBy(() -> ReportHistoryParameters.parse(INTEGRATION, parameters))
				.isInstanceOfSatisfying(ReportHistoryRequestException.class,
						error -> assertThat(error.errors()).containsOnlyKeys("contractId"));
	}
}
