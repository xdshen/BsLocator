"""
Visual comparison with map background: estimated BS position with hard clip (A)
vs tanh smooth cap (B), overlaid on OpenStreetMap tiles (WGS-84 aligned).
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

GAODE_TILES = ('https://webrd01.is.autonavi.com/appmaptile?'
               'lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}')


def wgs84_to_gcj02(lat, lng):
    """WGS-84 -> GCJ-02 (same algorithm as the app's CoordinateTransform)."""
    a, ee, pi = 6378245.0, 0.00669342162296594323, math.pi

    def tlat(x, y):
        r = -100.0 + 2*x + 3*y + 0.2*y*y + 0.1*x*y + 0.2*math.sqrt(abs(x))
        r += (20*math.sin(6*x*pi) + 20*math.sin(2*x*pi)) * 2/3
        r += (20*math.sin(y*pi) + 40*math.sin(y/3*pi)) * 2/3
        r += (160*math.sin(y/12*pi) + 320*math.sin(y*pi/30)) * 2/3
        return r

    def tlng(x, y):
        r = 300.0 + x + 2*y + 0.1*x*x + 0.1*x*y + 0.1*math.sqrt(abs(x))
        r += (20*math.sin(6*x*pi) + 20*math.sin(2*x*pi)) * 2/3
        r += (20*math.sin(x*pi) + 40*math.sin(x/3*pi)) * 2/3
        r += (150*math.sin(x/12*pi) + 300*math.sin(x/30*pi)) * 2/3
        return r

    dlat, dlng = tlat(lng-105.0, lat-35.0), tlng(lng-105.0, lat-35.0)
    radlat = lat/180*pi
    magic = 1 - ee*math.sin(radlat)**2
    sq = math.sqrt(magic)
    dlat = dlat*180/((a*(1-ee))/(magic*sq)*pi)
    dlng = dlng*180/(a/sq*math.cos(radlat)*pi)
    return lat+dlat, lng+dlng

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
    return pts, lat0, lng0


def enu_to_ll(x, y, lat0, lng0):
    lat = lat0 + y / EARTH_R * 180 / math.pi
    lng = lng0 + x / (EARTH_R * math.cos(math.radians(lat0))) * 180 / math.pi
    return lat, lng


def sector_ll(cx, cy, az_deg, bw_deg, radius, lat0, lng0, steps=40):
    half = math.radians(bw_deg / 2)
    start = math.radians(az_deg) - half
    angs = start + 2 * half * np.linspace(0, 1, steps)
    xs = cx + radius * np.sin(angs)
    ys = cy + radius * np.cos(angs)
    lats, lngs = [], []
    for x, y in [(cx, cy)] + list(zip(xs, ys)) + [(cx, cy)]:
        la, ln = enu_to_ll(x, y, lat0, lng0)
        lats.append(la); lngs.append(ln)
    return np.array(lngs), np.array(lats)


def sector_radius(p, pts):
    d = np.hypot(pts[:, 0] - p[0], pts[:, 1] - p[1])
    az = (np.degrees(np.arctan2(pts[:, 0] - p[0], pts[:, 1] - p[1])) + 360) % 360
    daz = np.abs((az - p[2] + 180) % 360 - 180)
    main = d[daz <= p[3] / 2]
    base = np.percentile(main if len(main) else d, 90)
    return float(np.clip(base * 1.2, 100, 2000))


def main():
    import matplotlib.pyplot as plt
    import contextily as ctx

    conn = sqlite3.connect(DB)
    fig, axes = plt.subplots(2, 2, figsize=(15, 14))

    for ax, (eci, note) in zip(axes.flat, CELLS):
        pts, lat0, lng0 = load_cell(conn, eci)
        pa, _ = estimate(pts, 'hard')
        pb, _ = estimate(pts, 'tanh')
        ra, rb = rmse(pa, pts, 'hard'), rmse(pb, pts, 'tanh')
        shift = math.hypot(pa[0] - pb[0], pa[1] - pb[1])
        daz = abs((pa[2] - pb[2] + 180) % 360 - 180)

        lat_p, lng_p = enu_to_ll(pts[:, 0], pts[:, 1], lat0, lng0)
        # 高德瓦片是 GCJ-02 坐标，数据先转过去再叠加（与 APP 地图显示一致）
        conv = np.array([wgs84_to_gcj02(la, ln) for la, ln in zip(lat_p, lng_p)])
        ax.scatter(conv[:, 1], conv[:, 0], c=pts[:, 2], cmap='RdYlGn', s=10, alpha=0.85,
                   edgecolor='none', zorder=4)

        for p, color, label, r in [
            (pa, '#C5504B', f'A: hard clip (RMSE {ra:.2f})', ra),
            (pb, '#2196F3', f'B: tanh smooth (RMSE {rb:.2f})', rb),
        ]:
            rad = sector_radius(p, pts)
            sx, sy = sector_ll(p[0], p[1], p[2], p[3], rad, lat0, lng0)
            conv_s = np.array([wgs84_to_gcj02(la, ln) for la, ln in zip(sy, sx)])
            ax.fill(conv_s[:, 1], conv_s[:, 0], color=color, alpha=0.13, zorder=2)
            ax.plot(conv_s[:, 1], conv_s[:, 0], color=color, lw=1.4, alpha=0.8, zorder=3)
            lat_s, lng_s = enu_to_ll(p[0], p[1], lat0, lng0)
            gs_lat, gs_lng = wgs84_to_gcj02(lat_s, lng_s)
            ax.scatter(gs_lng, gs_lat, marker='*', s=500, color=color,
                       edgecolor='k', linewidth=1.2, zorder=6, label=label)

        la_a, ln_a = enu_to_ll(pa[0], pa[1], lat0, lng0)
        la_b, ln_b = enu_to_ll(pb[0], pb[1], lat0, lng0)
        ga = wgs84_to_gcj02(la_a, ln_a)
        gb = wgs84_to_gcj02(la_b, ln_b)
        ax.plot([ga[1], gb[1]], [ga[0], gb[0]], 'k--', lw=1.5, alpha=0.6, zorder=5)

        ax.text(0.02, 0.02, f'shift {shift:.0f} m,  az $\\Delta$ {daz:.1f}$^\\circ$',
                transform=ax.transAxes, fontsize=11, fontweight='bold',
                va='bottom',
                bbox=dict(boxstyle='round,pad=0.3', fc='white', ec='gray', alpha=0.9))
        ax.set_title(
            f'ECI {eci} ({note})\n'
            f'A: az {pa[2]:.0f}$^\\circ$ bw {pa[3]:.0f}$^\\circ$ tilt {pa[4]:.1f}$^\\circ$ h {pa[5]:.0f}m | '
            f'B: az {pb[2]:.0f}$^\\circ$ bw {pb[3]:.0f}$^\\circ$ tilt {pb[4]:.1f}$^\\circ$ h {pb[5]:.0f}m',
            fontsize=10)
        ax.legend(loc='upper right', fontsize=9)

        try:
            # 高德 style=8 瓦片最大支持 z17，更高层级返回空白占位图
            ctx.add_basemap(ax, crs='EPSG:4326', source=GAODE_TILES, zoom=17)
        except Exception as e:
            print(f'basemap failed for {eci}: {e}')
            ax.set_aspect('equal', adjustable='datalim')
            ax.grid(alpha=0.3)
        ax.tick_params(labelsize=7)
        ax.set_xlabel('Longitude'); ax.set_ylabel('Latitude')

    conn.close()
    plt.tight_layout()
    fig.savefig(OUT / 'position_compare_map.png', dpi=150, bbox_inches='tight')
    print('saved', OUT / 'position_compare_map.png')


if __name__ == '__main__':
    main()
