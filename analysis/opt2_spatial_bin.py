"""
Opt 2: spatial binning + linear-domain averaging.

Trajectory samples are snapped to a square grid (1 m and 2 m). Inside each bin:
  - RSRP is averaged in linear power (mean of 10^(rsrp/10)) and converted back
    to dBm
  - coordinates are the arithmetic mean of the bin members

Evaluation vs baseline (plain 8-param fit):
  - point-count reduction
  - in-sample RMSE (binned fit evaluated on raw points) and split-half CV
    (fit half is binned, raw other half is used for validation)
  - parameter stability: position-estimate spread over 4 grid-origin phases
    (0 or gs/2 offset in x/y) on the full data, plus even/odd half-fit spread
"""
import math
import sys
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parent))
from opt_common import load_cells, estimate_w, baseline_results, setup_cn_font, OUT
from ab_compare_vbw import rmse

GRID_SIZES = [1.0, 2.0]


def bin_points(pts, gs, ox=0.0, oy=0.0):
    ix = np.floor((pts[:, 0] + ox) / gs).astype(int)
    iy = np.floor((pts[:, 1] + oy) / gs).astype(int)
    key = ix.astype(np.int64) * 1_000_000 + iy
    out = []
    for k in np.unique(key):
        m = key == k
        lin = 10.0 ** (pts[m, 2] / 10.0)
        out.append([pts[m, 0].mean(), pts[m, 1].mean(), 10.0 * math.log10(lin.mean())])
    return np.array(out)


def eval_binned(pts, gs):
    p_full = estimate_w(bin_points(pts, gs))[0]
    rm_full = rmse(p_full, pts, False)
    pe = estimate_w(bin_points(pts[0::2], gs))[0]
    po = estimate_w(bin_points(pts[1::2], gs))[0]
    cv = 0.5 * (rmse(pe, pts[1::2], False) + rmse(po, pts[0::2], False))
    stab = math.hypot(pe[0] - po[0], pe[1] - po[1])
    # grid-phase jitter on full data
    pos = []
    for ox in (0.0, gs / 2):
        for oy in (0.0, gs / 2):
            p = estimate_w(bin_points(pts, gs, ox, oy))[0]
            pos.append(p[:2])
    pos = np.array(pos)
    jitter = float(np.sqrt(((pos - pos.mean(axis=0)) ** 2).sum(axis=1)).mean())
    return rm_full, cv, stab, jitter


