"""
Opt 4: SINR/RSRQ-based NLOS screening.

Step 1 (analysis): residuals of the baseline full fit vs rssnr (SINR) and rsrq,
pooled over all cells - scatter + binned means + Spearman correlation, to test
the "low RSRP & low RSRQ & low SINR => NLOS => large residual" hypothesis.

Step 2 (only if correlation is visible): down-weight samples with
rssnr < 0 AND rsrq < -13 (weight 0.5) and re-evaluate CV RMSE.
"""
import math
import sys
from pathlib import Path

import numpy as np
import pandas as pd
from scipy.stats import spearmanr

sys.path.insert(0, str(Path(__file__).resolve().parent))
from opt_common import load_cells, estimate_w, baseline_results, setup_cn_font, OUT
from ab_compare_vbw import predict, rmse

SINR_TH = 0.0
RSRQ_TH = -13.0
LOW_W = 0.5


def weights_nlos(rssnr, rsrq):
    bad = (rssnr < SINR_TH) & (rsrq < RSRQ_TH)
    bad = np.where(np.isnan(rssnr), False, bad)
    return np.where(bad, LOW_W, 1.0), bad


def main():
    cells = load_cells()
    base = baseline_results(cells)

    # ---------- step 1: residual vs SINR/RSRQ ----------
    all_resid, all_sinr, all_rsrq, all_absr = [], [], [], []
    for eci, c in cells.items():
        pts = c['pts']
        p = base[eci]['params']
        r = predict(p, pts, False) - pts[:, 2]
        all_resid.append(r)
        all_absr.append(np.abs(r))
        all_sinr.append(c['rssnr'])
        all_rsrq.append(c['rsrq'])
    resid = np.concatenate(all_resid)
    absr = np.concatenate(all_absr)
    sinr = np.concatenate(all_sinr)
    rsrq = np.concatenate(all_rsrq)
    ok = ~np.isnan(sinr)

    rho_sinr = spearmanr(sinr[ok], absr[ok])
    rho_rsrq = spearmanr(rsrq[ok], absr[ok])
    print(f"Spearman(|resid|, SINR)  = {rho_sinr.statistic:.3f} (p={rho_sinr.pvalue:.2e})")
    print(f"Spearman(|resid|, RSRQ)  = {rho_rsrq.statistic:.3f} (p={rho_rsrq.pvalue:.2e})")

    def binned_mean(x, y, bins):
        idx = np.digitize(x, bins)
        out = []
        for b in range(1, len(bins)):
            m = idx == b
            out.append((np.nanmean(x[m]), np.nanmean(y[m]), m.sum()) if m.sum() else (np.nan, np.nan, 0))
        return out

    sinr_bins = binned_mean(sinr[ok], absr[ok], np.arange(-20, 40, 5))
    rsrq_bins = binned_mean(rsrq[ok], absr[ok], np.arange(-26, -8, 2))
    bad_mask = (sinr < SINR_TH) & (rsrq < RSRQ_TH) & ok
    print(f"'NLOS-like' (SINR<{SINR_TH:.0f} & RSRQ<{RSRQ_TH:.0f}): {bad_mask.sum()} pts "
          f"({bad_mask[ok].mean():.1%}), mean |resid| bad={absr[bad_mask].mean():.2f} dB "
          f"vs good={absr[ok & ~bad_mask].mean():.2f} dB")

    # ---------- step 2: weighted fit ----------
    rows = []
    for eci, c in cells.items():
        pts = c['pts']
        w_full, bad = weights_nlos(c['rssnr'], c['rsrq'])
        p_w = estimate_w(pts, w_full)[0]
        rm_w = rmse(p_w, pts, False)
        ev, od = slice(0, None, 2), slice(1, None, 2)
        w_e, _ = weights_nlos(c['rssnr'][ev], c['rsrq'][ev])
        w_o, _ = weights_nlos(c['rssnr'][od], c['rsrq'][od])
        pe = estimate_w(pts[ev], w_e)[0]
        po = estimate_w(pts[od], w_o)[0]
        cv_w = 0.5 * (rmse(pe, pts[od], False) + rmse(po, pts[ev], False))
        stab = math.hypot(pe[0] - po[0], pe[1] - po[1])
        b = base[eci]
        rows.append(dict(eci=eci, n=len(pts), bad_frac=bad.mean(),
                         base_rmse=b['rmse'], base_cv=b['cv'], base_stab=b['pos_stab'],
                         w_rmse=rm_w, w_cv=cv_w, w_stab=stab))
        print(f"ECI {eci} n={len(pts)} bad={bad.mean():.1%} | "
              f"cv {b['cv']:.2f}->{cv_w:.2f} | stab {b['pos_stab']:.1f}->{stab:.1f}m", flush=True)

    res = pd.DataFrame(rows)
    res.to_csv(OUT / 'opt4_nlos_cells.csv', index=False)

    print(f"\n=== Opt4 summary over {len(res)} cells ===")
    print(f"down-weighted point fraction: mean {res.bad_frac.mean():.1%}")
    print(f"in-sample RMSE: base {res.base_rmse.mean():.3f} -> weighted {res.w_rmse.mean():.3f} "
          f"(win {(res.w_rmse < res.base_rmse).sum()}/{len(res)})")
    print(f"CV RMSE:        base {res.base_cv.mean():.3f} -> weighted {res.w_cv.mean():.3f} "
          f"(win {(res.w_cv < res.base_cv).sum()}/{len(res)})")
    print(f"half-fit pos stability: base {res.base_stab.mean():.1f}m -> weighted {res.w_stab.mean():.1f}m")

    # ---------- figure ----------
    plt = setup_cn_font()
    fig, axes = plt.subplots(1, 4, figsize=(22, 5))

    ax = axes[0]
    ax.scatter(sinr[ok], absr[ok], s=2, alpha=0.15, color='#4472C4')
    xs = [b[0] for b in sinr_bins if not np.isnan(b[0])]
    ys = [b[1] for b in sinr_bins if not np.isnan(b[0])]
    ax.plot(xs, ys, 'o-', color='#ED7D31', lw=2, label='分箱均值')
    ax.set_xlabel('SINR / rssnr (dB)'); ax.set_ylabel('|拟合残差| (dB)')
    ax.set_title(f'|残差| vs SINR（Spearman ρ={rho_sinr.statistic:.2f}）'); ax.legend()
    ax.set_ylim(0, 40)

    ax = axes[1]
    ax.scatter(rsrq[ok], absr[ok], s=2, alpha=0.15, color='#4472C4')
    xs = [b[0] for b in rsrq_bins if not np.isnan(b[0])]
    ys = [b[1] for b in rsrq_bins if not np.isnan(b[0])]
    ax.plot(xs, ys, 'o-', color='#ED7D31', lw=2, label='分箱均值')
    ax.set_xlabel('RSRQ (dB)'); ax.set_ylabel('|拟合残差| (dB)')
    ax.set_title(f'|残差| vs RSRQ（Spearman ρ={rho_rsrq.statistic:.2f}）'); ax.legend()
    ax.set_ylim(0, 40)

    ax = axes[2]
    x = np.arange(len(res))
    w = 0.38
    ax.bar(x - w / 2, res.base_cv, w, label='基线', color='#4472C4')
    ax.bar(x + w / 2, res.w_cv, w, label='NLOS 降权', color='#ED7D31')
    ax.set_title('交叉验证 RMSE'); ax.set_ylabel('CV RMSE (dB)')
    ax.set_xticks(x[::2]); ax.set_xticklabels([str(e)[-4:] for e in res.eci[::2]],
                                              rotation=45, fontsize=7)
    ax.set_xlabel('ECI（后4位）'); ax.legend()

    ax = axes[3]
    ax.scatter(res.bad_frac * 100, res.base_cv - res.w_cv, color='#7030A0')
    ax.axhline(0, color='gray', ls='--', lw=1)
    ax.set_xlabel('被降权点比例 (%)'); ax.set_ylabel('CV RMSE 改善 (dB, 正=更好)')
    ax.set_title('降权比例 vs 泛化改善')

    plt.tight_layout()
    fig.savefig(OUT / 'opt_4_nlos.png', dpi=150, bbox_inches='tight')
    print('figure saved to', OUT / 'opt_4_nlos.png')

    summary = pd.DataFrame([dict(
        opt='4_nlos_sinr_rsrq_weight',
        base_insample=res.base_rmse.mean(), opt_insample_A=res.w_rmse.mean(), opt_insample_B=np.nan,
        base_cv=res.base_cv.mean(), opt_cv_A=res.w_cv.mean(), opt_cv_B=np.nan,
        cv_win_A=f"{(res.w_cv < res.base_cv).sum()}/{len(res)}", cv_win_B='',
        base_stab_m=res.base_stab.mean(), stab_A_m=res.w_stab.mean(), stab_B_m=np.nan,
        extra=f"spearman_sinr={rho_sinr.statistic:.3f}; spearman_rsrq={rho_rsrq.statistic:.3f}; "
              f"bad_frac_mean={res.bad_frac.mean():.3f}; rule: rssnr<{SINR_TH:.0f} & rsrq<{RSRQ_TH:.0f} w={LOW_W}",
    )])
    summary.to_csv(OUT / 'opt4_nlos_summary.csv', index=False)


if __name__ == '__main__':
    main()
