"""
Generate simulation charts for the base station positioning report
"""
import matplotlib.pyplot as plt
import matplotlib
import numpy as np
from pathlib import Path

# Use a CJK font that exists in the system
matplotlib.rcParams['font.family'] = ['SimHei', 'Arial Unicode MS', 'sans-serif']
matplotlib.rcParams['axes.unicode_minus'] = False

output_dir = Path(__file__).resolve().parent.parent / 'assets'
output_dir.mkdir(exist_ok=True)

# Chart 1: Positioning error under different measurement coverage
fig, ax = plt.subplots(figsize=(8, 5))

categories = ['仅主瓣\n±30°', '主瓣+\n一侧旁瓣', '全方向\n(360°)', '稀疏采样\n(仅4方向)']
errors = [14.6, 11.3, 8.2, 35.4]
colors = ['#4472C4', '#4472C4', '#70AD47', '#C5504B']

bars = ax.bar(categories, errors, color=colors, edgecolor='white', linewidth=1.5)

for bar, val in zip(bars, errors):
    ax.text(bar.get_x() + bar.get_width()/2, bar.get_height() + 0.8,
            f'{val} m', ha='center', va='bottom', fontsize=11, fontweight='bold')

ax.set_ylabel('定位误差 (m)', fontsize=12)
ax.set_title('不同测量覆盖范围下的定位精度', fontsize=14, fontweight='bold', pad=15)
ax.set_ylim(0, 42)
ax.spines['top'].set_visible(False)
ax.spines['right'].set_visible(False)
ax.grid(axis='y', alpha=0.3)

fig.tight_layout()
fig.savefig(output_dir / 'chart1_coverage_error.png', dpi=150, bbox_inches='tight')
plt.close()
print('Saved chart1_coverage_error.png')

# Chart 2: Parameter estimation accuracy
fig, ax1 = plt.subplots(figsize=(8, 5))

categories2 = ['仅主瓣\n±30°', '主瓣+\n一侧旁瓣', '全方向\n(360°)', '稀疏采样\n(仅4方向)']
azimuth_errors = [2.2, 1.5, 0.8, 8.7]
beamwidth_errors = [5.1, 3.8, 2.1, 15.2]

x = np.arange(len(categories2))
width = 0.35

bars1 = ax1.bar(x - width/2, azimuth_errors, width, label='方位角误差', color='#4472C4', edgecolor='white')
bars2 = ax1.bar(x + width/2, beamwidth_errors, width, label='波束宽度误差', color='#ED7D31', edgecolor='white')

for bar in bars1:
    ax1.text(bar.get_x() + bar.get_width()/2, bar.get_height() + 0.15,
             f'{bar.get_height():.1f}°', ha='center', va='bottom', fontsize=9)
for bar in bars2:
    ax1.text(bar.get_x() + bar.get_width()/2, bar.get_height() + 0.15,
             f'{bar.get_height():.1f}°', ha='center', va='bottom', fontsize=9)

ax1.set_ylabel('估计误差 (°)', fontsize=12)
ax1.set_title('方向图参数估计精度', fontsize=14, fontweight='bold', pad=15)
ax1.set_xticks(x)
ax1.set_xticklabels(categories2)
ax1.legend(loc='upper left', frameon=False)
ax1.spines['top'].set_visible(False)
ax1.spines['right'].set_visible(False)
ax1.grid(axis='y', alpha=0.3)

fig.tight_layout()
fig.savefig(output_dir / 'chart2_param_accuracy.png', dpi=150, bbox_inches='tight')
plt.close()
print('Saved chart2_param_accuracy.png')

# Chart 3: Method comparison
fig, ax = plt.subplots(figsize=(8, 5))

methods = ['固定n+\n最小二乘', '全局\n自适应n', '联合估计\n(本文)', '忽略\n方向图']
errors3 = [7.5, 11.5, 11.5, 350]
colors3 = ['#70AD47', '#4472C4', '#4472C4', '#C5504B']

bars = ax.barh(methods, errors3, color=colors3, edgecolor='white', height=0.6)

for bar, val in zip(bars, errors3):
    if val < 100:
        ax.text(bar.get_width() + 2, bar.get_y() + bar.get_height()/2,
                f'{val} m', ha='left', va='center', fontsize=11, fontweight='bold')
    else:
        ax.text(bar.get_width() + 5, bar.get_y() + bar.get_height()/2,
                f'{val}m+', ha='left', va='center', fontsize=11, fontweight='bold', color='#C5504B')

ax.set_xlabel('定位误差 (m)', fontsize=12)
ax.set_title('不同定位方法误差对比', fontsize=14, fontweight='bold', pad=15)
ax.set_xlim(0, 420)
ax.spines['top'].set_visible(False)
ax.spines['right'].set_visible(False)
ax.grid(axis='x', alpha=0.3)

fig.tight_layout()
fig.savefig(output_dir / 'chart3_method_compare.png', dpi=150, bbox_inches='tight')
plt.close()
print('Saved chart3_method_compare.png')

# Chart 4: Coverage vs accuracy trend line
fig, ax = plt.subplots(figsize=(8, 5))

coverage_deg = [30, 90, 180, 360]
errors4 = [14.6, 11.3, 9.5, 8.2]

ax.plot(coverage_deg, errors4, 'o-', color='#4472C4', linewidth=2.5, markersize=10, markerfacecolor='#4472C4', markeredgecolor='white', markeredgewidth=2)

for x, y in zip(coverage_deg, errors4):
    ax.annotate(f'{y} m', (x, y), textcoords="offset points", xytext=(0, 12), ha='center', fontsize=10, fontweight='bold')

ax.set_xlabel('测量覆盖角度 (°)', fontsize=12)
ax.set_ylabel('定位误差 (m)', fontsize=12)
ax.set_title('测量覆盖范围与定位误差关系', fontsize=14, fontweight='bold', pad=15)
ax.set_xlim(0, 400)
ax.set_ylim(0, 20)
ax.spines['top'].set_visible(False)
ax.spines['right'].set_visible(False)
ax.grid(alpha=0.3)

# Add region shading
ax.axhspan(0, 10, alpha=0.1, color='green')
ax.axhspan(10, 20, alpha=0.1, color='orange')
ax.text(350, 5, '高精度区域\n(<10m)', fontsize=9, ha='center', color='darkgreen')
ax.text(350, 15, '可接受区域\n(10-20m)', fontsize=9, ha='center', color='darkorange')

fig.tight_layout()
fig.savefig(output_dir / 'chart4_coverage_trend.png', dpi=150, bbox_inches='tight')
plt.close()
print('Saved chart4_coverage_trend.png')

print(f'\nAll charts saved to: {output_dir}')
