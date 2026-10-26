-- Запускать из корня Monitoring через psql -X -f только в отдельной пустой тестовой БД.
-- Результаты остаются в bench_results (полные JSON-планы) и bench_costs.
\set ON_ERROR_STOP on
DO $$ BEGIN
 IF current_database() <> 'monitoring_test_history_benchmark' THEN RAISE EXCEPTION 'Нужна отдельная БД monitoring_test_history_benchmark'; END IF;
END $$;
CREATE SCHEMA monitoring;
\i src/main/resources/db/migration/V1__initialize_monitoring_schema.sql
\i src/main/resources/db/migration/V2__create_report_storage.sql
\i src/main/resources/db/migration/V3__create_violation_groups.sql
\i src/main/resources/db/migration/V4__use_report_timestamp.sql
CREATE TABLE bench_results(phase text, scenario text, operation text, plan jsonb);
CREATE TABLE bench_costs(name text, value text);
INSERT INTO monitoring.reports(external_report_id,integration_id,contract_id,contract_version,outcome,received_at)
SELECT md5('report-'||g)::uuid,
       CASE WHEN g<=1000000 THEN md5('integration-main')::uuid ELSE md5('integration-other')::uuid END,
       md5('contract-'||CASE WHEN g%10<4 THEN 1 WHEN g%10<6 THEN 2 ELSE 3+g%998 END)::uuid,
       1+g%5, CASE WHEN g%31=0 OR g%10=4 THEN 'FAILED' ELSE 'PASSED' END,
       '2026-01-01'::timestamptz+g*interval '1 second'
FROM generate_series(1,1200000) g;
INSERT INTO monitoring.report_violations(report_row_id,ordinal,field_name,violation_kind,expected_type,actual_type)
SELECT report_row_id, n, 'sample_'||n, 'TYPE_MISMATCH', 'number', 'string'
FROM monitoring.reports CROSS JOIN generate_series(0,19) n WHERE report_row_id%100=0;
VACUUM ANALYZE monitoring.reports;
VACUUM ANALYZE monitoring.report_violations;
CREATE TABLE bench_cases(name text, condition text, page_offset int);
INSERT INTO bench_cases VALUES
('all','',0),('rare',' AND r.contract_id=md5(''contract-999'')::uuid',0),
('hot',' AND r.contract_id=md5(''contract-1'')::uuid',0),
('two',' AND r.contract_id IN (md5(''contract-999'')::uuid,md5(''contract-998'')::uuid)',0),
('rare-failed',' AND r.contract_id=md5(''contract-999'')::uuid AND r.outcome=''FAILED''',0),
('frequent-failed',' AND r.contract_id=md5(''contract-2'')::uuid AND r.outcome=''FAILED''',0),
('narrow',' AND r.contract_id=md5(''contract-999'')::uuid AND r.received_at>=''2026-01-10'' AND r.received_at<''2026-01-11''',0),
('wide',' AND r.contract_id=md5(''contract-999'')::uuid AND r.received_at>=''2026-01-01'' AND r.received_at<''2026-02-01''',0),
('deep-hot',' AND r.contract_id=md5(''contract-1'')::uuid',300000),
('deep-rare',' AND r.contract_id=md5(''contract-999'')::uuid',300),
('deep-all','',900000),
('two-hot',' AND r.contract_id IN (md5(''contract-1'')::uuid,md5(''contract-2'')::uuid)',0),
('rare-to',' AND r.contract_id=md5(''contract-999'')::uuid AND r.received_at<''2026-01-10''',0),
('rare-from',' AND r.contract_id=md5(''contract-999'')::uuid AND r.received_at>=''2026-01-10''',0),
('empty',' AND r.contract_id=md5(''contract-absent'')::uuid',0);
INSERT INTO bench_cases
SELECT 'fifty',' AND r.contract_id IN ('||string_agg('md5(''contract-'||g||''')::uuid',',')||')',0
FROM generate_series(951,1000) g;
INSERT INTO bench_cases SELECT 'fifty-failed-deep',condition||' AND r.outcome=''FAILED''',100 FROM bench_cases WHERE name='fifty';
CREATE FUNCTION bench_run(phase_name text) RETURNS void LANGUAGE plpgsql AS $$
DECLARE c record; p jsonb; base text; page_sql text; started timestamptz;
BEGIN
 FOR c IN SELECT * FROM bench_cases LOOP
  base := ' FROM monitoring.reports r WHERE r.integration_id=md5(''integration-main'')::uuid'||c.condition;
  EXECUTE 'EXPLAIN (ANALYZE,BUFFERS,FORMAT JSON) SELECT count(*)'||base INTO p;
  INSERT INTO bench_results VALUES(phase_name,c.name,'count',p);
  page_sql := 'SELECT page.external_report_id,page.contract_id,page.contract_version,page.outcome,page.received_at,
    (SELECT count(*) FROM monitoring.report_violations v WHERE v.report_row_id=page.report_row_id) AS violation_count
    FROM (SELECT r.report_row_id,r.external_report_id,r.contract_id,r.contract_version,r.outcome,r.received_at'||base||
    ' ORDER BY r.received_at DESC,r.report_row_id DESC LIMIT 20 OFFSET '||c.page_offset||') page
    ORDER BY page.received_at DESC,page.report_row_id DESC';
  EXECUTE 'EXPLAIN (ANALYZE,BUFFERS,FORMAT JSON) '||page_sql INTO p;
  INSERT INTO bench_results VALUES(phase_name,c.name,'page',p);
 END LOOP;
 started:=clock_timestamp();
 INSERT INTO monitoring.reports(external_report_id,integration_id,contract_id,contract_version,outcome,received_at)
 SELECT md5(phase_name||g)::uuid,md5('insert-cost')::uuid,md5('contract-'||(g%1000))::uuid,1,'PASSED',
 '2026-02-01'::timestamptz+g*interval '1 second' FROM generate_series(1,10000) g;
 INSERT INTO bench_costs VALUES(phase_name||'-insert-10000-ms',(extract(epoch FROM clock_timestamp()-started)*1000)::text);
 DELETE FROM monitoring.reports WHERE integration_id=md5('insert-cost')::uuid;
END $$;
SELECT bench_run('before-1');
SELECT bench_run('before-2');
SELECT bench_run('before-3');
DO $$ DECLARE started timestamptz:=clock_timestamp(); BEGIN
 CREATE INDEX reports_integration_contract_history_idx ON monitoring.reports(integration_id,contract_id,received_at DESC,report_row_id DESC);
 INSERT INTO bench_costs VALUES('create-index-ms',(extract(epoch FROM clock_timestamp()-started)*1000)::text);
 INSERT INTO bench_costs VALUES('index-bytes',pg_relation_size('monitoring.reports_integration_contract_history_idx')::text);
END $$;
VACUUM ANALYZE monitoring.reports;
SELECT bench_run('after-1');
SELECT bench_run('after-2');
SELECT bench_run('after-3');
SELECT scenario,operation,
 round(percentile_cont(0.5) WITHIN GROUP (ORDER BY (plan->0->>'Execution Time')::numeric) FILTER (WHERE phase LIKE 'before-%')::numeric,2) AS before_ms,
 round(percentile_cont(0.5) WITHIN GROUP (ORDER BY (plan->0->>'Execution Time')::numeric) FILTER (WHERE phase LIKE 'after-%')::numeric,2) AS after_ms
FROM bench_results GROUP BY scenario,operation ORDER BY scenario,operation;
TABLE bench_costs;
