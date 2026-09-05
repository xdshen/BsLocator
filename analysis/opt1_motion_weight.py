"""
Opt 1: motion-state tagging & weighting.

No IMU available -> infer motion from the trajectory: displacement between
consecutive samples vs elapsed time. A *stationary run* = maximal run of
consecutive samples whose step displacement < 2 m, run length >= 3 samples
and run duration >= 5 s. Samples inside stationary runs are redundant.

Variants (baseline = plain 8-param hard-clip fit, from opt_common cache):
  A) down-weight: stationary-run samples get weight 1/run_length (others 1)
  B) de-duplicate: collapse each stationary run to one mean point
     (coordinates: arithmetic mean, RSRP: linear-power mean back to dBm)

Evaluation: in-sample RMSE on raw points, split-half CV (both directions),
parameter stability (position/azimuth difference between even/odd half-fits).
"""
import math
import sys
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parent))
from opt_common import load_cells, estimate_w, eval_variant, baseline_results, \
    setup_cn_font, OUT

STEP_M = 2.0        # max displacement per step to count as stationary
MIN_RUN = 3         # min samples in a stationary run
MIN_RUN_S = 5.0     # min run duration (seconds)


def stationary_runs(pts, ts):
    """Boolean mask of samples belonging to a stationary run."""
    n = len(pts)
    step = np.hypot(np.diff(pts[:, 0]), np.diff(pts[:, 1]))
    still = np.concatenate([[False], step < STEP_M])  # point i continues a still run
    mask = np.zeros(n, dtype=bool)
    i = 0
    while i < n:
        if not still[i]:
            i += 1
            continue
        j = i
        while j + 1 < n and still[j + 1]:
            j += 1
        # run covers points i-1..j (i-1 is the anchor where the run started)
        a = max(i - 1, 0)
        length = j - a + 1
        dur = (ts[j] - ts[a]) / 1000.0
        if length >= MIN_RUN and dur >= MIN_RUN_S:
            mask[a:j + 1] = True
        i = j + 1
    return mask


def dedup_stationary(pts, ts):
    """Collapse stationary runs to single mean points; returns new pts array."""
    mask = stationary_runs(pts, ts)
    keep = []
    i = 0
    n = len(pts)
    while i < n:
        if not mask[i]:
            keep.append(pts[i])
            i += 1
        else:
            j = i
            while j + 1 < n and mask[j + 1]:
                j += 1
            run = pts[i:j + 1]
            lin = 10.0 ** (run[:, 2] / 10.0)
            keep.append([run[:, 0].mean(), run[:, 1].mean(),
                         10.0 * math.log10(lin.mean())])
            i = j + 1
    return np.array(keep)


def weights_stationary(pts, ts):
    """Weight 1/run_length inside stationary runs, 1 elsewhere."""
    mask = stationary_runs(pts, ts)
    w = np.ones(len(pts))
    i = 0
    n = len(pts)
    while i < n:
        if not mask[i]:
            i += 1
            continue
        j = i
        while j + 1 < n and mask[j + 1]:
            j += 1
        w[i:j + 1] = 1.0 / (j - i + 1)
        i = j + 1
    return w


