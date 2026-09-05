"""
Shared utilities for the 4 estimator-optimization evaluations (opt1..opt4).

- load_cells(): filtered real drive-test data per ECI in local ENU coordinates
- weighted 8-parameter estimator (hard 30 dB clip, Huber loss) reusing the
  gradient machinery of ab_compare_vbw.py
- eval_variant(): in-sample RMSE + split-half cross-validation (both directions)
  + parameter stability (position/azimuth difference between even-fit and odd-fit)
- baseline results are cached to _archive/opt_baseline_cache.pkl so the four
  scripts do not recompute them.
"""
import math
import pickle
import sqlite3
import sys
from pathlib import Path

import numpy as np
import pandas as pd
from scipy.optimize import minimize

sys.path.insert(0, str(Path(__file__).resolve().parent))
from ab_compare_vbw import (EARTH_R, UE_H, MAX_ATTEN, FBR, DELTA,
                            bounds_for, initial_guesses, predict, rmse)

DB = Path(__file__).resolve().parent.parent / '_archive' / 'latest_db.sqlite'
OUT = Path(__file__).resolve().parent.parent / '_archive'
MIN_PTS = 50
MAX_GPS_ACC = 15.0
RSSNR_INVALID = 2147483647


def load_cells():
    conn = sqlite3.connect(DB)
    df = pd.read_sql(
        "SELECT eci, timestamp, latitude, longitude, rsrp, rsrq, rssnr, "
        "gps_accuracy, speed FROM measurements", conn)
    conn.close()
    df = df[(df['eci'] > 0) & (df['gps_accuracy'] < MAX_GPS_ACC)]

    cells = {}
    for eci, g in df.groupby('eci'):
        g = g.sort_values('timestamp').reset_index(drop=True)
        if len(g) < MIN_PTS:
            continue
        lat0, lng0 = g['latitude'].iloc[0], g['longitude'].iloc[0]
        east = (g['longitude'] - lng0) * math.pi / 180 * EARTH_R * math.cos(math.radians(lat0))
        north = (g['latitude'] - lat0) * math.pi / 180 * EARTH_R
        pts = np.column_stack([east.values, north.values, g['rsrp'].values.astype(float)])
        rssnr = g['rssnr'].values.astype(float)
        rssnr[rssnr >= RSSNR_INVALID] = np.nan
        cells[int(eci)] = dict(
            pts=pts,
            ts=g['timestamp'].values.astype(float),
            rsrq=g['rsrq'].values.astype(float),
            rssnr=rssnr,
            gps_speed=g['speed'].values.astype(float),
        )
    return cells


def predict_and_grad_w(params, pts, w):
    """Weighted Huber loss, hard-clip pattern (mirrors BaseStationEstimator.kt)."""
    bx, by, az, bw, tilt, h, n, p0 = params[:8]
    vbw = 10.0
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
    loss = (w * np.where(abs_e <= DELTA, 0.5 * err ** 2, DELTA * (abs_e - 0.5 * DELTA))).sum()
    de = w * np.where(abs_e <= DELTA, err, DELTA * np.sign(err))

    d2 = np.maximum(dx ** 2 + dy ** 2, 1.0)
    dbear_bx = -dy * k / d2
    dbear_by = dx * k / d2
    delev_h = d * k / (d2 + hdiff ** 2)
    delev_d = -hdiff * k / (d2 + hdiff ** 2)
    ddist_bx = -dx / d
    ddist_by = -dy / d
    delev_bx = delev_d * ddist_bx
    delev_by = delev_d * ddist_by

    act_h = h_raw < MAX_ATTEN
    dh_ddAz = np.where(act_h, -24.0 * dAz / bw ** 2, 0.0)
    dh_bx = dh_ddAz * dbear_bx
    dh_by = dh_ddAz * dbear_by
    dh_az = np.where(act_h, 24.0 * dAz / bw ** 2, 0.0)
    dh_bw = np.where(act_h, 24.0 * dAz ** 2 / bw ** 3, 0.0)

    act_v = v_raw < MAX_ATTEN
    dv_ddEl = np.where(act_v, -24.0 * dEl / vbw ** 2, 0.0)
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


def estimate_w(pts, w=None):
    """8-param estimate with optional per-point weights. Returns (params, rmse_on_pts)."""
    if w is None:
        w = np.ones(len(pts))
    best, best_rmse = None, 1e18
    for p0 in initial_guesses(pts, adaptive=False):
        r = minimize(predict_and_grad_w, p0, args=(pts, w), jac=True,
                     method='L-BFGS-B', bounds=bounds_for(pts, False),
                     options={'maxiter': 500})
        cur = rmse(r.x, pts, False)
        if cur < best_rmse:
            best, best_rmse = r.x, cur
    return best, best_rmse


def eval_variant(pts, fit_fn):
    """fit_fn(sub_pts) -> params. All RMSEs evaluated on RAW points (8-param model).

    Returns dict with in-sample RMSE, split-half CV (even->odd, odd->even, mean),
    and stability = position/azimuth difference between the two half-fits.
    """
    p_full = fit_fn(pts)
    rm_full = rmse(p_full, pts, False)
    pe = fit_fn(pts[0::2])
    po = fit_fn(pts[1::2])
    cv_eo = rmse(pe, pts[1::2], False)
    cv_oe = rmse(po, pts[0::2], False)
    pos_stab = math.hypot(pe[0] - po[0], pe[1] - po[1])
    az_stab = abs((pe[2] - po[2] + 180) % 360 - 180)
    return dict(rmse=rm_full, cv_eo=cv_eo, cv_oe=cv_oe, cv=0.5 * (cv_eo + cv_oe),
                pos_stab=pos_stab, az_stab=az_stab, params=p_full)


def baseline_results(cells):
    """Cached baseline (8-param, hard clip, no preprocessing) per cell."""
    cache = OUT / 'opt_baseline_cache.pkl'
    if cache.exists():
        with open(cache, 'rb') as f:
            return pickle.load(f)
    res = {}
    for eci, c in cells.items():
        res[eci] = eval_variant(c['pts'], lambda p: estimate_w(p)[0])
        print(f"[baseline] ECI {eci} n={len(c['pts'])} "
              f"rmse={res[eci]['rmse']:.2f} cv={res[eci]['cv']:.2f}", flush=True)
    with open(cache, 'wb') as f:
        pickle.dump(res, f)
    return res


def setup_cn_font():
    import matplotlib.pyplot as plt
    plt.rcParams['font.sans-serif'] = ['SimHei']
    plt.rcParams['axes.unicode_minus'] = False
    return plt
