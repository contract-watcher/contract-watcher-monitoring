CREATE TABLE monitoring.violation_groups (
    group_id uuid PRIMARY KEY,
    integration_id uuid NOT NULL,
    contract_id uuid NOT NULL,
    contract_version integer NOT NULL CHECK (contract_version > 0),
    field_name text NOT NULL CHECK (field_name <> ''),
    violation_kind text NOT NULL CHECK (
        violation_kind IN ('REQUIRED_FIELD_MISSING', 'NULL_NOT_ALLOWED', 'TYPE_MISMATCH')
    ),
    expected_type text CHECK (expected_type IN ('string', 'number', 'boolean', 'object', 'array')),
    actual_type text CHECK (actual_type IN ('string', 'number', 'boolean', 'object', 'array')),
    occurrence_count bigint NOT NULL CHECK (occurrence_count > 0),
    first_seen_at timestamptz NOT NULL,
    last_seen_at timestamptz NOT NULL,
    CHECK (first_seen_at <= last_seen_at)
);

CREATE UNIQUE INDEX violation_groups_identity_with_actual_type_idx
    ON monitoring.violation_groups (
        integration_id, contract_id, contract_version, field_name, violation_kind, actual_type
    ) WHERE actual_type IS NOT NULL;

-- На PostgreSQL 12 обычный UNIQUE допускает повтор ключа с NULL.
CREATE UNIQUE INDEX violation_groups_identity_without_actual_type_idx
    ON monitoring.violation_groups (
        integration_id, contract_id, contract_version, field_name, violation_kind
    ) WHERE actual_type IS NULL;

ALTER TABLE monitoring.report_violations
    ADD COLUMN group_id uuid REFERENCES monitoring.violation_groups (group_id);
