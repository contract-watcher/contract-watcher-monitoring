CREATE INDEX reports_integration_contract_history_idx
    ON monitoring.reports (integration_id, contract_id, received_at DESC, report_row_id DESC);
