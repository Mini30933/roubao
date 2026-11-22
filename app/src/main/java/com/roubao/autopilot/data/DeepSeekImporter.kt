package com.roubao.autopilot.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * DeepSeek 官方数据导出包（export_format_version 3）导入器。
 *
 * 导出包结构：
 * - manifest.json / user.json（忽略，含账号信息，不读取）
 * - chat_sessions/<会话id>.json：一个会话一个文件
 *   {id, title, pinned, current_message_id, inserted_at, updated_at, messages[]}
 *   message：{id, parent_id, role: USER/ASSISTANT, inserted_at, fragments[]}
 *   fragment：REQUEST/RESPONSE/TEMPLATE_RESPONSE 带 content 文本；
 *             FILE 带 files[].file_name；THINK 与各类 TOOL_* 不导入。
 * - chat_sessions/<会话id>_files/：附件原文件（不导入，仅在正文里以 [附件：文件名] 标注）
 *
 * 消息是树形结构（重新生成会产生分支）：从 current_message_id 沿 parent_id
 * 回溯得到当前分支；回溯不到时按 id 升序取全部。
 */
object DeepSeekImporter {

    data class ImportOutcome(val added: Int, val skipped: Int)

    private const val MAX_ZIP_BYTES = 300L * 1024 * 1024

    /** 从直链下载导出包（跟随重定向，OkHttp 默认处理） */
    suspend fun downloadZip(url: String): ByteArray = withContext(Dispatchers.IO) {
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .build()
        val resp = client.newCall(Request.Builder().url(url.trim()).build()).execute()
        resp.use {
            if (!resp.isSuccessful) throw IllegalStateException("下载失败（HTTP ${resp.code}）")
            val body = resp.body ?: throw IllegalStateException("下载内容为空")
            if (body.contentLength() > MAX_ZIP_BYTES) throw IllegalStateException("文件过大，已取消")
            body.bytes()
        }
    }

    /** 解析导出 zip，返回会话列表；找不到任何会话时抛异常说明原因 */
    fun parseExport(zipBytes: ByteArray): List<ChatSession> {
        val sessions = mutableListOf<ChatSession>()
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name
                val isSessionJson = !entry.isDirectory &&
                    name.startsWith("chat_sessions/") && name.endsWith(".json") &&
                    !name.removePrefix("chat_sessions/").contains('/')
                if (isSessionJson) {
                    val text = zis.readBytes().toString(Charsets.UTF_8)
                    runCatching { parseSession(JSONObject(text)) }.getOrNull()?.let { sessions.add(it) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        if (sessions.isEmpty()) {
            throw IllegalStateException("压缩包里没找到 DeepSeek 对话数据，请确认是官方导出的 deepseek_data 压缩包")
        }
        return sessions
    }

    private fun parseSession(root: JSONObject): ChatSession? {
        val msgsArray = root.optJSONArray("messages") ?: return null
        val byId = HashMap<Int, JSONObject>()
        for (i in 0 until msgsArray.length()) {
            val m = msgsArray.getJSONObject(i)
            byId[m.optInt("id")] = m
        }
        if (byId.isEmpty()) return null

        // 沿当前分支回溯
        val chain = mutableListOf<JSONObject>()
        val seen = HashSet<Int>()
        var cur = root.optInt("current_message_id", 0)
        while (cur != 0 && byId.containsKey(cur) && seen.add(cur)) {
            val m = byId.getValue(cur)
            chain.add(m)
            cur = if (m.isNull("parent_id")) 0 else m.optInt("parent_id", 0)
        }
        val ordered = if (chain.isEmpty()) {
            byId.values.sortedBy { it.optInt("id") }
        } else {
            chain.reversed()
        }

        val messages = mutableListOf<ChatMessage>()
        var lastTs = parseIso(root.optString("inserted_at"))
        for (m in ordered) {
            val role = when (m.optString("role")) {
                "USER" -> "user"
                "ASSISTANT" -> "assistant"
                else -> continue
            }
            val text = extractText(m)
            if (text.isBlank()) continue
            val ts = parseIso(m.optString("inserted_at")).takeIf { it > 0 } ?: (lastTs + 1)
            lastTs = ts
            messages.add(ChatMessage(role = role, content = text, timestamp = ts))
        }
        if (messages.isEmpty()) return null

        val created = parseIso(root.optString("inserted_at")).takeIf { it > 0 } ?: messages.first().timestamp
        val updated = parseIso(root.optString("updated_at")).takeIf { it > 0 } ?: messages.last().timestamp
        val title = root.optString("title").ifBlank {
            messages.firstOrNull { it.role == "user" }?.content?.replace("\n", " ")?.take(18) ?: "导入的对话"
        }
        return ChatSession(
            id = root.optString("id").ifBlank { UUID.randomUUID().toString() },
            title = title,
            pinned = root.optBoolean("pinned", false),
            createdAt = created,
            updatedAt = updated,
            messages = messages
        )
    }

    private fun extractText(msg: JSONObject): String {
        val frags = msg.optJSONArray("fragments") ?: return ""
        val sb = StringBuilder()
        fun appendLine(s: String) {
            if (s.isEmpty()) return
            if (sb.isNotEmpty()) sb.append("\n")
            sb.append(s)
        }
        for (i in 0 until frags.length()) {
            val f = frags.getJSONObject(i)
            when (f.optString("type")) {
                "REQUEST", "RESPONSE", "TEMPLATE_RESPONSE" -> appendLine(f.optString("content"))
                "FILE" -> {
                    val files = f.optJSONArray("files")
                    if (files != null) {
                        for (j in 0 until files.length()) {
                            val name = files.getJSONObject(j).optString("file_name")
                            if (name.isNotEmpty()) appendLine("[附件：$name]")
                        }
                    }
                }
                // THINK（思维链）、TOOL_*（联网搜索等工具记录）、TIP 等不导入
            }
        }
        return sb.toString().trim()
    }

    private fun parseIso(s: String): Long {
        if (s.isBlank()) return 0L
        return try {
            Instant.parse(s).toEpochMilli()
        } catch (e: Exception) {
            0L
        }
    }

    /** 当前应用里已有的会话 id 集合（用于导入前标记/排除重复会话） */
    fun existingSessionIds(context: Context): Set<String> =
        ChatRepository(context).loadSessions().map { it.id }.toHashSet()

    /** 把解析出的会话并入现有存储：按会话 id 去重，已存在的跳过。返回 (新增, 跳过)。 */
    fun mergeInto(context: Context, parsed: List<ChatSession>): ImportOutcome {
        val repo = ChatRepository(context)
        val existing = repo.loadSessions()
        val existingIds = existing.map { it.id }.toHashSet()
        val fresh = parsed.filter { it.id !in existingIds }
        if (fresh.isNotEmpty()) {
            repo.saveSessions(existing + fresh)
        }
        return ImportOutcome(added = fresh.size, skipped = parsed.size - fresh.size)
    }
}
