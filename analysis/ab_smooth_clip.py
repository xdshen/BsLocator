"""
A/B/C comparison of the 30 dB pattern attenuation handling:
  A) hard clip (3GPP min[raw, 30], zero gradient beyond cap)   - current app
  B) smooth tanh saturation (-30*tanh(raw/30), gradient everywhere)
  C) tanh + near-field point filtering (drop d<20m after first pass, refit)

Same 8-parameter estimator, Huber loss, 4 multi-starts, bounds as in the app.
Metrics: in-sample RMSE and split-half cross-validation RMSE, per cell.
"""
import math
import numpy as np
import pandas as pd
import sqlite3
from pathlib import Path
from scipy.optimize import minimize

import sys
sys.path.insert(0, str(Path(__file__).resolve().parent))
from ab_compare_vbw import EARTH_R, UE_H, MAX_ATTEN, FBR, V_BW_FIXED, DELTA, \
    bounds_for, initial_guesses

DB = Path(__file__).resolve().parent.parent / '_archive' / 'latest_db.sqlite'
OUT = Path(__file__).resolve().parent.parent / '_archive'
MIN_PTS = 50
NEAR_FIELD_M = 20.0


def pattern_gain(angle_off, bw, mode):
    """A(angle) for horizontal/vertical term; returns (gain, dGain/draw) pieces.
    raw = 12*(off/bw)^2 ; hard: -min(raw,30) ; tanh: -30*tanh(raw/30)."""
    raw = 12.0 * (angle_off / bw) ** 2
    if mode == 'hard':
        gain = np.where(raw < MAX_ATTEN, -raw, -MAX_ATTEN)
        dgain_draw = np.where(raw < MAX_ATTEN, -1.0, 0.0)
    else:  # tanh
        t = np.tanh(raw / MAX_ATTEN)
        gain = -MAX_ATTEN * t
        dgain_draw = -(1.0 - t ** 2)  # -sech^2(raw/30)
    return gain, dgain_draw, raw


def predict_and_grad(params, pts, mode):
    bx, by, az, bw, tilt, h, n, p0 = params[:8]
    k = 180.0 / math.pi
    dx = pts[:, 0] - bx
    dy = pts[:, 1] - by
    d = np.maximum(np.hypot(dx, dy), 1.0)

    bearing = k * np.arctan2(dx, dy)
    dAz = (bearing - az + 180) % 360 - 180
    hdiff = h - UE_H
    elev = k * np.arctan2(hdiff, d)
    dEl = elev + tilt

    pl = p0 - 10.0 * n * np.log10(d)
    h_gain, dhg_draw, _ = pattern_gain(dAz, bw, mode)
    v_gain, dvg_draw, _ = pattern_gain(dEl, V_BW_FIXED, mode)
    fbr = np.where(np.abs(dAz) > 90, -FBR, 0.0)
    pred = pl + h_gain + v_gain + fbr

    err = pred - pts[:, 2]
    abs_e = np.abs(err)
    loss = np.where(abs_e <= DELTA, 0.5 * err**2, DELTA * (abs_e - 0.5 * DELTA)).sum()
    de = np.where(abs_e <= DELTA, err, DELTA * np.sign(err))

    d2 = np.maximum(dx**2 + dy**2, 1.0)
    dbear_bx = -dy * k / d2
    dbear_by = dx * k / d2
    delev_h = d * k / (d2 + hdiff**2)
    delev_d = -hdiff * k / (d2 + hdiff**2)
    ddist_bx = -dx / d
    ddist_by = -dy / d
    delev_bx = delev_d * ddist_bx
    delev_by = delev_d * ddist_by

    # d(raw)/d(off) = 24*off/bw^2 ; d(raw)/d(bw) = -24*off^2/bw^3
    dh_ddAz = dhg_draw * 24.0 * dAz / bw**2
    dh_bx = dh_ddAz * dbear_bx
    dh_by = dh_ddAz * dbear_by
    dh_az = -dh_ddAz
    dh_bw = dhg_draw * (-24.0 * dAz**2 / bw**3)

    dv_ddEl = dvg_draw * 24.0 * dEl / V_BW_FIXED**2
    dv_bx = dv_ddEl * delev_bx
    dv_by = dv_ddEl * delev_by
    dv_tilt = dv_ddEl
    dv_h = dv_ddEl * delev_h

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
    return loss, grad


