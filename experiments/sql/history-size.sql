-- How much space jobs and their history take (spec Open Question 3: how much
-- job_attempts history to keep). Sizes include indexes and TOAST.

SELECT relname                                          AS table_name,
       n_live_tup                                       AS rows,
       pg_total_relation_size(relid)                    AS total_bytes,
       round(pg_total_relation_size(relid)::numeric / nullif(n_live_tup, 0)) AS bytes_per_row
FROM pg_stat_user_tables
WHERE relname IN ('jobs', 'job_attempts')
ORDER BY relname;
