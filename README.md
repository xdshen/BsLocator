# BsLocator — Base Station Localization with Unknown Antenna Pattern

[English](README.md) | [中文](README.zh-CN.md)

**[⬇ Download the APK — install on your phone directly](download/BsLocator-v1.0-debug.apk)**
(Android 8.0+, ~40 MB, debug-signed)

> Reverse-engineer the location **and** the antenna radiation pattern of an LTE/NR base station
> from nothing but a walk-around drive test: GPS + RSRP measurements, collected by this Android app,
> solved by joint on-device optimization.

## Why this matters

A cellular base station does not radiate equally in all directions. The antenna pattern is a
function of angle, so the same distance can produce RSRP values that differ by up to **30 dB**.
Classical RSSI-based localization silently assumes an omnidirectional pattern — and fails badly:

| Approach | Prerequisite | Positioning error |
|---|---|---|
| Fixed path-loss exponent + least squares | Pattern known | 5–10 m |
| Global adaptive path-loss exponent | Pattern known | 8–15 m |
| **Joint estimation (this project)** | **Pattern unknown** | **8–15 m** |
| Ignore the pattern entirely | None | **350 m+ (worse than guessing)** |

BsLocator jointly estimates **8 parameters** — base station position (x, y), antenna azimuth,
3 dB beamwidth, downtilt, antenna height, path-loss exponent and reference RSSI — using only
field measurements. No prior knowledge of the site is required.

![Method comparison](assets/chart3_method_compare.png)

## How it works

![Principle](assets/principle.png)