def predict(params, pts, mode):
    bx, by, az, bw, tilt, h, n, p0 = params[:8]
    k = 180.0 / math.pi
    dx = pts[:, 0] - bx
    dy = pts[:, 1] - by
    d = np.maximum(np.hypot(dx, dy), 1.0)
    dAz = ((k * np.arctan2(dx, dy)) - az + 180) % 360 - 180
    dEl = k * np.arctan2(h - UE_H, d) + tilt
    pl = p0 - 10.0 * n * np.log10(d)
    h_gain, _, _ = pattern_gain(dAz, bw, mode)
    v_gain, _, _ = pattern_gain(dEl, V_BW_FIXED, mode)
    fbr = np.where(np.abs(dAz) > 90, -FBR, 0.0)
    return pl + h_gain + v_gain + fbr


def rmse(params, pts, mode):
    r = predict(params, pts, mode) - pts[:, 2]
    return math.sqrt((r**2).mean())


def estimate(pts, mode):
    best, best_rmse = None, 1e18
    for p0 in initial_guesses(pts, adaptive=False):
        r = minimize(predict_and_grad, p0, args=(pts, mode), jac=True,
                     method='L-BFGS-B', bounds=bounds_for(pts, False),
                     options={'maxiter': 500})
        cur = rmse(r.x, pts, mode)
        if cur < best_rmse:
            best, best_rmse = r.x, cur
    return best, best_rmse


def estimate_near_filtered(pts, mode):
    """Two-pass: estimate, drop points closer than NEAR_FIELD_M to the site, refit."""
    p1, _ = estimate(pts, mode)
    if p1 is None:
        return estimate(pts, mode)
    d = np.hypot(pts[:, 0] - p1[0], pts[:, 1] - p1[1])
    keep = d >= NEAR_FIELD_M
    if keep.sum() < 10 or keep.all():
        return p1, rmse(p1, pts, mode)
    p2, _ = estimate(pts[keep], mode)
    return p2, rmse(p2, pts, mode)


