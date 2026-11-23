package com.roubao.autopilot.ui.screens

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roubao.autopilot.BuildConfig
import com.roubao.autopilot.ui.theme.BaoziTheme

/**
 * 「关于伴语」页（仿微信「关于微信」的版式）：
 * 顶部居中放应用图标、名称与版本号，中间是功能介绍/意见反馈/版本更新入口，
 * 底部是许可、隐私与版权信息。从设置里的「版本」一项点进来。
 */
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    onCheckUpdate: (() -> Unit)? = null
) {
    val colors = BaoziTheme.colors
    val context = LocalContext.current
    var showFeatures by remember { mutableStateOf(false) }
    var showFeedback by remember { mutableStateOf(false) }
    var showLicense by remember { mutableStateOf(false) }
    var showPrivacy by remember { mutableStateOf(false) }

    // 应用图标（从系统取真实图标，和桌面上看到的一致）
    val iconBitmap = remember {
        try {
            val d = context.packageManager.getApplicationIcon(context.packageName)
            val bmp = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            d.setBounds(0, 0, 240, 240)
            d.draw(canvas)
            bmp.asImageBitmap()
        } catch (e: Exception) {
            null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        // 顶部返回
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(colors.backgroundCard)
                    .align(Alignment.CenterStart)
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowLeft,
                    contentDescription = "返回",
                    tint = colors.textPrimary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        // 图标 + 名称 + 版本
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (iconBitmap != null) {
                Image(
                    bitmap = iconBitmap,
                    contentDescription = null,
                    modifier = Modifier
                        .size(76.dp)
                        .clip(RoundedCornerShape(18.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(colors.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Text("伴", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = androidx.compose.ui.graphics.Color.White)
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "伴语",
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Version ${BuildConfig.VERSION_NAME}",
                fontSize = 14.sp,
                color = colors.textSecondary
            )
        }

        Spacer(modifier = Modifier.height(40.dp))

        // 中部入口（仿微信关于页的三行）
        AboutRow(title = "功能介绍", onClick = { showFeatures = true })
        AboutDivider()
        AboutRow(title = "意见反馈", onClick = { showFeedback = true })
        AboutDivider()
        AboutRow(title = "版本更新", onClick = { onCheckUpdate?.invoke() })

        Spacer(modifier = Modifier.weight(1f))

        // 底部许可 / 隐私 / 版权
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row {
                Text(
                    text = "《开源许可》",
                    fontSize = 12.5.sp,
                    color = colors.primary,
                    modifier = Modifier.clickable { showLicense = true }
                )
                Spacer(modifier = Modifier.width(14.dp))
                Text(
                    text = "《隐私保护指引》",
                    fontSize = 12.5.sp,
                    color = colors.primary,
                    modifier = Modifier.clickable { showPrivacy = true }
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "社区独立维护 · 基于开源项目「肉包」二次开发",
                fontSize = 12.sp,
                color = colors.textHint
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = "GitHub：github.com/Mini30933/Banyu",
                fontSize = 12.sp,
                color = colors.textHint
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = "Mini30933 版权所有",
                fontSize = 12.sp,
                color = colors.textHint
            )
            Text(
                text = "Copyright © 2026 Mini30933. All Rights Reserved.",
                fontSize = 12.sp,
                color = colors.textHint
            )
        }
    }

    if (showFeatures) {
        AlertDialog(
            onDismissRequest = { showFeatures = false },
            containerColor = colors.backgroundCard,
            title = { Text("功能介绍", color = colors.textPrimary) },
            text = {
                Text(
                    "· 角色陪伴聊天：在设置里填好角色设定，TA 就是你要的那个角色\n" +
                        "· 操控手机：聊天中让 TA 做事，TA 会自己操作你的手机完成\n" +
                        "· 多会话抽屉：对话可搜索、置顶、重命名、分组管理\n" +
                        "· 导入 DeepSeek 聊天记录：把那边的上下文整段搬过来接着聊\n" +
                        "· 对话压缩：长聊不丢记录，压缩只影响发给模型的内容\n" +
                        "· 应用内更新：打开应用自动检查新版本",
                    color = colors.textSecondary, fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(onClick = { showFeatures = false }) {
                    Text("知道了", color = colors.primary)
                }
            }
        )
    }

    if (showFeedback) {
        AlertDialog(
            onDismissRequest = { showFeedback = false },
            containerColor = colors.backgroundCard,
            title = { Text("意见反馈", color = colors.textPrimary) },
            text = {
                Text(
                    "联系作者：Mini30933@qq.com\n也可以到 GitHub 仓库提 Issue：\ngithub.com/Mini30933/Banyu",
                    color = colors.textSecondary, fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(onClick = { showFeedback = false }) {
                    Text("关闭", color = colors.primary)
                }
            }
        )
    }

    if (showLicense) {
        AlertDialog(
            onDismissRequest = { showLicense = false },
            containerColor = colors.backgroundCard,
            title = { Text("开源许可", color = colors.textPrimary) },
            text = {
                Text(
                    "伴语是社区独立维护的分支，基于开源项目「肉包」（Turbo1123/roubao）二次开发，遵循 MIT 许可证。\n\n本应用与原作者无隶属关系，更新与维护由社区独立进行。",
                    color = colors.textSecondary, fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(onClick = { showLicense = false }) {
                    Text("关闭", color = colors.primary)
                }
            }
        )
    }

    if (showPrivacy) {
        AlertDialog(
            onDismissRequest = { showPrivacy = false },
            containerColor = colors.backgroundCard,
            title = { Text("隐私保护指引", color = colors.textPrimary) },
            text = {
                Text(
                    "· 你的聊天记录、角色设定和 API 密钥只保存在本机，不经过任何中转服务器\n" +
                        "· 对话内容只会发送给你自己填写的模型接口\n" +
                        "· 已删除云端崩溃上报，应用不会自动上传任何日志\n" +
                        "· 操控手机时的截图只发给你配置的视觉模型，不做其他用途",
                    color = colors.textSecondary, fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(onClick = { showPrivacy = false }) {
                    Text("关闭", color = colors.primary)
                }
            }
        )
    }
}

@Composable
private fun AboutRow(title: String, onClick: () -> Unit) {
    val colors = BaoziTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 17.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            fontSize = 16.sp,
            color = colors.textPrimary
        )
        Spacer(modifier = Modifier.weight(1f))
        Icon(
            imageVector = Icons.Default.KeyboardArrowRight,
            contentDescription = null,
            tint = colors.textHint,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun AboutDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .height(0.6.dp)
            .background(BaoziTheme.colors.backgroundInput)
    )
}
