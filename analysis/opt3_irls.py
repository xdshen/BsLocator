"""
Opt 3: two-round IRLS-style refit.

Round 1: standard 8-param Huber fit. Drop points with |residual| > 10 dB,
round 2 refits on the cleaned set. Evaluation vs baseline:
  - dropped-point fraction (full data)
  - in-sample RMSE (round-2 params on raw full set) and split-half CV
    (each half cleaned by its own round-1 fit; raw other half validates)
  - position shift between round 1 and round 2 estimates
"""
import math
import sys
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parent))
from opt_common import load_cells, estimate_w, baseline_results, setup_cn_font, OUT
from ab_compare_vbw import predict, rmse

RESID_THRESH = 10.0
MIN_KEEP = 30


def irls2(pts):
    """Two-round fit. Returns (params, drop_fraction)."""
    p1 = estimate_w(pts)[0]
    r = predict(p1, pts, False) - pts[:, 2]
    keep = np.abs(r) <= RESID_THRESH
    if keep.sum() < max(MIN_KEEP, int(0.5 * len(pts))):
        return p1, 1.0 - keep.mean()
    p2 = estimate_w(pts[keep])[0]
    return p2, 1.0 - keep.mean()


def main():
    cells = load_cells()
    base = baseline_results(cells)

    rows = []
    for eci, c in cells.items():
        pts = c['pts']
        p1, drop = irls2(pts)
        rm2 = rmse(p1, pts, False)
        pe, _ = irls2(pts[0::2])
        po, _ = irls2(pts[1::2])
        cv2 = 0.5 * (rmse(pe, pts[1::2], False) + rmse(po, pts[0::2], False))
        stab = math.hypot(pe[0] - po[0], pe[1] - po[1])
        # round1 vs round2 position shift on full data
        p_r1 = estimate_w(pts)[0]
        shift12 = math.hypot(p_r1[0] - p1[0], p_r1[1] - p1[1])

        b = base[eci]
        rows.append(dict(eci=eci, n=len(pts), drop_frac=drop,
                         base_rmse=b['rmse'], base_cv=b['cv'], base_stab=b['pos_stab'],
                         irls_rmse=rm2, irls_cv=cv2, irls_stab=stab, shift12_m=shift12))
        print(f"ECI {eci} n={len(pts)} drop={drop:.1%} | rmse {b['rmse']:.2f}->{rm2:.2f} | "
              f"cv {b['cv']:.2f}->{cv2:.2f} | stab {b['pos_stab']:.1f}->{stab:.1f}m | "
              f"r1->r2 shift {shift12:.1f}m", flush=True)

    res = pd.DataFrame(rows)
    res.to_csv(OUT / 'opt3_irls_cells.csv', index=False)

    print(f"\n=== Opt3 summary over {len(res)} cells ===")
    print(f"dropped fraction: mean {res.drop_frac.mean():.1%}, max {res.drop_frac.max():.1%}")
    print(f"in-sample RMSE: base {res.base_rmse.mean():.3f} -> irls {res.irls_rmse.mean():.3f} "
          f"(win {(res.irls_rmse < res.base_rmse).sum()}/{len(res)})")
    print(f"CV RMSE:        base {res.base_cv.mean():.3f} -> irls {res.irls_cv.mean():.3f} "
          f"(win {(res.irls_cv < res.base_cv).sum()}/{len(res)})")
    print(f"half-fit pos stability: base {res.base_stab.mean():.1f}m -> irls {res.irls_stab.mean():.1f}m")
    print(f"round1->round2 position shift: mean {res.shift12_m.mean():.1f}m, max {res.shift12_m.max():.1f}m")

    # ---- figure ----
    plt = setup_cn_font()
    fig, axes = plt.subplots(1, 3, figsize=(17, 5))
    x = np.arange(len(res))
    w = 0.38

    ax = axes[0]
    ax.bar(x - w / 2, res.base_cv, w, label='基线（单轮 Huber）', color='#4472C4')
    ax.bar(x + w / 2, res.irls_cv, w, label='两轮 IRLS', color='#ED7D31')
    ax.set_title('交叉验证 RMSE'); ax.set_ylabel('CV RMSE (dB)')
    ax.set_xticks(x[::2]); ax.set_xticklabels([str(e)[-4:] for e in res.eci[::2]],
                                              rotation=45, fontsize=7)
    ax.set_xlabel('ECI（后4位）'); ax.legend()

    ax = axes[1]
    ax.scatter(res.drop_frac * 100, res.base_cv - res.irls_cv, color='#7030A0')
    ax.axhline(0, color='gray', ls='--', lw=1)
    ax.set_xlabel('剔除点比例 (%)'); ax.set_ylabel('CV RMSE 改善 (dB, 正=更好)')
    ax.set_title('剔除比例 vs 泛化改善')

    ax = axes[2]
    ax.hist(res.shift12_m, bins=20, color='#4472C4', edgecolor='white')
    ax.set_xlabel('第一轮→第二轮位置偏移 (m)'); ax.set_ylabel('小区数')
    ax.set_title('IRLS 重拟合的位置修正幅度')

    plt.tight_layout()
    fig.savefig(OUT / 'opt_3_irls.png', dpi=150, bbox_inches='tight')
    print('figure saved to', OUT / 'opt_3_irls.png')

    summary = pd.DataFrame([dict(
        opt='3_irls_two_round',
        base_insample=res.base_rmse.mean(), opt_insample_A=res.irls_rmse.mean(), opt_insample_B=np.nan,
        base_cv=res.base_cv.mean(), opt_cv_A=res.irls_cv.mean(), opt_cv_B=np.nan,
        cv_win_A=f"{(res.irls_cv < res.base_cv).sum()}/{len(res)}", cv_win_B='',
        base_stab_m=res.base_stab.mean(), stab_A_m=res.irls_stab.mean(), stab_B_m=np.nan,
        extra=f"drop_frac_mean={res.drop_frac.mean():.3f}; r1r2_shift_mean_m={res.shift12_m.mean():.1f}; "
              f"thresh={RESID_THRESH}dB",
    )])
    summary.to_csv(OUT / 'opt3_summary.csv', index=False)


if __name__ == '__main__':
    main()
