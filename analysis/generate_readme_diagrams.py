"""
Generate the principle / architecture diagrams used in README.
Outputs:
  assets/principle.png    - joint-estimation geometry schematic
  assets/pipeline.png     - end-to-end system pipeline
"""
import matplotlib.pyplot as plt
import matplotlib
import numpy as np
from matplotlib.patches import FancyBboxPatch, FancyArrowPatch, Wedge
from pathlib import Path

matplotlib.rcParams['font.family'] = ['SimHei', 'Arial Unicode MS', 'sans-serif']
matplotlib.rcParams['axes.unicode_minus'] = False

assets = Path(__file__).resolve().parent.parent / 'assets'

# ---------------------------------------------------------------- principle
fig, axes = plt.subplots(1, 2, figsize=(13, 5.6))

# --- left: measurement geometry with directional antenna pattern
ax = axes[0]
bs = np.array([0.0, 0.0])
azimuth = np.deg2rad(55)          # main-lobe pointing direction
beamwidth = np.deg2rad(65)        # 3 dB beamwidth

# 3GPP-style horizontal pattern: A(theta) = -min(12*(theta/bw)^2, 30) dB
theta = np.linspace(-np.pi, np.pi, 720)
A = -np.minimum(12 * (theta / beamwidth) ** 2, 30)
r = 10 ** (A / 18.0)             # normalize gain -> radius for display
px = bs[0] + r * np.cos(theta + azimuth) * 210
py = bs[1] + r * np.sin(theta + azimuth) * 210
ax.fill(px, py, color='#4472C4', alpha=0.25, zorder=1)
ax.plot(px, py, color='#4472C4', lw=1.8, zorder=2)

# base station
ax.scatter(*bs, marker='^', s=380, color='#C5504B', zorder=5, edgecolor='k')
ax.annotate('基站位置 (x, y)\n未知，待估计', xy=bs, xytext=(-330, 150),
            fontsize=11, fontweight='bold',
            arrowprops=dict(arrowstyle='->', color='#C5504B'))

# azimuth annotation
ax.annotate('', xy=(230*np.cos(azimuth), 230*np.sin(azimuth)), xytext=(230, 0),
            arrowprops=dict(arrowstyle='<->', color='#70AD47', lw=1.5))
ax.text(185, 60, '方位角 φ', fontsize=11, color='#70AD47', fontweight='bold')
ax.text(150, 235, '波束宽度 θ₃dB', fontsize=11, color='#4472C4', fontweight='bold')

# measurement drive-test track with RSRP colouring
rng = np.random.default_rng(7)
t = np.linspace(0, 1, 60)
track = np.column_stack([
    -420 + 840 * t,
    230 + 150 * np.sin(t * 2.2) + rng.normal(0, 10, t.size),
])
d = np.hypot(track[:, 0] - bs[0], track[:, 1] - bs[1])
bearing = np.arctan2(track[:, 1] - bs[1], track[:, 0] - bs[0])
gain = -np.minimum(12 * (((bearing - azimuth + np.pi) % (2*np.pi) - np.pi) / beamwidth) ** 2, 30)
rsrp = -25 - 30 * np.log10(d) + gain          # synthetic RSRP
sc = ax.scatter(track[:, 0], track[:, 1], c=rsrp, cmap='RdYlGn', s=42,
                zorder=4, edgecolor='white', linewidth=0.4)
cb = fig.colorbar(sc, ax=ax, fraction=0.046, pad=0.04)
cb.set_label('RSRP (dBm)', fontsize=10)

ax.annotate('路测轨迹\n(GPS + RSRP)', xy=track[10], xytext=(-440, -60),
            fontsize=11, arrowprops=dict(arrowstyle='->', color='gray'))
ax.text(-440, -180, '同距离、不同方位\n→ RSRP 差异可达 30 dB', fontsize=10,
        color='#7030A0', fontweight='bold',
        bbox=dict(boxstyle='round,pad=0.35', fc='#F3E8FD', ec='#7030A0'))

ax.set_xlim(-460, 460); ax.set_ylim(-360, 400)
ax.set_aspect('equal'); ax.axis('off')
ax.set_title('问题：方向图未知时，RSSI 无法直接换算距离', fontsize=13, fontweight='bold')

