import pandas as pd
import numpy as np
import matplotlib.pyplot as plt
import math

from pathlib import Path

# Read CSV (repo-relative path to the bundled field-data sample)
df = pd.read_csv(Path(__file__).resolve().parent.parent / 'data' / 'bslocator_all_1788411591773.csv')
df199 = df[df['pci'] == 199].copy()

# Estimate BS location (same logic as analyze script)
def estimate_bs(data):
    lats = data['latitude'].values
    lngs = data['longitude'].values
    rsrps = data['rsrp'].values
    ref_lat = np.mean(lats)
    ref_lng = np.mean(lngs)
    m_per_deg_lat = 111320.0
    m_per_deg_lng = 111320.0 * math.cos(math.radians(ref_lat))
    xs = (lngs - ref_lng) * m_per_deg_lng
    ys = (lats - ref_lat) * m_per_deg_lat
    best_idx = np.argmax(rsrps)
    x0, y0 = xs[best_idx], ys[best_idx]
    
    from scipy.optimize import minimize
    def loss(p):
        bx, by, n, p0 = p
        if n < 1 or n > 6 or p0 < -30 or p0 > 100:
            return 1e10
        dists = np.maximum(np.sqrt((xs-bx)**2 + (ys-by)**2), 1.0)
        pred = p0 - 10*n*np.log10(dists)
        return np.sum((rsrps - pred)**2)
    
    r = minimize(loss, [x0, y0, 3.0, rsrps[best_idx]], method='Nelder-Mead')
    bx, by = r.x[0], r.x[1]
    bs_lat = ref_lat + by / m_per_deg_lat
    bs_lng = ref_lng + bx / m_per_deg_lng
    return bs_lat, bs_lng

bs_lat, bs_lng = estimate_bs(df199)

# Create figure
fig, axes = plt.subplots(1, 2, figsize=(16, 7))

# Left: Trajectory + BS position
ax1 = axes[0]
scatter = ax1.scatter(df199['longitude'], df199['latitude'], c=df199['rsrp'], cmap='RdYlGn_r', s=15, alpha=0.7)
ax1.scatter(bs_lng, bs_lat, c='red', s=200, marker='*', edgecolors='black', linewidth=1.5, label='Estimated BS', zorder=5)
ax1.plot(df199['longitude'], df199['latitude'], 'b-', alpha=0.3, linewidth=0.5)
ax1.set_xlabel('Longitude')
ax1.set_ylabel('Latitude')
ax1.set_title('PCI=199 (ECI 4097097730) Measurement Trajectory\nRed star = Estimated Base Station')
ax1.legend()
plt.colorbar(scatter, ax=ax1, label='RSRP (dBm)')
ax1.set_aspect('equal', adjustable='box')

# Right: Signal strength vs distance from estimated BS
def haversine(lat1, lng1, lat2, lng2):
    R = 6371000
    dlat = math.radians(lat2 - lat1)
    dlng = math.radians(lng2 - lng1)
    a = math.sin(dlat/2)**2 + math.cos(math.radians(lat1)) * math.cos(math.radians(lat2)) * math.sin(dlng/2)**2
    return 2 * R * math.asin(math.sqrt(a))

distances = [haversine(bs_lat, bs_lng, lat, lng) for lat, lng in zip(df199['latitude'], df199['longitude'])]
ax2 = axes[1]
ax2.scatter(distances, df199['rsrp'], c='steelblue', s=15, alpha=0.5)

# Fit path loss curve
from scipy.optimize import curve_fit
def pl_model(d, n, p0):
    return p0 - 10 * n * np.log10(np.maximum(d, 1))

valid = np.array(distances) > 0.5
if np.sum(valid) > 5:
    try:
        popt, _ = curve_fit(pl_model, np.array(distances)[valid], df199['rsrp'].values[valid], p0=[3, -50])
        d_fit = np.linspace(min(distances), max(distances), 200)
        ax2.plot(d_fit, pl_model(d_fit, *popt), 'r-', linewidth=2, label=f'Fit: n={popt[0]:.2f}, P0={popt[1]:.1f}dBm')
    except:
        pass

ax2.set_xlabel('Distance from Estimated BS (m)')
ax2.set_ylabel('RSRP (dBm)')
ax2.set_title('Signal Strength vs Distance')
ax2.legend()
ax2.grid(True, alpha=0.3)

plt.tight_layout()
fig.savefig(Path(__file__).resolve().parent.parent / 'assets' / 'pci199_analysis.png', dpi=150, bbox_inches='tight')
print("Figure saved.")

# Also print bearing distribution
bearings = []
for lat, lng in zip(df199['latitude'], df199['longitude']):
    dlon = math.radians(lng - bs_lng)
    lat1 = math.radians(bs_lat)
    lat2 = math.radians(lat)
    y = math.sin(dlon) * math.cos(lat2)
    x = math.cos(lat1) * math.sin(lat2) - math.sin(lat1) * math.cos(lat2) * math.cos(dlon)
    b = (math.degrees(math.atan2(y, x)) + 360) % 360
    bearings.append(b)

print(f"\nBearing distribution from estimated BS to measurement points:")
print(f"  Min bearing: {min(bearings):.1f}°")
print(f"  Max bearing: {max(bearings):.1f}°")
print(f"  Range: {(max(bearings) - min(bearings)):.1f}°")
print(f"  Mean: {np.mean(bearings):.1f}°")
print(f"  Median: {np.median(bearings):.1f}°")
