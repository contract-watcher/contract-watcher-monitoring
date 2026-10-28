-- Из корня Monitoring: psql -X -v ON_ERROR_STOP=1 -f этот_файл.
-- Только отдельная пустая БД и синтетические данные, V1-V5 без изменений.
\set ON_ERROR_STOP on
DO $$ BEGIN
 IF current_database() <> 'monitoring_test_project_summaries_benchmark' THEN
  RAISE EXCEPTION 'Нужна отдельная БД monitoring_test_project_summaries_benchmark';
 END IF;
 IF EXISTS (SELECT FROM pg_namespace WHERE nspname = 'monitoring') THEN
  RAISE EXCEPTION 'Нужна пустая схема';
 END IF;
END $$;
SET TIME ZONE 'UTC';
CREATE SCHEMA monitoring;
\i src/main/resources/db/migration/V1__initialize_monitoring_schema.sql
\i src/main/resources/db/migration/V2__create_report_storage.sql
\i src/main/resources/db/migration/V3__create_violation_groups.sql
\i src/main/resources/db/migration/V4__use_report_timestamp.sql
\i src/main/resources/db/migration/V5__index_history_by_contract.sql

-- 1 млн в перекошенной интеграции, 1 млн по 10000 ID, 200 тыс. вне набора.
-- Детерминированный номер UUID позволяет независимо вычислять ожидаемый результат.
INSERT INTO monitoring.reports(external_report_id,integration_id,contract_id,contract_version,outcome,received_at)
SELECT md5('project-report-'||g)::uuid,
 ('00000000-0000-0000-0000-'||lpad(to_hex(CASE WHEN g<=1000000 THEN 1 WHEN g<=2000000 THEN 1+(g%10000) ELSE 20001+(g%1000) END),12,'0'))::uuid,
 md5('contract-'||(g%1000))::uuid, 1+g%5,
 CASE WHEN g%7=0 THEN 'FAILED' ELSE 'PASSED' END,
 CASE WHEN g<=1000000 AND g>900000 THEN '2026-12-15'::timestamptz+(g-900001)*interval '0.8 second'
 ELSE '2026-01-01'::timestamptz+(g%365)*interval '1 day'+(g%86400)*interval '1 second' END
FROM generate_series(1,2200000) g;
VACUUM ANALYZE monitoring.reports;

-- Данные готовы для EXPLAIN точного production SQL и настоящих HTTP/JDBC.
-- После первой серии измерений вставить g=2200001..2210000 с той же формулой,
-- целевой integration_id=1, выполнить ANALYZE без VACUUM и повторить серию.