def main():
    cells = load_cells()
    base = baseline_results(cells)

    rows = []
    examples = {}
    for eci, c in cells.items():
        pts, ts = c['pts'], c['ts']
        mask = stationary_runs(pts, ts)
        frac = mask.mean()
        n_dedup = len(dedup_stationary(pts, ts))
        if frac > 0.3:
            examples[eci] = frac

        # full fit on variant-preprocessed data
        from opt_common import rmse as _rmse
        pA_full = estimate_w(pts, weights_stationary(pts, ts))[0]
        pB_full = estimate_w(dedup_stationary(pts, ts))[0]
        rmA = _rmse(pA_full, pts, False)
        rmB = _rmse(pB_full, pts, False)

        # CV halves
        ev = slice(0, None, 2)
        od = slice(1, None, 2)
        pA_e = estimate_w(pts[ev], weights_stationary(pts[ev], ts[ev]))[0]
        pA_o = estimate_w(pts[od], weights_stationary(pts[od], ts[od]))[0]
        pB_e = estimate_w(dedup_stationary(pts[ev], ts[ev]))[0]
        pB_o = estimate_w(dedup_stationary(pts[od], ts[od]))[0]
        cvA = 0.5 * (_rmse(pA_e, pts[od], False) + _rmse(pA_o, pts[ev], False))
        cvB = 0.5 * (_rmse(pB_e, pts[od], False) + _rmse(pB_o, pts[ev], False))
        stabA = math.hypot(pA_e[0] - pA_o[0], pA_e[1] - pA_o[1])
        stabB = math.hypot(pB_e[0] - pB_o[0], pB_e[1] - pB_o[1])

        b = base[eci]
        rows.append(dict(eci=eci, n=len(pts), frac_stat=frac, n_dedup=n_dedup,
                         base_rmse=b['rmse'], base_cv=b['cv'], base_stab=b['pos_stab'],
                         A_rmse=rmA, A_cv=cvA, A_stab=stabA,
                         B_rmse=rmB, B_cv=cvB, B_stab=stabB))
        print(f"ECI {eci} n={len(pts)} stat={frac:.0%} -> dedup n={n_dedup} | "
              f"CV base={b['cv']:.2f} A={cvA:.2f} B={cvB:.2f} | "
              f"stab base={b['pos_stab']:.1f} A={stabA:.1f} B={stabB:.1f} m", flush=True)

    res = pd.DataFrame(rows)
    res.to_csv(OUT / 'opt1_motion_cells.csv', index=False)

    def summ(col, ref):
        return f"{res[col].mean():.3f} (win {(res[col] < res[ref]).sum()}/{len(res)})"
    print(f"\n=== Opt1 summary over {len(res)} cells ===")
    print(f"stationary fraction: mean {res.frac_stat.mean():.1%}, "
          f"cells >30%: {(res.frac_stat > 0.3).sum()}, points kept after dedup: "
          f"{res.n_dedup.sum()}/{res.n.sum()} ({res.n_dedup.sum()/res.n.sum():.1%})")
    print(f"in-sample RMSE: base {res.base_rmse.mean():.3f} | A {summ('A_rmse','base_rmse')} | B {summ('B_rmse','base_rmse')}")
    print(f"CV RMSE:        base {res.base_cv.mean():.3f} | A {summ('A_cv','base_cv')} | B {summ('B_cv','base_cv')}")
    print(f"half-fit pos stability (m): base {res.base_stab.mean():.1f} | A {res.A_stab.mean():.1f} | B {res.B_stab.mean():.1f}")

    # ---- figure ----
    plt = setup_cn_font()
    fig, axes = plt.subplots(1, 3, figsize=(17, 5))

    # (1) example trajectory with stationary points highlighted
    ex_eci = max(examples, key=examples.get) if examples else int(res.iloc[res.frac_stat.argmax()].eci)
    c = cells[ex_eci]
    m = stationary_runs(c['pts'], c['ts'])
    ax = axes[0]
    ax.plot(c['pts'][:, 0], c['pts'][:, 1], '.', ms=3, color='#4472C4', alpha=0.5, label='移动采样')
    ax.plot(c['pts'][m, 0], c['pts'][m, 1], '.', ms=5, color='#ED7D31', label=f'静止段 ({m.mean():.0%})')
    ax.set_title(f'示例小区 ECI …{str(ex_eci)[-4:]}：静止段识别')
    ax.set_xlabel('east (m)'); ax.set_ylabel('north (m)'); ax.legend(); ax.set_aspect('equal')

    # (2) CV RMSE paired plot
    ax = axes[1]
    x = np.arange(len(res))
    ax.plot(x, res.base_cv.sort_values().values, 'o-', ms=3, label='基线', color='#4472C4')
    ax.plot(x, res.A_cv.iloc[res.base_cv.argsort()].values, 's-', ms=3, label='A 静止降权', color='#ED7D31')
    ax.plot(x, res.B_cv.iloc[res.base_cv.argsort()].values, '^-', ms=3, label='B 静止去重', color='#70AD47')
    ax.set_title('交叉验证 RMSE（按基线排序）'); ax.set_xlabel('小区（排序后）')
    ax.set_ylabel('CV RMSE (dB)'); ax.legend()

    # (3) improvement vs stationary fraction
    ax = axes[2]
    ax.axhline(0, color='gray', ls='--', lw=1)
    ax.scatter(res.frac_stat * 100, res.base_cv - res.A_cv, label='A 降权', color='#ED7D31')
    ax.scatter(res.frac_stat * 100, res.base_cv - res.B_cv, label='B 去重', color='#70AD47', marker='^')
    ax.set_xlabel('静止点占比 (%)'); ax.set_ylabel('CV RMSE 改善 (dB, 正=更好)')
    ax.set_title('改善幅度 vs 静止点占比'); ax.legend()

    plt.tight_layout()
    fig.savefig(OUT / 'opt_1_motion.png', dpi=150, bbox_inches='tight')
    print('figure saved to', OUT / 'opt_1_motion.png')

    summary = pd.DataFrame([dict(
        opt='1_motion_stationary',
        base_insample=res.base_rmse.mean(), opt_insample_A=res.A_rmse.mean(), opt_insample_B=res.B_rmse.mean(),
        base_cv=res.base_cv.mean(), opt_cv_A=res.A_cv.mean(), opt_cv_B=res.B_cv.mean(),
        cv_win_A=f"{(res.A_cv < res.base_cv).sum()}/{len(res)}",
        cv_win_B=f"{(res.B_cv < res.base_cv).sum()}/{len(res)}",
        base_stab_m=res.base_stab.mean(), stab_A_m=res.A_stab.mean(), stab_B_m=res.B_stab.mean(),
        extra=f"stat_frac_mean={res.frac_stat.mean():.3f}; pts_kept_dedup={res.n_dedup.sum()/res.n.sum():.3f}",
    )])
    summary.to_csv(OUT / 'opt1_summary.csv', index=False)


if __name__ == '__main__':
    main()
