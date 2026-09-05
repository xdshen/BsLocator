"""
A/B comparison: 8-parameter estimator (vertical 3dB beamwidth fixed at 10 deg)
vs 9-parameter estimator (vertical beamwidth adaptive, bounded [5, 15] deg).

Faithful Python port of BaseStationEstimator.kt:
  log-distance path loss + 3GPP TR 38.901 antenna pattern, Huber loss (delta=5 dB),
  analytic gradients, 4 multi-start initializations, box constraints.

For each ECI with enough samples:
  - full-fit RMSE for both variants (in-sample; always favors the extra dof)
  - split-half cross-validation: fit on even time-indexed points, evaluate on odd
  - position/azimuth/tilt difference between the two variants
"""
import sqlite3
import math
import numpy as np
import pandas as pd
from scipy.optimize import minimize
from pathlib import Path

DB = Path(__file__).resolve().parent.parent / '_archive' / 'latest_db.sqlite'
OUT = Path(__file__).resolve().parent.parent / '_archive'

EARTH_R = 6371000.0
UE_H = 1.5
DELTA = 5.0          # Huber threshold (dB)
MAX_ATTEN = 30.0
FBR = 25.0
V_BW_FIXED = 10.0
MIN_PTS = 50         # per-ECI minimum after GPS filtering
MAX_GPS_ACC = 15.0

# parameter bounds: [bsX, bsY, azimuth, beamwidth, tilt, height, n, p0, v_bw]
def bounds_for(points, adaptive):
    lo = [points[:, 0].min() - 500, points[:, 1].min() - 500, 0, 30, 0, 5, 1.5, -80]
    hi = [points[:, 0].max() + 500, points[:, 1].max() + 500, 360, 120, 15, 50, 5.0, -20]
    if adaptive:
        lo.append(5.0); hi.append(15.0)
    return list(zip(lo, hi))


def predict_and_grad(params, pts, adaptive):
    """Return (huber_loss, grad) — analytic, mirroring BaseStationEstimator.kt."""
    if adaptive:
        bx, by, az, bw, tilt, h, n, p0, vbw = params
    else:
        bx, by, az, bw, tilt, h, n, p0 = params
        vbw = V_BW_FIXED

    dx = pts[:, 0] - bx
    dy = pts[:, 1] - by
    d = np.maximum(np.hypot(dx, dy), 1.0)
    k = 180.0 / math.pi

    bearing = k * np.arctan2(dx, dy)
    dAz = (bearing - az + 180) % 360 - 180
    hdiff = h - UE_H
    elev = k * np.arctan2(hdiff, d)
    dEl = elev + tilt

    pl = p0 - 10.0 * n * np.log10(d)
    h_raw = 12.0 * (dAz / bw) ** 2
    h_gain = np.where(h_raw < MAX_ATTEN, -h_raw, -MAX_ATTEN)
    v_raw = 12.0 * (dEl / vbw) ** 2
    v_gain = np.where(v_raw < MAX_ATTEN, -v_raw, -MAX_ATTEN)
    fbr = np.where(np.abs(dAz) > 90, -FBR, 0.0)
    pred = pl + h_gain + v_gain + fbr

    err = pred - pts[:, 2]
    abs_e = np.abs(err)
    loss = np.where(abs_e <= DELTA, 0.5 * err**2, DELTA * (abs_e - 0.5 * DELTA)).sum()
    de = np.where(abs_e <= DELTA, err, DELTA * np.sign(err))

    # intermediates
    d2 = np.maximum(dx**2 + dy**2, 1.0)
    dbear_bx = -dy * k / d2
    dbear_by = dx * k / d2
    delev_h = d * k / (d2 + hdiff**2)
    delev_d = -hdiff * k / (d2 + hdiff**2)
    ddist_bx = -dx / d
    ddist_by = -dy / d
    delev_bx = delev_d * ddist_bx
    delev_by = delev_d * ddist_by

    # horizontal gain derivatives (zero where clipped)
    act_h = h_raw < MAX_ATTEN
    dh_ddAz = np.where(act_h, -24.0 * dAz / bw**2, 0.0)
    dh_bx = dh_ddAz * dbear_bx
    dh_by = dh_ddAz * dbear_by
    dh_az = np.where(act_h, 24.0 * dAz / bw**2, 0.0)
    dh_bw = np.where(act_h, 24.0 * dAz**2 / bw**3, 0.0)

    # vertical gain derivatives
    act_v = v_raw < MAX_ATTEN
    dv_ddEl = np.where(act_v, -24.0 * dEl / vbw**2, 0.0)
    dv_bx = dv_ddEl * delev_bx
    dv_by = dv_ddEl * delev_by
    dv_tilt = dv_ddEl
    dv_h = dv_ddEl * delev_h
    dv_vbw = np.where(act_v, 24.0 * dEl**2 / vbw**3, 0.0)

    # path loss derivatives
    dpl_d = -10.0 * n / (d * math.log(10.0))
    dpl_bx = dpl_d * ddist_bx
    dpl_by = dpl_d * ddist_by
    dpl_n = -10.0 * np.log10(d)

    grad = np.array([
        (de * (dpl_bx + dh_bx + dv_bx)).sum(),
        (de * (dpl_by + dh_by + dv_by)).sum(),
        (de * dh_az).sum(),
        (de * dh_bw).sum(),
        (de * dv_tilt).sum(),
        (de * dv_h).sum(),
        (de * dpl_n).sum(),
        de.sum(),
    ])
    if adaptive:
        grad = np.append(grad, (de * dv_vbw).sum())
    return loss, grad


