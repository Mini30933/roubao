package com.roubao.autopilot.data

import android.content.Context
import com.roubao.autopilot.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/** 最新版本信息（来自发行版附带的 version.json） */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val notes: String,
    val apkUrl: String,
    val apkSize: Long,
    val force: Boolean = false  // true = 强制更新，弹窗关不掉
)

/**
 * 应用更新检查，无需自有服务器：
 * 主通道走 Gitee（国内直连快）：先查 Gitee 最新发行版，找到其中附带的
 * version.json 读版本号与下载地址；Gitee 不通时兜底走 GitHub 的固定直链。
 * 每次发版时在发行版里附一个 version.json，应用读它比对版本号。
 */
object UpdateChecker {
    private const val GITEE_LATEST_API =
        "https://gitee.com/api/v5/repos/mini30933/banyu/releases/latest"
    private const val GITHUB_VERSION_JSON_URL =
        "https://github.com/Mini30933/Banyu/releases/latest/download/version.json"

    /** 有新版返回信息，没有/失败返回 null（静默，不打扰用户） */
    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        // 主通道：Gitee
        fetchGiteeInfo(client)?.let { return@withContext it }
        // 兜底：GitHub
        val body = fetchText(client, GITHUB_VERSION_JSON_URL) ?: return@withContext null
        parseInfo(body)
    }

    /** 经 Gitee API 找到最新发行版的 version.json 并解析；任何一步失败返回 null */
    private fun fetchGiteeInfo(client: OkHttpClient): UpdateInfo? {
        val apiBody = fetchText(client, GITEE_LATEST_API) ?: return null
        val assets = try {
            JSONObject(apiBody).optJSONArray("assets")
        } catch (e: Exception) {
            null
        } ?: return null
        var versionJsonUrl: String? = null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            if (asset.optString("name") == "version.json") {
                versionJsonUrl = asset.optString("browser_download_url")
                break
            }
        }
        val url = versionJsonUrl?.takeIf { it.isNotBlank() } ?: return null
        val body = fetchText(client, url) ?: return null
        return parseInfo(body)
    }

    private fun fetchText(client: OkHttpClient, url: String): String? = try {
        client.newCall(Request.Builder().url(url).build()).execute().use {
            if (it.isSuccessful) it.body?.string() else null
        }
    } catch (e: Exception) {
        null
    }

    /** 解析 version.json；不是新版或字段不全返回 null */
    private fun parseInfo(body: String): UpdateInfo? = try {
        val json = JSONObject(body)
        val code = json.optInt("versionCode", 0)
        val apkUrl = json.optString("apkUrl", "")
        if (code <= BuildConfig.VERSION_CODE || apkUrl.isBlank()) {
            null
        } else {
            UpdateInfo(
                versionCode = code,
                versionName = json.optString("versionName", ""),
                notes = json.optString("notes", ""),
                apkUrl = apkUrl,
                apkSize = json.optLong("apkSize", 0),
                force = json.optBoolean("force", false)
            )
        }
    } catch (e: Exception) {
        null
    }

    /** 下载新版 APK 到 cacheDir/update.apk（覆盖安装走系统安装器，数据保留）；失败返回 null */
    suspend fun downloadApk(context: Context, info: UpdateInfo, onProgress: (Int) -> Unit): File? =
        withContext(Dispatchers.IO) {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(120, TimeUnit.SECONDS)
                    .build()
                val response = client.newCall(Request.Builder().url(info.apkUrl).build()).execute()
                response.use {
                    if (!it.isSuccessful) return@withContext null
                    val body = it.body ?: return@withContext null
                    val total = if (info.apkSize > 0) info.apkSize else body.contentLength()
                    val out = File(context.cacheDir, "update.apk")
                    body.byteStream().use { input ->
                        FileOutputStream(out).use { output ->
                            val buf = ByteArray(64 * 1024)
                            var sum = 0L
                            while (true) {
                                val read = input.read(buf)
                                if (read < 0) break
                                output.write(buf, 0, read)
                                sum += read
                                if (total > 0) onProgress((sum * 100 / total).toInt().coerceIn(0, 100))
                            }
                        }
                    }
                    // 大小对不上 = 下载不完整，删掉别装
                    if (info.apkSize > 0 && out.length() != info.apkSize) {
                        out.delete()
                        return@withContext null
                    }
                    out
                }
            } catch (e: Exception) {
                null
            }
        }
}
