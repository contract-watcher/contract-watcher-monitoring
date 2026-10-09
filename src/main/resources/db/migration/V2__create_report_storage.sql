CREATE TABLE monitoring.reports (
    report_row_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    external_report_id uuid NOT NULL,
    integration_id uuid NOT NULL,
    contract_id uuid NOT NULL,
    contract_version integer NOT NULL CHECK (contract_version > 0),
    outcome text NOT NULL CHECK (outcome IN ('PASSED', 'FAILED')),
    received_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT reports_integration_report_unique UNIQUE (integration_id, external_report_id)
);

CREATE INDEX reports_integration_history_idx
    ON monitoring.reports (integration_id, received_at DESC, report_row_id DESC);

CREATE TABLE monitoring.report_violations (
    report_row_id bigint NOT NULL REFERENCES monitoring.reports (report_row_id),
    ordinal integer NOT NULL CHECK (ordinal >= 0),
    field_name text NOT NULL CHECK (field_name <> ''),
    violation_kind text NOT NULL CHECK (
        violation_kind IN ('REQUIRED_FIELD_MISSING', 'NULL_NOT_ALLOWED', 'TYPE_MISMATCH')
    ),
    expected_type text CHECK (expected_type IN ('string', 'number', 'boolean', 'object', 'array')),
    actual_type text CHECK (actual_type IN ('string', 'number', 'boolean', 'object', 'array')),
    PRIMARY KEY (report_row_id, ordinal)
);
