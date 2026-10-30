-- The statements that took the most total database time since the last reset
-- (spec Open Question 5: is the claim query the bottleneck?)

SELECT calls,
       round(total_exec_time::numeric, 1)                      AS total_ms,
       round(mean_exec_time::numeric, 3)                       AS mean_ms,
       rows,
       left(regexp_replace(query, '\s+', ' ', 'g'), 90)        AS statement
FROM pg_stat_statements
WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
ORDER BY total_exec_time DESC
LIMIT 10;
