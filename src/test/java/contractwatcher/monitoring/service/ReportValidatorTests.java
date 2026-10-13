package contractwatcher.monitoring.service;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import contractwatcher.monitoring.dto.ReportRequest;
import contractwatcher.monitoring.dto.ReportViolation;
import contractwatcher.monitoring.dto.ReportViolation.JsonType;
import contractwatcher.monitoring.dto.ReportViolation.Kind;
import contractwatcher.monitoring.dto.ValidationReport;
import contractwatcher.monitoring.dto.ValidationReport.Status;
import contractwatcher.monitoring.exception.ReportValidationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class ReportValidatorTests {

	private final ReportValidator validator = new ReportValidator();

	@Test
	void collectsMissingEnvelopeFieldsWithoutDereferencingReport() {
		assertThatExceptionOfType(ReportValidationException.class)
				.isThrownBy(() -> validator.validate(new ReportRequest(null, null, null)))
				.satisfies(error -> assertThat(error.errors()).containsOnlyKeys("integrationId", "contractId", "report"));
	}

	@Test
	void collectsInvalidMetadataAndNullViolationWithOriginalPaths() {
		var invalid = new ReportViolation("", Kind.TypeMismatch, null, null);
		var report = new ValidationReport("1-1-1-1-1", 0, Status.Failed, Arrays.asList(null, invalid));

		assertThatExceptionOfType(ReportValidationException.class)
				.isThrownBy(() -> validator.validate(envelope(report)))
				.satisfies(error -> assertThat(error.errors()).containsOnlyKeys(
						"report.reportId", "report.contractVersion", "report.violations[0]",
						"report.violations[1].fieldName", "report.violations[1].expectedType",
						"report.violations[1].actualType"));
	}

	@Test
	void rejectsStatusAndViolationInconsistency() {
		var missing = new ReportViolation("field", Kind.RequiredFieldMissing, JsonType.String, null);
		for (var report : List.of(
				new ValidationReport(UUID.randomUUID().toString(), 1, Status.Passed, List.of(missing)),
				new ValidationReport(UUID.randomUUID().toString(), 1, Status.Failed, List.of()))) {
			assertThatExceptionOfType(ReportValidationException.class)
					.isThrownBy(() -> validator.validate(envelope(report)))
					.satisfies(error -> assertThat(error.errors()).containsOnlyKeys("report.violations"));
		}
	}

	@Test
	void acceptsExactWhitespaceFieldNamesAndRepeatedViolations() {
		var missing = new ReportViolation(" ", Kind.RequiredFieldMissing, JsonType.String, null);
		var report = new ValidationReport(UUID.randomUUID().toString(), 1, Status.Failed, List.of(missing, missing));

		assertThatCode(() -> validator.validate(envelope(report))).doesNotThrowAnyException();
	}

	private ReportRequest envelope(ValidationReport report) {
		return new ReportRequest(UUID.randomUUID().toString(), UUID.randomUUID().toString(), report);
	}
}