# --- right: joint-estimation flow
ax = axes[1]
ax.axis('off')
ax.set_xlim(0, 10); ax.set_ylim(0, 10)

def box(x, y, w, h, text, fc, ec, fs=10.5, bold=True):
    ax.add_patch(FancyBboxPatch((x, y), w, h, boxstyle='round,pad=0.12',
                                fc=fc, ec=ec, lw=1.6))
    ax.text(x + w/2, y + h/2, text, ha='center', va='center',
            fontsize=fs, fontweight='bold' if bold else 'normal')

def arrow(x1, y1, x2, y2):
    ax.add_patch(FancyArrowPatch((x1, y1), (x2, y2), arrowstyle='-|>',
                                 mutation_scale=16, color='#555555', lw=1.6))

box(2.5, 8.4, 5, 1.2, '路测数据：GPS 位置 + RSRP + PCI/ECI', '#EAF1FB', '#4472C4')
box(2.5, 6.3, 5, 1.2, '信号模型 = 对数路径损耗\n+ 3GPP TR 38.901 天线方向图', '#FFF6E5', '#ED7D31')
box(0.4, 3.6, 4.4, 1.5, '联合优化 8 参数\n位置(x,y) 方位角 波束宽度\n下倾角 高度 n p₀', '#EAF7EA', '#70AD47', fs=10)
box(5.3, 3.6, 4.3, 1.5, 'Huber 鲁棒损失\n解析梯度 + Armijo 回溯\n4 组多起点取最优', '#F3E8FD', '#7030A0', fs=10)
box(2.5, 1.2, 5, 1.2, '输出：基站坐标 + 方向图参数\n定位误差 8~15 m', '#FDECEC', '#C5504B')

arrow(5, 8.4, 5, 7.7)
arrow(5, 6.3, 2.6, 5.3)
arrow(5, 6.3, 7.4, 5.3)
arrow(2.6, 3.6, 4.4, 2.5)
arrow(7.4, 3.6, 5.6, 2.5)
ax.set_title('方法：位置与方向图参数联合估计', fontsize=13, fontweight='bold')

plt.tight_layout()
fig.savefig(assets / 'principle.png', dpi=160, bbox_inches='tight')
plt.close(fig)

# ---------------------------------------------------------------- pipeline
fig, ax = plt.subplots(figsize=(13, 3.4))
ax.axis('off'); ax.set_xlim(0, 26); ax.set_ylim(0, 6)

def pbox(x, w, title, sub, fc, ec):
    ax.add_patch(FancyBboxPatch((x, 1.4), w, 3.0, boxstyle='round,pad=0.15',
                                fc=fc, ec=ec, lw=1.8))
    ax.text(x + w/2, 3.55, title, ha='center', fontsize=12, fontweight='bold')
    ax.text(x + w/2, 2.35, sub, ha='center', fontsize=9.5, color='#444444')

def parrow(x1, x2):
    ax.add_patch(FancyArrowPatch((x1, 2.9), (x2, 2.9), arrowstyle='-|>',
                                 mutation_scale=18, color='#555555', lw=1.8))

pbox(0.3, 4.6, '数据采集', '前台 Service\nGPS + LTE/NR 信号\n(RSRP/RSRQ/SINR/ECI)', '#EAF1FB', '#4472C4')
pbox(5.6, 4.2, '本地存储', 'Room 数据库\n会话管理 · CSV 导入导出', '#EAF7EA', '#70AD47')
pbox(10.5, 4.6, '联合估计', 'WorkManager 后台\nHuber + 多起点\n投影梯度优化', '#FFF6E5', '#ED7D31')
pbox(15.8, 4.4, '地图可视化', '高德地图\n轨迹热力 · 基站扇区\n主瓣方向叠加', '#F3E8FD', '#7030A0')
pbox(20.9, 4.6, '离线分析', 'Python / pandas\n定位误差评估\n研究报告生成', '#FDECEC', '#C5504B')
for x1, x2 in [(4.9, 5.6), (9.8, 10.5), (15.1, 15.8), (20.2, 20.9)]:
    parrow(x1, x2)

plt.tight_layout()
fig.savefig(assets / 'pipeline.png', dpi=160, bbox_inches='tight')
plt.close(fig)

print('saved:', assets / 'principle.png', 'and', assets / 'pipeline.png')