def main():
    conn = sqlite3.connect(DB)
    df = pd.read_sql(
        "SELECT eci, timestamp, latitude, longitude, rsrp, gps_accuracy FROM measurements", conn)
    conn.close()
    df = df[(df['eci'] > 0) & (df['gps_accuracy'] < 15)]

    rows = []
    for eci, g in df.groupby('eci'):
        g = g.sort_values('timestamp')
        if len(g) < MIN_PTS:
            continue
        lat0, lng0 = g['latitude'].iloc[0], g['longitude'].iloc[0]
        east = (g['longitude'] - lng0) * math.pi / 180 * EARTH_R * math.cos(math.radians(lat0))
        north = (g['latitude'] - lat0) * math.pi / 180 * EARTH_R
        pts = np.column_stack([east.values, north.values, g['rsrp'].values.astype(float)])

        pa, _ = estimate(pts, 'hard')
        pb, _ = estimate(pts, 'tanh')
        pc, _ = estimate_near_filtered(pts, 'tanh')
        # RMSE always evaluated against measured data with each variant's own model
        ra = rmse(pa, pts, 'hard')
        rb = rmse(pb, pts, 'tanh')
        rc = rmse(pc, pts, 'tanh')

        fit_pts, val_pts = pts[0::2], pts[1::2]
        qa, _ = estimate(fit_pts, 'hard')
        qb, _ = estimate(fit_pts, 'tanh')
        qc, _ = estimate_near_filtered(fit_pts, 'tanh')
        cva = rmse(qa, val_pts, 'hard')
        cvb = rmse(qb, val_pts, 'tanh')
        cvc = rmse(qc, val_pts, 'tanh')

        # parameter shifts B vs A
        dist_ab = math.hypot(pa[0] - pb[0], pa[1] - pb[1])
        daz_ab = abs((pa[2] - pb[2] + 180) % 360 - 180)
        near_cnt = int((np.hypot(pts[:, 0] - pa[0], pts[:, 1] - pa[1]) < NEAR_FIELD_M).sum())

        rows.append(dict(eci=eci, n=len(pts), near_pts=near_cnt,
                         rmseA=ra, rmseB=rb, rmseC=rc,
                         cvA=cva, cvB=cvb, cvC=cvc,
                         pos_diff_ab=dist_ab, az_diff_ab=daz_ab,
                         tiltA=pa[4], tiltB=pb[4], hA=pa[5], hB=pb[5]))
        print(f"ECI {eci} (n={len(pts)}, near={near_cnt}): "
              f"RMSE A={ra:.2f} B={rb:.2f} C={rc:.2f} | "
              f"CV A={cva:.2f} B={cvb:.2f} C={cvc:.2f} | "
              f"A<->B shift {dist_ab:.1f}m/{daz_ab:.1f}deg")

    res = pd.DataFrame(rows)
    res.to_csv(OUT / 'ab_clip_results.csv', index=False)
    print(f"\n=== Summary over {len(res)} cells ===")
    for m in ['rmse', 'cv']:
        a, b, c = res[f'{m}A'], res[f'{m}B'], res[f'{m}C']
        label = 'in-sample' if m == 'rmse' else 'cross-val'
        print(f"{label}: A(hard) {a.mean():.3f} | B(tanh) {b.mean():.3f} | C(tanh+near) {c.mean():.3f}")
        print(f"   B wins vs A: {(b < a).sum()}/{len(res)} | C wins vs A: {(c < a).sum()}/{len(res)} | "
              f"C wins vs B: {(c < b).sum()}/{len(res)}")
    print(f"A<->B position shift: mean {res.pos_diff_ab.mean():.1f} m, max {res.pos_diff_ab.max():.1f} m")
    print(f"A<->B azimuth shift:  mean {res.az_diff_ab.mean():.1f} deg, max {res.az_diff_ab.max():.1f}")
    print(f"cells with near-field points (<{NEAR_FIELD_M}m): {(res.near_pts > 0).sum()}/{len(res)}")

    # focused look at cells that have clipped/near points
    focus = res[(res.near_pts > 0)].sort_values('near_pts', ascending=False)
    if len(focus):
        print("\ncells with near-field points:")
        for _, r in focus.iterrows():
            print(f"  {r.eci}: near={r.near_pts}, tilt {r.tiltA:.1f}->{r.tiltB:.1f}, "
                  f"h {r.hA:.1f}->{r.hB:.1f}, CV {r.cvA:.2f}->{r.cvB:.2f}->{r.cvC:.2f}")

    import matplotlib.pyplot as plt
    x = np.arange(len(res))
    fig, axes = plt.subplots(1, 2, figsize=(15, 5))
    for ax, m, title in [(axes[0], 'rmse', 'In-sample RMSE'), (axes[1], 'cv', 'Cross-validation RMSE')]:
        ax.bar(x - 0.28, res[f'{m}A'], 0.28, label='A: hard clip', color='#4472C4')
        ax.bar(x, res[f'{m}B'], 0.28, label='B: tanh smooth', color='#ED7D31')
        ax.bar(x + 0.28, res[f'{m}C'], 0.28, label='C: tanh + near-field filter', color='#70AD47')
        ax.set_title(title); ax.set_ylabel('RMSE (dB)'); ax.legend(fontsize=8)
        ax.set_xticks(x); ax.set_xticklabels([str(e)[-4:] for e in res.eci], rotation=45, fontsize=7)
        ax.set_xlabel('ECI (last 4 digits)')
    plt.tight_layout()
    fig.savefig(OUT / 'ab_clip_comparison.png', dpi=150, bbox_inches='tight')
    print('figure saved to', OUT / 'ab_clip_comparison.png')


if __name__ == '__main__':
    main()
