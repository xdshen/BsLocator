"""
Visual comparison: estimated BS position with hard clip (A) vs tanh smooth cap (B)
for a few typical cells. Each subplot shows the measurement points colored by RSRP,
both estimated positions with their recovered main-lobe sectors, and the shift.
"""
import math
import numpy as np
import pandas as pd
import sqlite3
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
from ab_compare_vbw import EARTH_R
from ab_smooth_clip import estimate, rmse

DB = Path(__file__).resolve().parent.parent / '_archive' / 'latest_db.sqlite'
OUT = Path(__file__).resolve().parent.parent / '_archive'

CELLS = [
    (4100030466, 'near-site point (d=7m)'),
    (4099854339, '19 near-site points'),
    (4097097730, 'dense stable cell (574 pts)'),
    (5786095617, 'clipped points, tilt=5.4'),
]


def load_cell(conn, eci):
    df = pd.read_sql(
        "SELECT timestamp, latitude, longitude, rsrp FROM measurements "
        "WHERE eci = ? AND gps_accuracy < 15 ORDER BY timestamp", conn, params=(eci,))
    lat0, lng0 = df['latitude'].iloc[0], df['longitude'].iloc[0]
    east = (df['longitude'] - lng0) * math.pi / 180 * EARTH_R * math.cos(math.radians(lat0))
    north = (df['latitude'] - lat0) * math.pi / 180 * EARTH_R
    pts = np.column_stack([east.values, north.values, df['rsrp'].values.astype(float)])
    return pts


def sector_pts(cx, cy, az_deg, bw_deg, radius, steps=40):
    half = math.radians(bw_deg / 2)
    start = math.radians(az_deg) - half
    angs = start + 2 * half * np.linspace(0, 1, steps)
    # azimuth: 0=north, clockwise -> dx = r*sin(a), dy = r*cos(a)
    xs = cx + radius * np.sin(angs)
    ys = cy + radius * np.cos(angs)
    return np.column_stack([np.concatenate([[cx], xs, [cx]]),
                            np.concatenate([[cy], ys, [cy]])])


def sector_radius(p, pts):
    d = np.hypot(pts[:, 0] - p[0], pts[:, 1] - p[1])
    az = (np.degrees(np.arctan2(pts[:, 0] - p[0], pts[:, 1] - p[1])) + 360) % 360
    daz = np.abs((az - p[2] + 180) % 360 - 180)
    main = d[daz <= p[3] / 2]
    base = np.percentile(main if len(main) else d, 90)
    return float(np.clip(base * 1.2, 100, 2000))


def main():
    import matplotlib.pyplot as plt
    conn = sqlite3.connect(DB)
    fig, axes = plt.subplots(2, 2, figsize=(14, 13))

    for ax, (eci, note) in zip(axes.flat, CELLS):
        pts = load_cell(conn, eci)
        pa, _ = estimate(pts, 'hard')
        pb, _ = estimate(pts, 'tanh')
        ra, rb = rmse(pa, pts, 'hard'), rmse(pb, pts, 'tanh')
        shift = math.hypot(pa[0] - pb[0], pa[1] - pb[1])
        daz = abs((pa[2] - pb[2] + 180) % 360 - 180)

        sc = ax.scatter(pts[:, 0], pts[:, 1], c=pts[:, 2], cmap='RdYlGn',
                        s=14, alpha=0.75, edgecolor='none')
        fig.colorbar(sc, ax=ax, label='RSRP (dBm)', fraction=0.046)

        for p, color, label, r in [
            (pa, '#C5504B', f'A: hard clip (RMSE {ra:.2f})', ra),
            (pb, '#2196F3', f'B: tanh smooth (RMSE {rb:.2f})', rb),
        ]:
            rad = sector_radius(p, pts)
            sec = sector_pts(p[0], p[1], p[2], p[3], rad)
            ax.fill(sec[:, 0], sec[:, 1], color=color, alpha=0.15)
            ax.plot(sec[:, 0], sec[:, 1], color=color, lw=1.2, alpha=0.7)
            ax.scatter(p[0], p[1], marker='*', s=420, color=color,
                       edgecolor='k', linewidth=1.2, zorder=6, label=label)

        ax.plot([pa[0], pb[0]], [pa[1], pb[1]], 'k--', lw=1.5, alpha=0.6)
        ax.text(0.02, 0.02, f'shift {shift:.0f} m,  az $\\Delta$ {daz:.1f}$^\\circ$',
                transform=ax.transAxes, fontsize=10, fontweight='bold',
                va='bottom',
                bbox=dict(boxstyle='round,pad=0.3', fc='white', ec='gray', alpha=0.9))

        ax.set_title(f'ECI {eci} ({note})\nA: az {pa[2]:.0f}$^\\circ$ bw {pa[3]:.0f}$^\\circ$ tilt {pa[4]:.1f}$^\\circ$ h {pa[5]:.0f}m | '
                     f'B: az {pb[2]:.0f}$^\\circ$ bw {pb[3]:.0f}$^\\circ$ tilt {pb[4]:.1f}$^\\circ$ h {pb[5]:.0f}m',
                     fontsize=10)
        ax.legend(loc='best', fontsize=9)
        ax.set_aspect('equal')
        ax.set_xlabel('East (m)'); ax.set_ylabel('North (m)')
        ax.grid(alpha=0.3)

    conn.close()
    plt.tight_layout()
    fig.savefig(OUT / 'position_compare_ab.png', dpi=150, bbox_inches='tight')
    print('saved', OUT / 'position_compare_ab.png')


if __name__ == '__main__':
    main()