def predict(params, pts, adaptive):
    bx, by, az, bw, tilt, h, n, p0 = params[:8]
    vbw = params[8] if adaptive else V_BW_FIXED
    dx = pts[:, 0] - bx
    dy = pts[:, 1] - by
    d = np.maximum(np.hypot(dx, dy), 1.0)
    k = 180.0 / math.pi
    dAz = ((k * np.arctan2(dx, dy)) - az + 180) % 360 - 180
    elev = k * np.arctan2(h - UE_H, d)
    dEl = elev + tilt
    pl = p0 - 10.0 * n * np.log10(d)
    h_gain = -np.minimum(12.0 * (dAz / bw) ** 2, MAX_ATTEN)
    v_gain = -np.minimum(12.0 * (dEl / vbw) ** 2, MAX_ATTEN)
    fbr = np.where(np.abs(dAz) > 90, -FBR, 0.0)
    return pl + h_gain + v_gain + fbr


def rmse(params, pts, adaptive):
    r = predict(params, pts, adaptive) - pts[:, 2]
    return math.sqrt((r**2).mean())


def initial_guesses(pts, adaptive):
    w = 10.0 ** (pts[:, 2] / 10.0)
    wsum = w.sum()
    wx = (pts[:, 0] * w).sum() / wsum
    wy = (pts[:, 1] * w).sum() / wsum
    strongest = pts[np.argmax(pts[:, 2])]
    cx = (pts[:, 0].min() + pts[:, 0].max()) / 2
    cy = (pts[:, 1].min() + pts[:, 1].max()) / 2
    iw = 1.0 / (np.hypot(pts[:, 0] - wx, pts[:, 1] - wy) + 1.0)
    ix = (pts[:, 0] * iw).sum() / iw.sum()
    iy = (pts[:, 1] * iw).sum() / iw.sum()

    weakest = pts[np.argmin(pts[:, 2])]

    def mk(x0, y0):
        az = math.degrees(math.atan2(weakest[0] - x0, weakest[1] - y0)) % 360
        p = [x0, y0, az, 65.0, 6.0, 30.0, 3.0, -40.0]
        if adaptive:
            p.append(V_BW_FIXED)
        return np.array(p, dtype=float)

    return [mk(wx, wy), mk(strongest[0], strongest[1]), mk(cx, cy), mk(ix, iy)]


def estimate(pts, adaptive):
    best, best_rmse = None, 1e18
    for p0 in initial_guesses(pts, adaptive):
        r = minimize(predict_and_grad, p0, args=(pts, adaptive), jac=True,
                     method='L-BFGS-B', bounds=bounds_for(pts, adaptive),
                     options={'maxiter': 500})
        cur = rmse(r.x, pts, adaptive)
        if cur < best_rmse:
            best, best_rmse = r.x, cur
    return best, best_rmse