def main():
    cells = load_cells()
    base = baseline_results(cells)

    rows = []
    for eci, c in cells.items():
        pts = c['pts']
        row = dict(eci=eci, n=len(pts),
                   base_rmse=base[eci]['rmse'], base_cv=base[eci]['cv'],
                   base_stab=base[eci]['pos_stab'])
        msg = f"ECI {eci} n={len(pts)}"
        for gs in GRID_SIZES:
            nb = len(bin_points(pts, gs))
            rm_, cv_, stab_, jit_ = eval_binned(pts, gs)
            tag = f"g{int(gs)}"
            row.update({f'{tag}_nbin': nb, f'{tag}_rmse': rm_, f'{tag}_cv': cv_,
                        f'{tag}_stab': stab_, f'{tag}_jitter': jit_})
            msg += f" | {gs:.0f}m: n->{nb} cv={cv_:.2f} stab={stab_:.1f} jit={jit_:.1f}"
        rows.append(row)
        print(msg, flush=True)

    res = pd.DataFrame(rows)
    res.to_csv(OUT / 'opt2_binning_cells.csv', index=False)

    print(f"\n=== Opt2 summary over {len(res)} cells ===")
    print(f"points kept: 1m bin {res.g1_nbin.sum()/res.n.sum():.1%}, "
          f"2m bin {res.g2_nbin.sum()/res.n.sum():.1%}")
    for tag in ['g1', 'g2']:
        win = (res[f'{tag}_cv'] < res.base_cv).sum()
        print(f"[{tag}] in-sample base {res.base_rmse.mean():.3f} -> {res[f'{tag}_rmse'].mean():.3f} | "
              f"CV base {res.base_cv.mean():.3f} -> {res[f'{tag}_cv'].mean():.3f} "
              f"(win {win}/{len(res)}) | "
              f"half-fit stab base {res.base_stab.mean():.1f}m -> {res[f'{tag}_stab'].mean():.1f}m | "
              f"grid-phase jitter {res[f'{tag}_jitter'].mean():.2f}m")

    # ---- figure ----
    plt = setup_cn_font()
    fig, axes = plt.subplots(1, 3, figsize=(17, 5))

    ax = axes[0]
    x = np.arange(len(res))
    w = 0.27
    ax.bar(x - w, res.base_cv, w, label='基线（原始点）', color='#4472C4')
    ax.bar(x, res.g1_cv, w, label='1m 分箱', color='#ED7D31')
    ax.bar(x + w, res.g2_cv, w, label='2m 分箱', color='#70AD47')
    ax.set_title('交叉验证 RMSE'); ax.set_ylabel('CV RMSE (dB)')
    ax.set_xticks(x[::2]); ax.set_xticklabels([str(e)[-4:] for e in res.eci[::2]],
                                              rotation=45, fontsize=7)
    ax.set_xlabel('ECI（后4位）'); ax.legend()

    ax = axes[1]
    ax.bar(x - w, res.base_stab, w, label='基线', color='#4472C4')
    ax.bar(x, res.g1_stab, w, label='1m 分箱', color='#ED7D31')
    ax.bar(x + w, res.g2_stab, w, label='2m 分箱', color='#70AD47')
    ax.set_title('参数稳定性：偶/奇半折位置差'); ax.set_ylabel('位置差 (m)')
    ax.set_xticks(x[::2]); ax.set_xticklabels([str(e)[-4:] for e in res.eci[::2]],
                                              rotation=45, fontsize=7)
    ax.set_xlabel('ECI（后4位）'); ax.legend()

    ax = axes[2]
    red1 = 1 - res.g1_nbin / res.n
    red2 = 1 - res.g2_nbin / res.n
    ax.scatter(red1 * 100, res.base_cv - res.g1_cv, label='1m', color='#ED7D31')
    ax.scatter(red2 * 100, res.base_cv - res.g2_cv, label='2m', color='#70AD47', marker='^')
    ax.axhline(0, color='gray', ls='--', lw=1)
    ax.set_xlabel('点数压缩率 (%)'); ax.set_ylabel('CV RMSE 改善 (dB, 正=更好)')
    ax.set_title('分箱压缩 vs 泛化改善'); ax.legend()

    plt.tight_layout()
    fig.savefig(OUT / 'opt_2_binning.png', dpi=150, bbox_inches='tight')
    print('figure saved to', OUT / 'opt_2_binning.png')

    summary = pd.DataFrame([dict(
        opt='2_spatial_binning',
        base_insample=res.base_rmse.mean(),
        opt_insample_A=res.g1_rmse.mean(), opt_insample_B=res.g2_rmse.mean(),
        base_cv=res.base_cv.mean(), opt_cv_A=res.g1_cv.mean(), opt_cv_B=res.g2_cv.mean(),
        cv_win_A=f"{(res.g1_cv < res.base_cv).sum()}/{len(res)}",
        cv_win_B=f"{(res.g2_cv < res.base_cv).sum()}/{len(res)}",
        base_stab_m=res.base_stab.mean(), stab_A_m=res.g1_stab.mean(), stab_B_m=res.g2_stab.mean(),
        extra=f"A=1m bin kept {res.g1_nbin.sum()/res.n.sum():.3f}, jitter {res.g1_jitter.mean():.2f}m; "
              f"B=2m bin kept {res.g2_nbin.sum()/res.n.sum():.3f}, jitter {res.g2_jitter.mean():.2f}m",
    )])
    summary.to_csv(OUT / 'opt2_summary.csv', index=False)


if __name__ == '__main__':
    main()
