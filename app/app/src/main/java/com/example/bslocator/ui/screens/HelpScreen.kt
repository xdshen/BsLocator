package com.example.bslocator.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen() {
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("使用帮助") })
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Spacer(modifier = Modifier.height(4.dp)) }

            item {
                HelpCard(title = "① 必需权限（首次打开请全部允许）") {
                    HelpLine("• 精确位置：路测时获取 GPS 坐标，采集功能的核心")
                    HelpLine("• 电话状态：读取 LTE/NR 小区信息（ECI、PCI、RSRP 等）")
                    HelpLine("• 通知：后台采集/推断完成时接收提醒")
                    HelpLine("• 如果之前点了拒绝，可在系统设置中重新开启：")
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null)
                            )
                            context.startActivity(intent)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("打开应用权限设置")
                    }
                }
            }

            item {
                HelpCard(title = "② 防止后台被杀（保证采集中断不了）") {
                    HelpLine("国产系统（vivo / OPPO / 小米 / 华为等）会激进地清理后台应用，")
                    HelpLine("长时间路测前请完成以下设置：")
                    Spacer(modifier = Modifier.height(4.dp))
                    HelpLine("1. 电池优化白名单：将本应用设为“不优化”", bold = true)
                    OutlinedButton(
                        onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("打开电池优化设置")
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    HelpLine("2. 允许自启动：", bold = true)
                    HelpLine("   vivo：设置 → 应用与权限 → 权限管理 → 自启动")
                    HelpLine("   小米：设置 → 应用设置 → 授权管理 → 自启动管理")
                    HelpLine("   华为：手机管家 → 启动管理 → 允许后台活动")
                    HelpLine("   OPPO/一加：设置 → 电池 → 应用耗电管理 → 允许后台运行")
                    Spacer(modifier = Modifier.height(4.dp))
                    HelpLine("3. 在最近任务列表中下拉本应用卡片“加锁”", bold = true)
                    HelpLine("4. 路测期间关闭省电/超级省电模式", bold = true)
                }
            }

            item {
                HelpCard(title = "③ 采集建议（直接影响推断精度）") {
                    HelpLine("• 尽量绕基站走满 360°，至少覆盖主瓣和一侧旁瓣")
                    HelpLine("• 近、中、远距离都要有采样点")
                    HelpLine("• 每个小区建议 50 个点以上；少于约 20 个点结果不可靠")
                    HelpLine("• GPS 精度差（>20m）的采样会被自动丢弃，请在开阔地带采集")
                    HelpLine("• 采集过程中可以锁屏，前台服务会持续工作")
                }
            }

            item {
                HelpCard(title = "④ 使用流程") {
                    HelpLine("1. 采集页：点“开始采集”，走完路线后点“停止采集”")
                    HelpLine("2. 推断页：选择目标小区 → “开始推断（后台运行）”")
                    HelpLine("3. 日志页：勾选会话可在地图叠加轨迹，可导出 CSV/JSON")
                    HelpLine("4. 地图页：查看轨迹、推断出的基站位置与主瓣扇区")
                }
            }

            item {
                HelpCard(title = "⑤ 常见问题") {
                    HelpLine("Q：地图需要配置吗？", bold = true)
                    HelpLine("A：不需要，安装即可正常显示地图。")
                    Spacer(modifier = Modifier.height(4.dp))
                    HelpLine("Q：推断失败 / 提示数据不足？", bold = true)
                    HelpLine("A：该小区的有效测量点不足 10 个，或 GPS 精度太差，请补充采集后再试。")
                    Spacer(modifier = Modifier.height(4.dp))
                    HelpLine("Q：数据存在哪里？", bold = true)
                    HelpLine("A：全部保存在手机本地数据库，不上传任何服务器；可随时在日志页导出备份。")
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun HelpCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun HelpLine(text: String, bold: Boolean = false) {
    Text(
        text = text,
        fontSize = 14.sp,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        lineHeight = 22.sp,
        modifier = Modifier.padding(vertical = 1.dp)
    )
}