def main():
    conn = sqlite3.connect(DB)
    df = pd.read_sql(
        "SELECT eci, timestamp, latitude, longitude, rsrp, gps_accuracy FROM measurements", conn)
    conn.close()
    df = df[(df['eci'] > 0) & (df['gps_accuracy'] < MAX_GPS_ACC)]

    rows = []
    for eci, g in df.groupby('eci'):
        g = g.sort_values('timestamp')
        if len(g) < MIN_PTS:
            continue
        lat0, lng0 = g['latitude'].iloc[0], g['longitude'].iloc[0]
        east = (g['longitude'] - lng0) * math.pi / 180 * EARTH_R * math.cos(math.radians(lat0))
        north = (g['latitude'] - lat0) * math.pi / 180 * EARTH_R
        pts = np.column_stack([east.values, north.values, g['rsrp'].values.astype(float)])

        p8, r8 = estimate(pts, adaptive=False)
        p9, r9 = estimate(pts, adaptive=True)

        # split-half cross-validation (even/odd by index ~ time interleave)
        fit_pts, val_pts = pts[0::2], pts[1::2]
        q8, _ = estimate(fit_pts, adaptive=False)
        q9, _ = estimate(fit_pts, adaptive=True)
        cv8, cv9 = rmse(q8, val_pts, False), rmse(q9, val_pts, True)

        dist = math.hypot(p8[0] - p9[0], p8[1] - p9[1])
        daz = abs((p8[2] - p9[2] + 180) % 360 - 180)
        rows.append(dict(
            eci=eci, n=len(pts),
            rmse8=r8, rmse9=r9, cv8=cv8, cv9=cv9,
            pos_diff_m=dist, az_diff_deg=daz,
            tilt8=p8[4], tilt9=p9[4], vbw9=p9[8],
            az8=p8[2], az9=p9[2],
        ))
        print(f"ECI {eci} (n={len(pts)}): RMSE 8p={r8:.2f} 9p={r9:.2f} | "
              f"CV 8p={cv8:.2f} 9p={cv9:.2f} | pos diff={dist:.1f}m az diff={daz:.1f}° | "
              f"tilt {p8[4]:.1f}->{p9[4]:.1f}° vbw9={p9[8]:.1f}°")

    res = pd.DataFrame(rows)
    res.to_csv(OUT / 'ab_vbw_results.csv', index=False)
    print(f"\n=== Summary over {len(res)} cells ===")
    print(f"in-sample RMSE: 8p mean {res.rmse8.mean():.3f} vs 9p mean {res.rmse9.mean():.3f} "
          f"(9p wins on {int((res.rmse9 < res.rmse8).sum())}/{len(res)})")
    print(f"cross-val RMSE: 8p mean {res.cv8.mean():.3f} vs 9p mean {res.cv9.mean():.3f} "
          f"(9p wins on {int((res.cv9 < res.cv8).sum())}/{len(res)})")
    print(f"position diff: mean {res.pos_diff_m.mean():.1f} m, max {res.pos_diff_m.max():.1f} m")
    print(f"azimuth diff:  mean {res.az_diff_deg.mean():.1f}°, max {res.az_diff_deg.max():.1f}°")
    print(f"adaptive v_bw: mean {res.vbw9.mean():.1f}°, range [{res.vbw9.min():.1f}, {res.vbw9.max():.1f}], "
          f"hit-lower-bound(5°) {int((res.vbw9 <= 5.01).sum())}, hit-upper(15°) {int((res.vbw9 >= 14.99).sum())}")

    # comparison figure
    import matplotlib.pyplot as plt
    fig, axes = plt.subplots(1, 3, figsize=(16, 5))
    x = np.arange(len(res))
    axes[0].bar(x - 0.2, res.rmse8, 0.4, label='8-param (fixed 10°)', color='#4472C4')
    axes[0].bar(x + 0.2, res.rmse9, 0.4, label='9-param (adaptive)', color='#ED7D31')
    axes[0].set_title('In-sample RMSE (lower is better)')
    axes[0].set_ylabel('RMSE (dB)'); axes[0].legend(); axes[0].set_xticks(x)
    axes[0].set_xticklabels([str(e)[-4:] for e in res.eci], rotation=45, fontsize=8)
    axes[0].set_xlabel('ECI (last 4 digits)')

    axes[1].bar(x - 0.2, res.cv8, 0.4, label='8-param (fixed 10°)', color='#4472C4')
    axes[1].bar(x + 0.2, res.cv9, 0.4, label='9-param (adaptive)', color='#ED7D31')
    axes[1].set_title('Cross-validation RMSE (split-half)')
    axes[1].set_ylabel('RMSE (dB)'); axes[1].legend(); axes[1].set_xticks(x)
    axes[1].set_xticklabels([str(e)[-4:] for e in res.eci], rotation=45, fontsize=8)
    axes[1].set_xlabel('ECI (last 4 digits)')

    axes[2].scatter(res.tilt9, res.vbw9, s=res.n / 3, c='#7030A0', alpha=0.7, edgecolor='white')
    for _, r in res.iterrows():
        axes[2].annotate(f"{r.pos_diff_m:.0f}m", (r.tilt9, r.vbw9),
                         textcoords='offset points', xytext=(5, 5), fontsize=8)
    axes[2].axhline(10, color='gray', ls='--', alpha=0.5, label='fixed 10°')
    axes[2].set_xlabel('estimated tilt (deg)')
    axes[2].set_ylabel('adaptive vertical beamwidth (deg)')
    axes[2].set_title('Fitted v_bw vs tilt\n(labels: |position shift| 8p vs 9p)')
    axes[2].legend()

    plt.tight_layout()
    fig.savefig(OUT / 'ab_vbw_comparison.png', dpi=150, bbox_inches='tight')
    print('figure saved to', OUT / 'ab_vbw_comparison.png')


if __name__ == '__main__':
    main()
