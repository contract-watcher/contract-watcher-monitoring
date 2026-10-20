package contractwatcher.monitoring.config;

public final class ApiExamples {

	public static final String REQUEST = """
			{"integrationId":"22222222-2222-4222-8222-222222222222",
			 "contractId":"33333333-3333-4333-8333-333333333333",
			 "report":{"reportId":"66666666-6666-4666-8666-666666666666","contractVersion":3,"status":"Failed",
			 "timestamp":"2026-10-19T09:00:00.123456Z",
			 "violations":[{"fieldName":"sample_field","type":"TypeMismatch","expectedType":"Number","actualType":"String"},
			 {"fieldName":"sample_field","type":"TypeMismatch","expectedType":"Number","actualType":"String"}]}}
			""";
	public static final String PASSED_REQUEST = """
			{"integrationId":"22222222-2222-4222-8222-222222222222",
			 "contractId":"33333333-3333-4333-8333-333333333333",
			 "report":{"reportId":"77777777-7777-4777-8777-777777777777","contractVersion":3,"status":"Passed",
			 "timestamp":"2026-10-19T09:01:00.123456Z","violations":[]}}
			""";
	public static final String ACCEPTED = """
			{"integrationId":"22222222-2222-4222-8222-222222222222","reportId":"66666666-6666-4666-8666-666666666666",
			 "receivedAt":"2026-10-19T09:00:00.123456Z","duplicate":false}
			""";
	public static final String DUPLICATE = """
			{"integrationId":"22222222-2222-4222-8222-222222222222","reportId":"66666666-6666-4666-8666-666666666666",
			 "receivedAt":"2026-10-19T09:00:00.123456Z","duplicate":true}
			""";
	public static final String HISTORY = """
			{"items":[{"reportId":"66666666-6666-4666-8666-666666666666","contractId":"33333333-3333-4333-8333-333333333333",
			 "contractVersion":3,"status":"Failed","receivedAt":"2026-10-19T09:00:00.123456Z","violationCount":2}],
			 "total":1,"offset":0,"limit":20}
			""";
	public static final String EMPTY_PAGE = """
			{"items":[],"total":0,"offset":0,"limit":20}
			""";
	public static final String OUTSIDE_PAGE = """
			{"items":[],"total":1,"offset":20,"limit":20}
			""";
	public static final String DETAILS = """
			{"integrationId":"22222222-2222-4222-8222-222222222222","reportId":"66666666-6666-4666-8666-666666666666",
			 "contractId":"33333333-3333-4333-8333-333333333333","contractVersion":3,"status":"Failed",
			 "receivedAt":"2026-10-19T09:00:00.123456Z","violationCount":2,
			 "violations":[{"groupId":"88888888-8888-4888-8888-888888888888","fieldName":"sample_field","type":"TypeMismatch",
			 "expectedType":"Number","actualType":"String"},
			 {"groupId":"88888888-8888-4888-8888-888888888888","fieldName":"sample_field","type":"TypeMismatch",
			 "expectedType":"Number","actualType":"String"}]}
			""";
	public static final String GROUP = """
			{"groupId":"88888888-8888-4888-8888-888888888888","contractId":"33333333-3333-4333-8333-333333333333",
			 "contractVersion":3,"fieldName":"sample_field","type":"TypeMismatch","expectedType":"Number","actualType":"String",
			 "occurrenceCount":2,"firstSeenAt":"2026-10-19T09:00:00.123456Z","lastSeenAt":"2026-10-19T09:00:00.123456Z"}
			""";
	public static final String GROUPS = "{\"items\":[" + GROUP + "],\"total\":1,\"offset\":0,\"limit\":20}";
	public static final String SUMMARY = """
			{"integrationId":"22222222-2222-4222-8222-222222222222","totalReports":4,"passedReports":2,"failedReports":2,
			 "lastReceivedAt":"2026-10-19T09:05:00.123456Z"}
			""";
	public static final String EMPTY_SUMMARY = """
			{"integrationId":"22222222-2222-4222-8222-222222222222","totalReports":0,"passedReports":0,"failedReports":0,"lastReceivedAt":null}
			""";

	private ApiExamples() {
	}
}
