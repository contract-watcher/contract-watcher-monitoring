-- Запуск из корня Monitoring через psql -X -v ON_ERROR_STOP=1 -f.
-- Только синтетические данные в отдельной пустой тестовой БД.
\set ON_ERROR_STOP on
DO $$ BEGIN
 IF current_database() <> 'monitoring_test_summary_benchmark' THEN
  RAISE EXCEPTION 'Нужна отдельная БД monitoring_test_summary_benchmark';
 END IF;
 IF EXISTS (SELECT FROM pg_namespace WHERE nspname = 'monitoring') THEN
  RAISE EXCEPTION 'Нужна пустая БД без схемы monitoring';
 END IF;
END $$;
SET TIME ZONE 'UTC';
CREATE SCHEMA monitoring;
\i src/main/resources/db/migration/V1__initialize_monitoring_schema.sql
\i src/main/resources/db/migration/V2__create_report_storage.sql
\i src/main/resources/db/migration/V3__create_violation_groups.sql
\i src/main/resources/db/migration/V4__use_report_timestamp.sql
\i src/main/resources/db/migration/V5__index_history_by_contract.sql

CREATE TABLE bench_results(phase text, run int, scenario text, plan jsonb);
CREATE TABLE bench_costs(name text, value numeric);
INSERT INTO monitoring.reports(external_report_id,integration_id,contract_id,contract_version,outcome,received_at)
SELECT md5('summary-report-'||g)::uuid,
       md5(CASE WHEN g<=1000000 THEN 'summary-main' ELSE 'summary-other-'||(g%10) END)::uuid,
       md5('contract-'||(g%1000))::uuid, 1+g%5,
       CASE WHEN g%7=0 THEN 'FAILED' ELSE 'PASSED' END,
       CASE WHEN g>900000 AND g<=1000000
            THEN '2026-12-15'::timestamptz+(g-900001)*interval '0.8 second'
            ELSE '2026-01-01'::timestamptz+(g%365)*interval '1 day'+(g/365)*interval '20 second' END
FROM generate_series(1,1200000) g;
VACUUM ANALYZE monitoring.reports;

CREATE TABLE bench_cases(name text, integration_id uuid, from_time timestamptz, to_time timestamptz);
INSERT INTO bench_cases VALUES
('hour',md5('summary-main')::uuid,'2026-07-15T08:00:00Z','2026-07-15T09:00:00Z'),
('day',md5('summary-main')::uuid,'2026-07-15T00:00:00Z','2026-07-16T00:00:00Z'),
('hot-day',md5('summary-main')::uuid,'2026-12-15T00:00:00Z','2026-12-16T00:00:00Z'),
('week',md5('summary-main')::uuid,'2026-07-13T00:00:00Z','2026-07-20T00:00:00Z'),
('year',md5('summary-main')::uuid,'2026-01-01T00:00:00Z','2027-01-01T00:00:00Z'),
('all',md5('summary-main')::uuid,null,null),
('from',md5('summary-main')::uuid,'2026-07-15T00:00:00Z',null),
('to',md5('summary-main')::uuid,null,'2026-07-16T00:00:00Z'),
('empty',md5('summary-main')::uuid,'2027-01-01T00:00:00Z','2027-01-02T00:00:00Z'),
('unknown',md5('summary-unknown')::uuid,null,null);

CREATE FUNCTION bench_run(phase_name text) RETURNS void LANGUAGE plpgsql AS $$
DECLARE c record; p jsonb; query text; attempt int;
BEGIN
 FOR attempt IN 1..3 LOOP
  FOR c IN SELECT * FROM bench_cases ORDER BY name LOOP
   query := 'SELECT count(*) AS total_reports,
     count(*) FILTER (WHERE outcome=''PASSED'') AS passed_reports,
     count(*) FILTER (WHERE outcome=''FAILED'') AS failed_reports,
     max(received_at) AS last_received_at FROM monitoring.reports WHERE integration_id='
     ||quote_literal(c.integration_id)||'::uuid';
   IF c.from_time IS NOT NULL THEN query := query||' AND received_at >= '||quote_literal(c.from_time)||'::timestamptz'; END IF;
   IF c.to_time IS NOT NULL THEN query := query||' AND received_at < '||quote_literal(c.to_time)||'::timestamptz'; END IF;
   EXECUTE 'EXPLAIN (ANALYZE,BUFFERS,FORMAT JSON) '||query INTO p;
   INSERT INTO bench_results VALUES(phase_name,attempt,c.name,p);
  END LOOP;
 END LOOP;
END $$;
SELECT bench_run('vacuum');

DO $$ DECLARE started timestamptz:=clock_timestamp(); BEGIN
 INSERT INTO monitoring.reports(external_report_id,integration_id,contract_id,contract_version,outcome,received_at)
 SELECT md5('summary-fresh-'||g)::uuid,md5('summary-main')::uuid,md5('contract-'||(g%1000))::uuid,
        1+g%5,CASE WHEN g%7=0 THEN 'FAILED' ELSE 'PASSED' END,
        '2026-12-15'::timestamptz+g*interval '1 second'
 FROM generate_series(1,10000) g;
 INSERT INTO bench_costs VALUES('fresh-insert-10000-ms',extract(epoch FROM clock_timestamp()-started)*1000);
END $$;
ANALYZE monitoring.reports;
SELECT bench_run('fresh');
INSERT INTO bench_costs
SELECT indexrelname||'-bytes',pg_relation_size(indexrelid)
FROM pg_stat_user_indexes WHERE schemaname='monitoring' AND relname='reports';

CREATE VIEW bench_metrics AS
SELECT phase,run,scenario,
 (plan->0->>'Planning Time')::numeric AS planning_ms,
 (plan->0->>'Execution Time')::numeric AS execution_ms,
 (plan->0->'Plan'->>'Shared Hit Blocks')::bigint AS shared_hits,
 (plan->0->'Plan'->>'Shared Read Blocks')::bigint AS shared_reads,
 (plan->0->'Plan'->>'Temp Read Blocks')::bigint AS temp_reads,
 (plan->0->'Plan'->>'Temp Written Blocks')::bigint AS temp_writes
FROM bench_results;
SELECT phase,scenario,round(percentile_cont(0.5) WITHIN GROUP (ORDER BY execution_ms)::numeric,3) AS median_ms,
 min(shared_hits) AS min_hits,max(shared_hits) AS max_hits,max(shared_reads) AS reads,
 max(temp_reads) AS temp_reads,max(temp_writes) AS temp_writes
FROM bench_metrics GROUP BY phase,scenario ORDER BY phase,scenario;
TABLE bench_costs;
-- Полные планы с rows/loops, rows removed и buffers остаются в bench_results.
-- HTTP/JDBC проверяется отдельно на этой же БД, например с session auto_explain.
