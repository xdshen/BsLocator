import pandas as pd
import numpy as np
from scipy.optimize import minimize
import math

from pathlib import Path

# Read CSV (repo-relative path to the bundled field-data sample)
df = pd.read_csv(Path(__file__).resolve().parent.parent / 'data' / 'bslocator_all_1788411591773.csv')

print("=== CSV Overview ===")
print(f"Total records: {len(df)}")
print(f"Columns: {list(df.columns)}")
print()

# Check unique PCI values and their counts
print("=== PCI Distribution ===")
print(df.groupby(['pci', 'eci']).size().reset_index(name='count').sort_values('count', ascending=False))
print()

# Filter PCI=199
df199 = df[df['pci'] == 199].copy()
print(f"=== PCI=199: {len(df199)} records ===")
print(f"Unique ECI(s): {df199['eci'].unique()}")
print()

for eci in sorted(df199['eci'].unique()):
    sub = df199[df199['eci'] == eci].copy()
    print(f"--- ECI {eci}: {len(sub)} records ---")
    print(f"  Lat range: {sub['latitude'].min():.6f} ~ {sub['latitude'].max():.6f}")
    print(f"  Lng range: {sub['longitude'].min():.6f} ~ {sub['longitude'].max():.6f}")
    print(f"  RSRP range: {sub['rsrp'].min()} ~ {sub['rsrp'].max()} dBm")
    print(f"  Avg GPS accuracy: {sub['gps_accuracy'].mean():.1f}m")
    print()

# Base station estimation algorithm
def estimate_bs_location(measurements):
    """Estimate base station location from measurement data."""
    lats = measurements['latitude'].values
    lngs = measurements['longitude'].values
    rsrps = measurements['rsrp'].values
    
    # Convert to local Cartesian coordinates (meters)
    ref_lat = np.mean(lats)
    ref_lng = np.mean(lngs)
    
    # Approximate meters per degree
    m_per_deg_lat = 111320.0
    m_per_deg_lng = 111320.0 * math.cos(math.radians(ref_lat))
    
    xs = (lngs - ref_lng) * m_per_deg_lng
    ys = (lats - ref_lat) * m_per_deg_lat
    
    # Path loss model: RSSI = P0 - 10*n*log10(d)
    # RSRP is negative, strongest (least negative) means closest
    # Use strongest signal point as initial guess
    best_idx = np.argmax(rsrps)
    x0, y0 = xs[best_idx], ys[best_idx]
    
    # Loss function: minimize squared error in RSSI prediction
    def loss(params):
        bx, by, n, p0 = params
        if n < 1 or n > 6:
            return 1e10
        if p0 < -30 or p0 > 100:
            return 1e10
        
        dists = np.sqrt((xs - bx)**2 + (ys - by)**2)
        dists = np.maximum(dists, 1.0)  # avoid log(0)
        predicted = p0 - 10 * n * np.log10(dists)
        errors = rsrps - predicted
        return np.sum(errors**2)
    
    # Initial guess
    # Estimate P0 from strongest measurement (at ~0 distance)
    p0_init = rsrps[best_idx]
    n_init = 3.0
    
    result = minimize(loss, [x0, y0, n_init, p0_init], 
                      method='Nelder-Mead',
                      options={'maxiter': 5000})
    
    if result.success:
        bx, by, n, p0 = result.x
        bs_lat = ref_lat + by / m_per_deg_lat
        bs_lng = ref_lng + bx / m_per_deg_lng
        
        # Calculate RMSE
        dists = np.sqrt((xs - bx)**2 + (ys - by)**2)
        dists = np.maximum(dists, 1.0)
        predicted = p0 - 10 * n * np.log10(dists)
        rmse = np.sqrt(np.mean((rsrps - predicted)**2))
        
        return {
            'bs_lat': bs_lat,
            'bs_lng': bs_lng,
            'n': n,
            'p0': p0,
            'rmse': rmse,
            'iterations': result.nit
        }
    return None

def estimate_azimuth(measurements, bs_lat, bs_lng):
    """Estimate antenna azimuth from bearing distribution."""
    lats = measurements['latitude'].values
    lngs = measurements['longitude'].values
    rsrps = measurements['rsrp'].values
    
    # Calculate bearing from BS to each measurement point
    bearings = []
    for lat, lng in zip(lats, lngs):
        dlon = math.radians(lng - bs_lng)
        lat1 = math.radians(bs_lat)
        lat2 = math.radians(lat)
        y = math.sin(dlon) * math.cos(lat2)
        x = math.cos(lat1) * math.sin(lat2) - math.sin(lat1) * math.cos(lat2) * math.cos(dlon)
        bearing = math.degrees(math.atan2(y, x))
        bearing = (bearing + 360) % 360
        bearings.append(bearing)
    
    bearings = np.array(bearings)
    
    # Weight by signal strength (stronger = higher weight)
    weights = np.exp((rsrps - rsrps.min()) / 10)  # normalize
    
    # Try different azimuths, find one that maximizes coverage in main lobe
    best_az = 0
    best_score = -1e10
    best_bw = 65
    
    for az in range(0, 360, 2):
        for bw in [30, 45, 65, 90, 120]:
            # Points within main lobe
            rel = (bearings - az + 180) % 360 - 180
            in_lobe = np.abs(rel) <= bw / 2
            
            if np.sum(in_lobe) < len(measurements) * 0.3:
                continue
            
            score = np.sum(weights[in_lobe]) - 0.5 * np.sum(weights[~in_lobe])
            if score > best_score:
                best_score = score
                best_az = az
                best_bw = bw
    
    return best_az, best_bw

# Run estimation for each ECI with PCI=199
print("=" * 60)
print("BASE STATION ESTIMATION RESULTS FOR PCI=199")
print("=" * 60)
print()

for eci in sorted(df199['eci'].unique()):
    sub = df199[df199['eci'] == eci].copy()
    
    result = estimate_bs_location(sub)
    if result:
        az, bw = estimate_azimuth(sub, result['bs_lat'], result['bs_lng'])
        
        print(f"ECI: {eci}")
        print(f"  基站纬度: {result['bs_lat']:.6f}°")
        print(f"  基站经度: {result['bs_lng']:.6f}°")
        print(f"  方位角:   {az}°")
        print(f"  波束宽度: {bw}°")
        print(f"  路径损耗指数 n: {result['n']:.2f}")
        print(f"  参考 RSSI: {result['p0']:.1f} dBm")
        print(f"  拟合 RMSE: {result['rmse']:.2f} dB")
        print(f"  测量点数: {len(sub)}")
        print()
    else:
        print(f"ECI {eci}: Estimation failed")

print("=" * 60)
