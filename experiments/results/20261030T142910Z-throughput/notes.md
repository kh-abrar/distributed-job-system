# Notes: Spring Boot 4.1 / Java 21 upgrade, throughput A/B (E2)

Checks that upgrading from Spring Boot 3.2.4 / Java 17 to Spring Boot 4.1.2 /
Java 21 (with Jackson 3, Hibernate 7, Flyway 12) didn't cost throughput.

| Run folder | Version | Steady jobs/s per run | Median |
|---|---|---|---|
| 20261030T142553Z-throughput | old: `main` at 11d810a, Spring Boot 3.2.4, Java 17 | 3,560, 3,170, 3,402 | **3,402** |
| 20261030T142910Z-throughput (this one) | new: `upgrade-boot-4`, Spring Boot 4.1.2, Java 21 | 3,400, 3,350, 3,237 | **3,350** |

The two ran back to back, each on a fresh experiment database. The old version
was an exact copy of `main` exported with `git archive` and run with its own copy
of the harness; each run's environment.txt records which worker image ran (old
`1acaeba…`, new `a21330b…`). The medians are within 1.5%, well inside the spread
between runs; mean job time (2.4 vs 2.5 ms) and jobs per claim (1.92 vs 1.91)
match. **The upgrade costs no throughput.**

A first, separate run on the new version (20261030T142145Z-throughput) gave a
median of 2,971, 15% lower, with runs falling from 3,357 to 2,457. The A/B shows
that was variation between sessions on this machine, as with the connection pool
A/B before it: comparisons need to run back to back.
