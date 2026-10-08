"""
Map-based visual verification for the 4 estimator optimizations.

For each optimization, 2-3 representative cells (biggest CV win + a counter
example) are drawn on Amap (GCJ-02) tiles:
  - measurement points colored by RSRP (RdYlGn)
  - baseline estimate (red star + red sector) vs optimized (blue star + sector)
  - dashed connector with position shift annotation
  - title: ECI, split-half CV RMSE of both, azimuth/tilt of both
  - footer: the adopt/reject verdict rationale

Outputs: _archive/optviz_1_motion.png ... _archive/optviz_4_nlos.png
"""
import math
import sys
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parent))
from opt_common import load_cells, estimate_w, setup_cn_font, OUT
from ab_smooth_clip import estimate as estimate_hard, rmse as rmse_hard
from opt1_motion_weight import dedup_stationary, stationary_runs
from opt2_spatial_bin import bin_points
from opt3_irls import irls2
from opt4_nlos_sinr_rsrq import weights_nlos
from viz_position_compare_map import (GAODE_TILES, wgs84_to_gcj02, enu_to_ll,
                                      sector_ll, sector_radius)

RED, BLUE = '#C5504B', '#2196F3'


def ll(pts_xy, lat0, lng0):
    lat, lng = enu_to_ll(pts_xy[:, 0], pts_xy[:, 1], lat0, lng0)
    conv = np.array([wgs84_to_gcj02(a, b) for a, b in zip(np.atleast_1d(lat), np.atleast_1d(lng))])
    return conv[:, 1], conv[:, 0]  # lng, lat


def draw_panel(ax, c, p_base, p_opt, cv_b, cv_o, base_lab, opt_lab,
               extra_title='', highlight_mask=None, extra_marks=None):
    pts, lat0, lng0 = c['pts'], c['lat0'], c['lng0']
    x, y = ll(pts, lat0, lng0)
    sc = ax.scatter(x, y, c=pts[:, 2], cmap='RdYlGn', s=12, alpha=0.85,
                    edgecolor='none', zorder=4, vmin=-100, vmax=-50)
    if highlight_mask is not None and highlight_mask.any():
        xh, yh = ll(pts[highlight_mask], lat0, lng0)
        ax.scatter(xh, yh, facecolor='none', edgecolor='k', s=26, lw=0.7,
                   zorder=5, label='静止段点')

    for p, color, lab in [(p_base, RED, base_lab), (p_opt, BLUE, opt_lab)]:
        rad = sector_radius(p, pts)
        sx, sy = sector_ll(p[0], p[1], p[2], p[3], rad, lat0, lng0)
        conv = np.array([wgs84_to_gcj02(a, b) for a, b in zip(sy, sx)])
        ax.fill(conv[:, 1], conv[:, 0], color=color, alpha=0.13, zorder=2)
        ax.plot(conv[:, 1], conv[:, 0], color=color, lw=1.4, alpha=0.8, zorder=3)
        gx, gy = ll(np.array([[p[0], p[1]]]), lat0, lng0)
        ax.scatter(gx, gy, marker='*', s=500, color=color, edgecolor='k',
                   linewidth=1.2, zorder=6, label=lab)

    if extra_marks:  # list of (params, marker_label) drawn as small dots
        gx, gy = ll(np.array([[p[0], p[1]] for p in extra_marks[0]]), lat0, lng0)
        ax.scatter(gx, gy, marker='x', s=60, color=extra_marks[1], lw=1.5,
                   zorder=5, label=extra_marks[2])

    ga = ll(np.array([[p_base[0], p_base[1]]]), lat0, lng0)
    gb = ll(np.array([[p_opt[0], p_opt[1]]]), lat0, lng0)
    ax.plot([ga[0][0], gb[0][0]], [ga[1][0], gb[1][0]], 'k--', lw=1.5, alpha=0.6, zorder=5)

    shift = math.hypot(p_base[0] - p_opt[0], p_base[1] - p_opt[1])
    daz = abs((p_base[2] - p_opt[2] + 180) % 360 - 180)
    ax.text(0.02, 0.02, f'位置差 {shift:.0f} m, 方位角差 {daz:.1f}°',
            transform=ax.transAxes, fontsize=11, fontweight='bold', va='bottom',
            bbox=dict(boxstyle='round,pad=0.3', fc='white', ec='gray', alpha=0.9))
    ax.set_title(
        f'{extra_title}\n'
        f'基线: 方位 {p_base[2]:.0f}° 波束 {p_base[3]:.0f}° 下倾 {p_base[4]:.1f}° 高 {p_base[5]:.0f}m | '
        f'优化: 方位 {p_opt[2]:.0f}° 波束 {p_opt[3]:.0f}° 下倾 {p_opt[4]:.1f}° 高 {p_opt[5]:.0f}m\n'
        f'CV RMSE: 基线 {cv_b:.2f} dB → 优化 {cv_o:.2f} dB',
        fontsize=9.5)
    ax.legend(loc='upper right', fontsize=8.5)

    import contextily as ctx
    try:
        # 高德 style=8 瓦片最大 z17，更高层级返回空白占位图
        ctx.add_basemap(ax, crs='EPSG:4326', source=GAODE_TILES, zoom=17)
    except Exception as e:
        print(f'  basemap failed: {e}')
        ax.set_aspect('equal', adjustable='datalim')
        ax.grid(alpha=0.3)
    ax.tick_params(labelsize=7)
    return sc


