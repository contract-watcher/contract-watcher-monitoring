package contractwatcher.monitoring.mapper;

import java.util.List;
import java.util.UUID;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import contractwatcher.monitoring.dto.ReportRequest;
import contractwatcher.monitoring.dto.ReportViolation;
import contractwatcher.monitoring.dto.ReportViolation.JsonType;
import contractwatcher.monitoring.dto.ReportViolation.Kind;
import contractwatcher.monitoring.dto.ValidationReport;
import contractwatcher.monitoring.dto.ValidationReport.Status;
import contractwatcher.monitoring.model.ReportToStore.Violation;

import static org.assertj.core.api.Assertions.assertThat;

class ReportMapperTests {

	@Test
	void mapsPassedWithIdentifiersAndNoViolations() {
		UUID integration = UUID.randomUUID();
		UUID contract = UUID.randomUUID();
		UUID reportId = UUID.randomUUID();
		var request = new ReportRequest(integration.toString(), contract.toString(),
				new ValidationReport(reportId.toString(), 3, Status.Passed, Instant.parse("2026-10-20T10:00:00.1234567Z"), List.of()));

		var stored = ReportMapper.toStorage(request);

		assertThat(stored.integrationId()).isEqualTo(integration);
		assertThat(stored.contractId()).isEqualTo(contract);
		assertThat(stored.reportId()).isEqualTo(reportId);
		assertThat(stored.contractVersion()).isEqualTo(3);
		assertThat(stored.outcome()).isEqualTo("PASSED");
		assertThat(stored.timestamp()).isEqualTo(Instant.parse("2026-10-20T10:00:00.123456Z"));
		assertThat(stored.violations()).isEmpty();
	}

	@Test
	void mapsAllKindsAndJsonTypesToExistingStorageValues() {
		var input = List.of(
				new ReportViolation("missing", Kind.RequiredFieldMissing, JsonType.String, null),
				new ReportViolation("nullable", Kind.NullNotAllowed, JsonType.Boolean, null),
				new ReportViolation("string", Kind.TypeMismatch, JsonType.String, JsonType.Number),
				new ReportViolation("number", Kind.TypeMismatch, JsonType.Number, JsonType.String),
				new ReportViolation("boolean", Kind.TypeMismatch, JsonType.Boolean, JsonType.Object),
				new ReportViolation("object", Kind.TypeMismatch, JsonType.Object, JsonType.Array),
				new ReportViolation("array", Kind.TypeMismatch, JsonType.Array, JsonType.Boolean));

		var stored = ReportMapper.toStorage(failed(input));

		assertThat(stored.outcome()).isEqualTo("FAILED");
		assertThat(stored.violations()).extracting(Violation::kind).containsExactly(
				"REQUIRED_FIELD_MISSING", "NULL_NOT_ALLOWED", "TYPE_MISMATCH", "TYPE_MISMATCH",
				"TYPE_MISMATCH", "TYPE_MISMATCH", "TYPE_MISMATCH");
		assertThat(stored.violations()).extracting(Violation::expectedType)
				.containsExactly("string", "boolean", "string", "number", "boolean", "object", "array");
		assertThat(stored.violations()).extracting(Violation::actualType)
				.containsExactly(null, null, "number", "string", "object", "array", "boolean");
	}

	@Test
	void preservesExactFieldNamesDuplicatesAndOriginalOrdinals() {
		var missing = new ReportViolation(" Customer_id ", Kind.RequiredFieldMissing, JsonType.String, null);
		var mismatch = new ReportViolation("amount", Kind.TypeMismatch, JsonType.Number, JsonType.String);

		var stored = ReportMapper.toStorage(failed(List.of(missing, mismatch, missing)));

		assertThat(stored.violations()).containsExactly(
				new Violation(0, " Customer_id ", "REQUIRED_FIELD_MISSING", "string", null),
				new Violation(1, "amount", "TYPE_MISMATCH", "number", "string"),
				new Violation(2, " Customer_id ", "REQUIRED_FIELD_MISSING", "string", null));
	}

	private ReportRequest failed(List<ReportViolation> violations) {
		return new ReportRequest(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
				new ValidationReport(UUID.randomUUID().toString(), 3, Status.Failed, Instant.parse("2026-10-20T10:00:00Z"), violations));
	}
}
