-- The saga logs of LDS in PostgreSQL that hold entries: writes that are under way, that failed and wait to be
-- run again, or that were given up (the saga log whose name ends in dead-saga). Empty when every write is done.
--
--   psql -U lds -d sagalog -v owner=development -f sagalog-entries.sql
--
-- owner is the value of sagalog.config.cluster.owner, the schema the saga log is in. It only reads.
SELECT 'SELECT * FROM (' || string_agg(format('SELECT %L AS saga_log, count(*) AS entries FROM %I.%I',
         table_name, table_schema, table_name), ' UNION ALL ') || ') AS t WHERE entries > 0 ORDER BY saga_log'
FROM information_schema.tables
WHERE table_schema = :'owner' AND table_name LIKE 'SAGALOG%'
\gexec