def make_fig(panels, verdict, outfile):
    plt = setup_cn_font()
    n = len(panels)
    fig, axes = plt.subplots(1, n, figsize=(7.2 * n, 7.6))
    if n == 1:
        axes = [axes]
    sc = None
    for ax, p in zip(axes, panels):
        sc = draw_panel(ax, **p)
    cax = fig.add_axes([0.905, 0.20, 0.012, 0.55])
    fig.colorbar(sc, cax=cax, label='RSRP (dBm)')
    fig.text(0.45, 0.005, verdict, ha='center', fontsize=11, fontweight='bold',
             bbox=dict(boxstyle='round,pad=0.4', fc='#FFF2CC', ec='#BF9000'))
    plt.tight_layout(rect=[0, 0.035, 0.90, 1])
    fig.savefig(OUT / outfile, dpi=150, bbox_inches='tight')
    print('saved', OUT / outfile)


def get_cell_row(csv, eci):
    df = pd.read_csv(OUT / csv)
    return df, df[df.eci == eci].iloc[0]


def main():
    cells = load_cells()

    # ---------------- opt1: stationary dedup ----------------
    df1 = pd.read_csv(OUT / 'opt1_motion_cells.csv')
    hi = df1[df1.frac_stat > 0.25]
    best = hi.loc[(hi.base_cv - hi.B_cv).idxmax()]
    worst = df1.loc[(df1.B_cv - df1.base_cv).idxmax()]
    picks = [(int(best.eci), f'静止占比 {best.frac_stat:.0%}，改善最大'),
             (int(worst.eci), f'静止占比 {worst.frac_stat:.0%}，反例（变差最大）')]
    panels = []
    for eci, note in picks:
        c = cells[eci]
        p_b, _ = estimate_hard(c['pts'], 'hard')
        p_o, _ = estimate_w(dedup_stationary(c['pts'], c['ts']))
        r = df1[df1.eci == eci].iloc[0]
        panels.append(dict(c=c, p_base=p_b, p_opt=p_o, cv_b=r.base_cv, cv_o=r.B_cv,
                           base_lab='基线（原始点）', opt_lab='静止段去重',
                           extra_title=f'ECI {eci} — {note}',
                           highlight_mask=stationary_runs(c['pts'], c['ts'])))
        print(f'opt1 pick {eci}: {note}')
    make_fig(panels,
             '优化1 静止段去重：静止占比高的小区 CV 与稳定性明显改善（左），'
             '样本少/静止少的小区反而变差（右）；全体胜率 21/70 → 条件采纳（仅静止占比>30%且点数充足时启用）',
             'optviz_1_motion.png')

    # ---------------- opt2: spatial binning ----------------
    df2 = pd.read_csv(OUT / 'opt2_binning_cells.csv')
    jit = df2.loc[df2.g2_jitter.idxmax()]
    best2 = df2.loc[(df2.base_cv - df2.g2_cv).idxmax()]
    worst2 = df2.loc[(df2.g2_cv - df2.base_cv).idxmax()]
    panels = []
    for eci, note, show_jit in [
            (int(jit.eci), f'网格相位抖动最大（{jit.g2_jitter:.0f}m）', True),
            (int(best2.eci), 'CV 改善最大', False),
            (int(worst2.eci), '反例（CV 变差最大）', False)]:
        c = cells[eci]
        p_b, _ = estimate_hard(c['pts'], 'hard')
        p_o, _ = estimate_w(bin_points(c['pts'], 2.0))
        r = df2[df2.eci == eci].iloc[0]
        marks = None
        if show_jit:
            ph = [estimate_w(bin_points(c['pts'], 2.0, ox, oy))[0]
                  for ox in (0.0, 1.0) for oy in (0.0, 1.0)]
            marks = (ph, '#7030A0', '4 种网格相位的估计')
        panels.append(dict(c=c, p_base=p_b, p_opt=p_o, cv_b=r.base_cv, cv_o=r.g2_cv,
                           base_lab='基线（原始点）', opt_lab='2m 分箱',
                           extra_title=f'ECI {eci} — {note}', extra_marks=marks))
        print(f'opt2 pick {eci}: {note}')
    make_fig(panels,
             '优化2 空间分箱(2m)：CV 均值仅 7.08→6.87（胜 37/70）改善微弱且逐小区不稳定，'
             '网格原点相位还引入最高数百米的位置抖动（左图 × 标记）→ 不采纳',
             'optviz_2_binning.png')

    # ---------------- opt3: IRLS two-round ----------------
    df3 = pd.read_csv(OUT / 'opt3_irls_cells.csv')
    big = df3.loc[df3.shift12_m.idxmax()]
    best3 = df3.loc[(df3.base_cv - df3.irls_cv).idxmax()]
    worst3 = df3.loc[(df3.irls_cv - df3.base_cv).idxmax()]
    panels = []
    for eci, note in [(int(big.eci), f'两轮位置跳变最大（{big.shift12_m:.0f}m）'),
                      (int(best3.eci), 'CV 改善最大'),
                      (int(worst3.eci), '反例（CV 变差最大）')]:
        c = cells[eci]
        p_b, _ = estimate_hard(c['pts'], 'hard')
        p_o, drop = irls2(c['pts'])
        r = df3[df3.eci == eci].iloc[0]
        panels.append(dict(c=c, p_base=p_b, p_opt=p_o, cv_b=r.base_cv, cv_o=r.irls_cv,
                           base_lab='基线（单轮 Huber）', opt_lab='IRLS 第二轮',
                           extra_title=f'ECI {eci} — {note}（剔除 {r.drop_frac:.0%}）'))
        print(f'opt3 pick {eci}: {note}')
    make_fig(panels,
             '优化3 IRLS 两轮：第二轮常跳到数百米外的另一个局部最优（左），CV 胜率仅 14/70、'
             '两轮间位置平均跳变 171m → 不采纳（Huber δ=5 已足够鲁棒，硬剔除丢信息）',
             'optviz_3_irls.png')

    # ---------------- opt4: SINR/RSRQ NLOS down-weight ----------------
    df4 = pd.read_csv(OUT / 'opt4_nlos_cells.csv')
    act = df4[df4.bad_frac > 0.02]
    best4 = act.loc[(act.base_cv - act.w_cv).idxmax()]
    worst4 = act.loc[(act.w_cv - act.base_cv).idxmax()]
    panels = []
    for eci, note in [(int(best4.eci), f'CV 改善最大（降权点 {best4.bad_frac:.0%}）'),
                      (int(worst4.eci), f'反例：CV 变差最大（降权点 {worst4.bad_frac:.0%}）')]:
        c = cells[eci]
        p_b, _ = estimate_hard(c['pts'], 'hard')
        w, _ = weights_nlos(c['rssnr'], c['rsrq'])
        p_o, _ = estimate_w(c['pts'], w)
        r = df4[df4.eci == eci].iloc[0]
        panels.append(dict(c=c, p_base=p_b, p_opt=p_o, cv_b=r.base_cv, cv_o=r.w_cv,
                           base_lab='基线（等权）', opt_lab='NLOS 降权(×0.5)',
                           extra_title=f'ECI {eci} — {note}'))
        print(f'opt4 pick {eci}: {note}')
    make_fig(panels,
             '优化4 SINR/RSRQ 降权：残差与 SINR/RSRQ 基本无关（ρ=0.11/0.01），降权后 CV 胜率仅 15/70，'
             '个别小区位置大幅漂移（右）→ 不采纳',
             'optviz_4_nlos.png')


if __name__ == '__main__':
    main()
