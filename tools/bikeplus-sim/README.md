# Bike+ ERG simulator

A small Python simulator of Grupetto's ERG controller driving a model Peloton Bike+, used to design and
check the feed-forward, smooth ramp, half-level deadband and cadence-memory changes before riding them.
It is a development tool, not part of the app. Python 3.10+ with `numpy` and `pandas`.

**It is a copy, not the app.** `ctl.py` is a hand port of `BikeControl`'s ERG logic with switches for each
variant; keep it in step with the Kotlin when the controller changes.

## The model bike (`plant.py`, calibrated from the rides in `data/`)

- Power at a level: `A(level) · (cadence/90)^1.7`, with `A` = the seed curve
  `exp(1.491016 + 0.108267·r − 0.000584·r²)` scaled up by up to 3.5% above level 46 (this bike's steady
  readings, `table.py`). `plant.SCALE` shifts the whole bike ±3% to stand in for another unit or a recalibration.
- Motor: moves toward the commanded level at 30 levels/s (the fit put it at 30 or faster).
- Reported power: 0.9 s dead time, then a 0.8 s first-order lag (`fit.py`; RMS fit error about 6 W over 21
  interval changes from three rides).
- Grupetto timing: telemetry every 200 ms, control tick every 100 ms, the mainboard echoes a write about 0.25 s later.

## Riders

- Replay: cadence from a real ride (`run.fitcad('r11')`), lightly smoothed.
- Models (`rider.py`): `spinner` (about 97 rpm at 330 W and up, 90 rpm otherwise, spins up to about 101 rpm in
  the last 5 s before a hard effort, as on the Oct 29 ride), `steady` (90 rpm), `grinder` (82 rpm at high power),
  `mixed`. Cadence follows the target with a 3 s time constant.

## Files

| File | What it does |
|---|---|
| `plant.py` | Model bike |
| `ctl.py` | Port of Grupetto's ERG controller; variant switches: `rate`, `settle`, `smooth`/`ramp_rate`, `band`, `persist`, `cadref` (`avg5`, `med120`, `mem_avg5`, `down_mem`, ...) |
| `run.py` | `simulate()`, `metrics()`, `levelchanges()`, and variants `V_PT3`, `V_PT5`, `V_PT6` (Oct 28 builds) |
| `evalv.py` | Workouts (PowerTable, 3-min threshold) over replayed cadence and ±3% bikes |
| `eval3.py` | The Oct 29 PowerTable v2 workout over rider models; `F` = current build (ramp + half-level band) |
| `sweep1.py`, `sweep2.py`, `sweep3.py` | Deadband / persistence, ramp rate, saved-table experiments |
| `fit.py`, `table.py` | Re-fit motor/reading dynamics and the per-level power table from `data/` |
| `extract.py` | Reads `bike.log` (or a raw logcat) |
| `analyze_ride.py` | Interval-change metrics for a real ride folder |
| `data/` | Ride data used for calibration (see `data/README.md`) |

## Examples

```bash
python analyze_ride.py data/r11                      # a real ride's settle/overshoot per interval change
python -c "from eval3 import *; print(ev(dict(F,cadref='down_mem'), PT7, 'spinner'))"
python sweep1.py                                     # deadband / persistence comparison
```

Treat results as comparisons between variants under the same conditions. The simulator reproduces real
transitions closely, but exaggerates steady-state level hunting (it cannot copy how a rider adjusts), so
absolute steady-state numbers are pessimistic.

## Key results so far

| Change | Simulated | Ridden |
|---|---|---|
| Half-level deadband | steady-state level changes down 3–4x, settle unchanged | Oct 29: 150 s at 295 W and 30 s at 360 W held with 0–1 changes |
| Smooth 10 levels/s ramp | as fast as the stepped version | Oct 29: smooth, up-jumps settle ~5 s, 4–6 W overshoot |
| Saved power table | no gain; a 6% stale table made settling worse (7.3 → 11 s) | not built |
| Drop level from remembered cadence (`down_mem`) | spinner: settle 12.9 → 10.4 s, overshoot 11.3 → 7.0 W; steady rider unchanged | pending |
