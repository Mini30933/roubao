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

/** GitHub Release 上的最新版本信息（来自 version.json） */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val notes: String,
    val apkUrl: String,
    val apkSize: Long,
    val force: Boolean = false  // true = 强制更新，弹窗关不掉
)

/**
 * 基于 GitHub Releases 的应用更新检查，无需自有服务器：
 * 每次发版时在 Release 里附一个 version.json，应用读它比对版本号。
 * latest/download/ 固定指向最新 Release 的同名附件。
 */
object UpdateChecker {
    private const val VERSION_JSON_URL =
        "https://github.com/Mini30933/Banyu/releases/latest/download/version.json"

    /** 有新版返回信息，没有/失败返回 null（静默，不打扰用户） */
    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
            val response = client.newCall(Request.Builder().url(VERSION_JSON_URL).build()).execute()
            response.use {
                if (!it.isSuccessful) return@withContext null
                val body = it.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                val code = json.optInt("versionCode", 0)
                if (code <= BuildConfig.VERSION_CODE) return@withContext null
                val apkUrl = json.optString("apkUrl", "")
                if (apkUrl.isBlank()) return@withContext null
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
