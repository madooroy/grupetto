# Ride data

One folder per ride: `ride.csv` (TrainerRoad FIT, 1 s: time, power, cadence), `laps.csv`, and `bike.log` (Bike+ telemetry from logcat: `S` = 1 Hz resistance/cadence/power reading, `W` = a Grupetto resistance write). Heart rate and location are not included.

The tablet clock and the FIT clock differ (about 3 s on Oct 28, about 10 min on Oct 29); align by power.

| Folder | Ride |
|---|---|
| `data/r5` | 2026-10-28 PowerTable, Grupetto feed-forward 3 levels/s |
| `data/r6` | 2026-10-28 PowerTable, SS2K 26.10.18-ch |
| `data/r7` | 2026-10-28 PowerTable, Grupetto 8 levels/s |
| `data/r8` | 2026-10-28 PowerTable, Grupetto settle on arrival |
| `data/r9` | 2026-10-28 PowerTable, Grupetto rebased v0.0.44 |
| `data/r10` | 2026-10-28 PowerTable, Grupetto single-command jump |
| `data/r11` | 2026-10-28 PowerTable, Grupetto 16 pts/s + 4.5 s settle |
| `data/r12` | 2026-10-29 PowerTable v2, Grupetto smooth ramp + half-level band |
| `data/r3` | 2026-10-28 Wannenhorn, SS2K 26.10.18-ch |
| `data/r4` | 2026-10-28 Wannenhorn, Grupetto feed-forward |