The signal model combines a log-distance path loss with the
[3GPP TR 38.901](https://www.etsi.org/deliver/etsi_tr/138900_138999/138901/) antenna pattern
(horizontal + vertical, 30 dB front-to-back-limited). The optimizer minimizes a **Huber-robust**
residual with analytic gradients, Armijo backtracking line search, projected parameter bounds
and **4 multi-start initializations** (weighted centroid, strongest-signal point, bounding-box
center, inverse-distance weighted) — the run with the best RMSE wins.

![Pipeline](assets/pipeline.png)

## App screenshots

| Drive-test collection | Map with estimated sector | Estimation result | Session logs |
|---|---|---|---|
| ![collection](assets/screenshots/measure.png) | ![map](assets/screenshots/map_estimate.png) | ![estimate](assets/screenshots/estimate_result.png) | ![logs](assets/screenshots/logs.png) |

<details>
<summary>More screenshots</summary>

![help](assets/screenshots/help.png)
![map zoom](assets/screenshots/map_estimate_zoom.png)
![map wide](assets/screenshots/map.png)

</details>

## Performance

Positioning accuracy vs. angular coverage of the measurement campaign (macro-cell scenario):

| Measurement coverage | Samples | Positioning error | Azimuth error | Beamwidth error |
|---|---|---|---|---|
| Main lobe ±30° only | 62 | 14.6 m | 2.2° | 5.1° |
| Main lobe + one side lobe | 95 | 11.3 m | 1.5° | 3.8° |
| Full 360° | 156 | **8.2 m** | **0.8°** | **2.1°** |
| Sparse (4 directions only) | 28 | 35.4 m | 8.7° | 15.2° |

![Coverage vs error](assets/chart1_coverage_error.png)
![Parameter accuracy](assets/chart2_param_accuracy.png)
![Coverage trend](assets/chart4_coverage_trend.png)

### Validation on real field data

953 real measurements (PCI 199, collected in Beijing with this app) reproduce the expected
distance–RSRP trend and bearing-dependent pattern attenuation:

![Field data analysis](assets/pci199_analysis.png)

## Features

- **Drive-test collection** — foreground service records GPS + LTE/NR serving-cell signal
  (ECI/PCI, EARFCN, RSRP/RSRQ/SINR, CQI, TAC) at up to 2 Hz, with GPS-accuracy filtering
- **On-device estimation** — WorkManager background worker, single-cell or batch over all
  ECIs in selected sessions, progress notifications included
- **Map visualization** — AMap (Gaode) overlay: color-coded measurement tracks, estimated
  base station marker, translucent main-lobe sector polygon, WGS-84 ↔ GCJ-02 conversion
- **Session management** — multi-select sessions, CSV import/export (SAF), JSON export
- **In-app help** — permission guide plus per-vendor background-keep-alive instructions
  with one-tap shortcuts to system settings
- **Offline analysis** — Python scripts reproduce the estimation and generate report charts

## Repository structure

```
├── app/        Android app (Kotlin · Jetpack Compose · Room · WorkManager · AMap)
│   └── app/src/main/java/com/example/bslocator/algorithm/BaseStationEstimator.kt  ← core algorithm
├── analysis/   Offline analysis & figure generation (pandas / scipy / matplotlib)
├── assets/     Charts, diagrams and app screenshots used in this README
├── data/       Real field-measurement sample (953 records, CSV)
├── docs/       Research report (Markdown + Word) + detailed algorithm doc (算法详解.md)
└── download/   Ready-to-install APK
```

## Install & use the app

### 1. Install

1. Download **[BsLocator-v1.0-debug.apk](download/BsLocator-v1.0-debug.apk)**
   (on the GitHub page, click the file, then **Download raw file**) — or just open this
   repository on your phone's browser and tap the link
2. Allow *Install unknown apps* for your browser/file manager when Android asks
3. Open the app and grant the permissions it needs:
   - **Location (precise)** — required for GPS fixes during the drive test
   - **Phone / phone state** — required to read LTE/NR cell info (ECI, PCI, RSRP…)
   - **Notifications** — so background estimation can report progress
4. **Keep the app alive in the background (important on Chinese ROMs):**
   vivo / OPPO / Xiaomi / Huawei aggressively kill background apps, which would silently
   stop a long drive test. Before collecting:
   - Add BsLocator to the **battery-optimization whitelist** ("Don't optimize")
   - Allow **auto-launch / background activity** for the app (system settings path varies
     by vendor)
   - **Lock the app** in the recent-tasks view (pull down its card)
   - Disable battery-saver mode during the test
   
   The in-app **帮助 (Help)** tab lists the exact per-vendor settings paths and offers
   one-tap shortcuts to the relevant system settings pages — check it on first launch.

The map works out of the box — no API key or other configuration is needed.

### 2. Collect measurements (采集)

1. Go outdoors near the target base station; the more you can **walk around it (ideally
   360°, near + far distances)**, the better the result
2. Open the **采集** tab and tap **开始采集** — a foreground service keeps recording even
   with the screen off
3. Walk your route; each sample (GPS + serving-cell signal) is stored automatically
4. Tap **停止采集** when done. Aim for **50+ samples per cell**; below ~20 the estimate
   is unreliable

### 3. Run the estimation (推断)

1. Open the **推断** tab, pick the target cell (ECI/PCI) from the dropdown
2. Tap **开始推断（后台运行）** — you can lock the screen; a notification arrives on
   completion. To estimate every cell seen in selected sessions at once, tick sessions in
   the **日志** tab first, then use the batch button
3. The result card shows the estimated **position, azimuth, beamwidth, downtilt, height,
   path-loss exponent** and the fit **RMSE**

### 4. Inspect on the map (地图)

- Tick sessions in the **日志** tab to overlay their tracks on the **地图** tab
- Estimated base stations appear as a **red star** with a translucent **main-lobe sector**
- Tap any marker for full measurement / estimation details

### 5. Export your data

- Single session: **日志** tab → export icon → CSV or JSON
- Estimation + its source measurements: the button at the bottom of the **推断** result card
- Exported files can be fed directly into the scripts under `analysis/`

## Getting started

### Android app

1. Open `app/` in Android Studio (or run `./gradlew assembleDebug` inside `app/`)
2. Insert your own **AMap (Gaode) API key** in
   `app/app/src/main/AndroidManifest.xml` (`com.amap.api.v2.apikey`) —
   see `app/高德云KEY配置说明.md`
3. Install, grant location + phone-state permissions, walk around a base station,
   then run inference from the **推断 (Estimate)** tab

### Offline analysis

```bash
pip install pandas numpy scipy matplotlib
python analysis/analyze_pci199.py    # joint estimation on the bundled field data
python analysis/plot_pci199.py       # regenerates assets/pci199_analysis.png
```

## Research report & algorithm documentation

The complete study (problem analysis, algorithm design, simulation results, deployment
guidelines) is in `docs/单基站天线方向图未知_定位研究报告.md` (Chinese) and the
companion Word document. The detailed algorithm specification — signal model formulas,
parameter bounds, Huber loss, optimizer, multi-start strategy, robustness analysis and
future improvements (IRLS two-round fitting, TA constraints, …) — is in
[`docs/算法详解.md`](docs/算法详解.md), mapped line-by-line to
`BaseStationEstimator.kt`.

## Disclaimer

The CSV in `data/` contains real GPS traces. It is published for research reproducibility —
please use it responsibly.

## License

No license file yet — all rights reserved by the author until one is added.
