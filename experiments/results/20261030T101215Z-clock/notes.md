# Notes: the clock fault and its fix

Three clock-monitor runs show the test machine before and after the fix:

| Run | When | Result |
|---|---|---|
| 20261030T082956Z-clock | before | 20 jumps of about 319 s in 10 minutes: PostgreSQL's clock stepped back about every 30.5 s, usually for 0.2 s, twice for 15.4 s |
| 20261030T100951Z-clock | just after | one 1.3 s step in the first 12 s (Docker's VM settling onto the corrected host time), then none |
| 20261030T101215Z-clock | after | no jumps; offset within 0.05 s for a whole minute |

Cause: a leftover policy key, HKLM\SOFTWARE\Policies\Microsoft\W32Time\Parameters
with NtpServer set to ",0x1" (an empty server name), stopped Windows from ever
syncing its clock, so it drifted about 320 s slow. Hyper-V kept pushing that wrong
time into Docker Desktop's WSL2 VM, and the VM's own time sync kept putting the real
time back. Deleting the key and pointing the time service at time.windows.com fixed
it: afterwards Windows was within 10 ms of time.google.com.

Experiment runs from before the fix (E1 and the first two throughput runs) record
clock_skew_seconds_db_minus_host of about 319 in environment.txt; runs after it
record about 0.
