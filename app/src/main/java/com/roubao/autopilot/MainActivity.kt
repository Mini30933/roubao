package com.roubao.autopilot

import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import android.net.Uri
import android.provider.Settings
import com.roubao.autopilot.agent.MobileAgent
import com.roubao.autopilot.controller.AccessibilityBridge
import com.roubao.autopilot.controller.AppScanner
import com.roubao.autopilot.controller.DeviceController
import com.roubao.autopilot.data.*
import com.roubao.autopilot.ui.SwarmLaunchAnimation
import com.roubao.autopilot.ui.screens.*
import com.roubao.autopilot.ui.theme.*
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import com.roubao.autopilot.vlm.GUIOwlClient
import com.roubao.autopilot.vlm.MAIUIClient
import com.roubao.autopilot.vlm.VLMClient
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.rememberCoroutineScope
import androidx.core.content.FileProvider
import android.content.Intent
import com.roubao.autopilot.data.UpdateChecker
import com.roubao.autopilot.data.UpdateInfo
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import android.util.Log

private const val TAG = "MainActivity"

sealed class Screen(val route: String, val title: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    object Chat : Screen("chat", "对话", Icons.Outlined.Person, Icons.Filled.Person)
    object Home : Screen("home", "操控", Icons.Outlined.Home, Icons.Filled.Home)
    object Capabilities : Screen("capabilities", "能力", Icons.Outlined.Star, Icons.Filled.Star)
    object History : Screen("history", "记录", Icons.Outlined.List, Icons.Filled.List)
    object Settings : Screen("settings", "设置", Icons.Outlined.Settings, Icons.Filled.Settings)
}

// 冷启动动画是否已在本进程播放过（每次启动 App 只播一次）
private var launchAnimShown = false

class MainActivity : ComponentActivity() {

    private lateinit var deviceController: DeviceController
    private lateinit var settingsManager: SettingsManager
    private lateinit var executionRepository: ExecutionRepository

    private val mobileAgent = mutableStateOf<MobileAgent?>(null)
    private var shizukuAvailable = mutableStateOf(false)

    // 当前执行的协程 Job（用于停止任务）
    private var currentExecutionJob: kotlinx.coroutines.Job? = null

    // 执行记录列表
    private val executionRecords = mutableStateOf<List<ExecutionRecord>>(emptyList())

    // 是否正在执行（点击发送后立即为 true）
    private val isExecuting = mutableStateOf(false)

    // 当前执行的记录 ID（用于停止后跳转）
    private val currentRecordId = mutableStateOf<String?>(null)

    // 是否需要跳转到记录详情（悬浮窗停止后触发）
    private val shouldNavigateToRecord = mutableStateOf(false)

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.d(TAG, "Shizuku binder received")
        shizukuAvailable.value = true
        if (checkShizukuPermission()) {
            Log.d(TAG, "Shizuku permission granted, binding service")
            deviceController.bindService()
        } else {
            Log.d(TAG, "Shizuku permission not granted")
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.d(TAG, "Shizuku binder dead")
        shizukuAvailable.value = false
    }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        Log.d(TAG, "Shizuku permission result: $grantResult")
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            deviceController.bindService()
            Toast.makeText(this, "Shizuku 权限已获取", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        // 设置边到边显示，深色状态栏和导航栏
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )

        deviceController = DeviceController(this)
        deviceController.setCacheDir(cacheDir)
        settingsManager = SettingsManager(this)
        executionRepository = ExecutionRepository(this)

        // 加载执行记录
        lifecycleScope.launch {
            executionRecords.value = executionRepository.getAllRecords()
        }

        // 添加 Shizuku 监听器
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)

        // 检查 Shizuku 状态
        checkAndUpdateShizukuStatus()

        // 预加载已安装应用
        lifecycleScope.launch(Dispatchers.IO) {
            AppScanner(this@MainActivity).getApps()
        }

        setContent {
            val settings by settingsManager.settings.collectAsState()
            BaoziTheme(themeMode = settings.themeMode) {
                val colors = BaoziTheme.colors
                // 动态更新系统栏颜色
                SideEffect {
                    val window = this@MainActivity.window
                    window.statusBarColor = colors.background.toArgb()
                    window.navigationBarColor = colors.backgroundCard.toArgb()
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !colors.isDark
                        isAppearanceLightNavigationBars = !colors.isDark
                    }
                }

                // 首次启动显示引导画面
                if (!settings.hasSeenOnboarding) {
                    OnboardingScreen(
                        onComplete = {
                            settingsManager.setOnboardingSeen()
                        }
                    )
                } else {
                    MainApp()
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun MainApp() {
        var currentScreen by remember { mutableStateOf<Screen>(Screen.Chat) }
        var showLaunchAnim by remember { mutableStateOf(!launchAnimShown) }
        if (showLaunchAnim) {
            SideEffect { launchAnimShown = true }
        }
        var selectedRecord by remember { mutableStateOf<ExecutionRecord?>(null) }

        val settings by settingsManager.settings.collectAsState()
        val colors = BaoziTheme.colors
        val agent = mobileAgent.value
        val agentState by agent?.state?.collectAsState() ?: remember { mutableStateOf(null) }
        val logs by agent?.logs?.collectAsState() ?: remember { mutableStateOf(emptyList<String>()) }
        val records by remember { executionRecords }
        val isShizukuAvailable = shizukuAvailable.value && checkShizukuPermission()
        val executing by remember { isExecuting }
        val navigateToRecord by remember { shouldNavigateToRecord }
        val recordId by remember { currentRecordId }

        // 监听跳转事件
        LaunchedEffect(navigateToRecord, recordId) {
            if (navigateToRecord && recordId != null) {
                // 找到对应的记录并跳转
                val record = records.find { it.id == recordId }
                if (record != null) {
                    selectedRecord = record
                    currentScreen = Screen.History
                }
                shouldNavigateToRecord.value = false
            }
        }

        // 应用更新（GitHub Releases）：每次冷启动静默检查一次，非强制，覆盖安装保数据
        val updateContext = LocalContext.current
        var pendingUpdate by remember { mutableStateOf<UpdateInfo?>(null) }
        var updateDownloading by remember { mutableStateOf(false) }
        var updateProgress by remember { mutableStateOf(0) }
        val updateScope = rememberCoroutineScope()

        fun startUpdateDownload(info: UpdateInfo) {
            if (!updateContext.packageManager.canRequestPackageInstalls()) {
                Toast.makeText(updateContext, "请先允许伴语安装应用，开启后回来点「立即更新」", Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + updateContext.packageName))
                updateContext.startActivity(intent)
                return
            }
            updateDownloading = true
            updateProgress = 0
            updateScope.launch {
                val file = UpdateChecker.downloadApk(updateContext, info) { updateProgress = it }
                updateDownloading = false
                if (file != null) {
                    pendingUpdate = null
                    val uri = FileProvider.getUriForFile(updateContext, updateContext.packageName + ".fileprovider", file)
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    updateContext.startActivity(intent)
                } else {
                    Toast.makeText(updateContext, "下载失败，请检查网络后重试", Toast.LENGTH_SHORT).show()
                }
            }
        }

        LaunchedEffect(Unit) {
            pendingUpdate = UpdateChecker.check()
        }

        pendingUpdate?.let { info ->
            AlertDialog(
                onDismissRequest = { if (!updateDownloading) pendingUpdate = null },
                containerColor = colors.backgroundCard,
                title = { Text("发现新版本 ${info.versionName}", color = colors.textPrimary) },
                text = {
                    Column {
                        if (updateDownloading) {
                            Text("正在下载更新… $updateProgress%", color = colors.textSecondary, fontSize = 14.sp)
                            Spacer(Modifier.height(12.dp))
                            LinearProgressIndicator(progress = updateProgress / 100f, modifier = Modifier.fillMaxWidth())
                        } else {
                            Text(
                                (info.notes.ifBlank { "有新版本可用。" }) + "\n\n更新是覆盖安装，聊天记录和设置都会保留。",
                                color = colors.textSecondary, fontSize = 14.sp
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(enabled = !updateDownloading, onClick = { startUpdateDownload(info) }) {
                        Text(if (updateDownloading) "下载中…" else "立即更新", color = colors.primary)
                    }
                },
                dismissButton = {
                    TextButton(enabled = !updateDownloading, onClick = { pendingUpdate = null }) {
                        Text("以后再说", color = colors.textSecondary)
                    }
                }
            )
        }

        Scaffold(
            modifier = Modifier.background(colors.background),
            containerColor = colors.background,
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                // 处理系统返回手势
                BackHandler(enabled = selectedRecord != null) {
                    selectedRecord = null
                }
                BackHandler(enabled = selectedRecord == null && currentScreen != Screen.Chat) {
                    currentScreen = Screen.Chat
                }

                // 详情页优先显示
                if (selectedRecord != null) {
                    HistoryDetailScreen(
                        record = selectedRecord!!,
                        onBack = { selectedRecord = null }
                    )
                } else {
                    // 主页面切换
                    AnimatedContent(
                        targetState = currentScreen,
                        transitionSpec = {
                            fadeIn() togetherWith fadeOut()
                        },
                        label = "screen"
                    ) { screen ->
                        when (screen) {
                            Screen.Chat -> {
                                ChatScreen(
                                    settings = settings,
                                    onControlRequest = { instruction, onResult ->
                                        runAgent(
                                            instruction = instruction,
                                            apiKey = settings.apiKey,
                                            baseUrl = settings.baseUrl,
                                            model = settings.model,
                                            maxSteps = settings.maxSteps,
                                            isGUIAgent = settings.currentProvider.isGUIAgent,
                                            providerId = settings.currentProviderId,
                                            onControlResult = onResult
                                        )
                                    },
                                    onOpenSettings = { currentScreen = Screen.Settings }
                                )
                            }
                            Screen.Home -> {
                                // 每次进入首页都检测 Shizuku 状态
                                LaunchedEffect(Unit) {
                                    checkAndUpdateShizukuStatus()
                                }
                                HomeScreen(
                                    agentState = agentState,
                                    logs = logs,
                                    onExecute = { instruction ->
                                        runAgent(
                                            instruction = instruction,
                                            apiKey = settings.apiKey,
                                            baseUrl = settings.baseUrl,
                                            model = settings.model,
                                            maxSteps = settings.maxSteps,
                                            isGUIAgent = settings.currentProvider.isGUIAgent,
                                            providerId = settings.currentProviderId
                                        )
                                    },
                                    onStop = {
                                        mobileAgent.value?.stop()
                                    },
                                    shizukuAvailable = isShizukuAvailable,
                                    accessibilityReady = settings.controlMode == ControlMode.ACCESSIBILITY &&
                                        (AccessibilityBridge.isConnected() || AccessibilityBridge.isEnabled(this@MainActivity)),
                                    currentModel = settings.model,
                                    onRefreshShizuku = { refreshShizukuStatus() },
                                    onShizukuRequired = { },
                                    isExecuting = executing
                                )
                            }
                            Screen.Capabilities -> CapabilitiesScreen()
                            Screen.History -> HistoryScreen(
                                records = records,
                                onRecordClick = { record -> selectedRecord = record },
                                onDeleteRecord = { id -> deleteRecord(id) }
                            )
                            Screen.Settings -> SettingsScreen(
                                settings = settings,
                                onBack = { currentScreen = Screen.Chat },
                                onUpdateApiKey = { settingsManager.updateApiKey(it) },
                                onUpdateBaseUrl = { settingsManager.updateBaseUrl(it) },
                                onUpdateModel = { settingsManager.updateModel(it) },
                                onUpdateCachedModels = { settingsManager.updateCachedModels(it) },
                                onUpdateThemeMode = { settingsManager.updateThemeMode(it) },
                                onUpdateMaxSteps = { settingsManager.updateMaxSteps(it) },
                                onUpdateCloudCrashReport = { enabled ->
                                    settingsManager.updateCloudCrashReportEnabled(enabled)
                                    App.getInstance().updateCloudCrashReportEnabled(enabled)
                                },
                                onUpdateRootModeEnabled = { settingsManager.updateRootModeEnabled(it) },
                                onUpdateSuCommandEnabled = { settingsManager.updateSuCommandEnabled(it) },
                                onUpdateControlMode = { settingsManager.updateControlMode(it) },
                                onUpdateCustomSystemPrompt = { settingsManager.updateCustomSystemPrompt(it) },
                                onUpdateCompressionModel = { settingsManager.updateCompressionModel(it) },
                                onUpdateCompressionTriggerPercent = { settingsManager.updateCompressionTriggerPercent(it) },
                                onUpdateCompressionKeepPercent = { settingsManager.updateCompressionKeepPercent(it) },
                                onUpdateModelContextLength = { settingsManager.updateModelContextLength(it) },
                                onClearLongTermMemory = { settingsManager.clearLongTermMemory() },
                                onSelectProvider = { settingsManager.selectProvider(it) },
                                shizukuAvailable = isShizukuAvailable,
                                shizukuPrivilegeLevel = if (isShizukuAvailable) {
                                    when (deviceController.getShizukuPrivilegeLevel()) {
                                        DeviceController.ShizukuPrivilegeLevel.ROOT -> "ROOT"
                                        DeviceController.ShizukuPrivilegeLevel.ADB -> "ADB"
                                        else -> "NONE"
                                    }
                                } else "NONE",
                                onFetchModels = { onSuccess, onError ->
                                    lifecycleScope.launch {
                                        val result = VLMClient.fetchModels(settings.baseUrl, settings.apiKey)
                                        result.onSuccess { models ->
                                            onSuccess(models)
                                            // 顺带读取各模型的上下文长度（接口有返回的话），供聊天压缩自动识别
                                            VLMClient.fetchModelContexts(settings.baseUrl, settings.apiKey)
                                                .onSuccess { settingsManager.updateModelContexts(it) }
                                        }.onFailure { error ->
                                            onError(error.message ?: "未知错误")
                                        }
                                    }
                                },
                                onCheckUpdate = {
                                    lifecycleScope.launch {
                                        val info = UpdateChecker.check()
                                        if (info != null) {
                                            pendingUpdate = info
                                        } else {
                                            Toast.makeText(this@MainActivity, "已是最新版本", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            )
                        }
                    }
                }

                // 冷启动 swarm 光标动画（每次启动只播一次，点击跳过）
                if (showLaunchAnim) {
                    SwarmLaunchAnimation(
                        darkTheme = colors.isDark,
                        onFinished = { showLaunchAnim = false }
                    )
                }
            }
        }

    }

    private fun deleteRecord(id: String) {
        lifecycleScope.launch {
            executionRepository.deleteRecord(id)
            executionRecords.value = executionRepository.getAllRecords()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        deviceController.unbindService()
    }

    private fun checkShizukuPermission(): Boolean {
        return try {
            val granted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            Log.d(TAG, "checkShizukuPermission: $granted")
            granted
        } catch (e: Exception) {
            Log.e(TAG, "checkShizukuPermission error", e)
            false
        }
    }

    private fun checkAndUpdateShizukuStatus() {
        Log.d(TAG, "checkAndUpdateShizukuStatus called")
        try {
            val binderAlive = Shizuku.pingBinder()
            Log.d(TAG, "Shizuku pingBinder: $binderAlive")

            if (binderAlive) {
                shizukuAvailable.value = true
                val hasPermission = checkShizukuPermission()
                Log.d(TAG, "Shizuku hasPermission: $hasPermission")

                if (hasPermission) {
                    Log.d(TAG, "Binding Shizuku service")
                    deviceController.bindService()
                } else {
                    Log.d(TAG, "Requesting Shizuku permission")
                    requestShizukuPermission()
                }
            } else {
                Log.d(TAG, "Shizuku binder not alive")
                shizukuAvailable.value = false
            }
        } catch (e: Exception) {
            Log.e(TAG, "checkAndUpdateShizukuStatus error", e)
            shizukuAvailable.value = false
        }
    }

    private fun refreshShizukuStatus() {
        Log.d(TAG, "refreshShizukuStatus called by user")
        Toast.makeText(this, "正在检查 Shizuku 状态...", Toast.LENGTH_SHORT).show()
        checkAndUpdateShizukuStatus()

        if (shizukuAvailable.value && checkShizukuPermission()) {
            Toast.makeText(this, "Shizuku 已连接", Toast.LENGTH_SHORT).show()
        } else if (shizukuAvailable.value) {
            Toast.makeText(this, "请在弹窗中授权 Shizuku", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "请先启动 Shizuku App", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestShizukuPermission() {
        try {
            if (!Shizuku.pingBinder()) {
                Toast.makeText(this, "请先启动 Shizuku App", Toast.LENGTH_SHORT).show()
                return
            }

            if (Shizuku.isPreV11()) {
                Toast.makeText(this, "Shizuku 版本过低", Toast.LENGTH_SHORT).show()
                return
            }

            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Shizuku 权限已获取", Toast.LENGTH_SHORT).show()
                shizukuAvailable.value = true
                deviceController.bindService()
                return
            }

            Shizuku.requestPermission(0)
        } catch (e: Exception) {
            Toast.makeText(this, "请先启动 Shizuku App", Toast.LENGTH_SHORT).show()
        }
    }

    private fun runAgent(
        instruction: String,
        apiKey: String,
        baseUrl: String,
        model: String,
        maxSteps: Int,
        isGUIAgent: Boolean = false,
        providerId: String = "",
        onControlResult: ((Boolean, String) -> Unit)? = null
    ) {
        if (instruction.isBlank()) {
            Toast.makeText(this, "请输入指令", Toast.LENGTH_SHORT).show()
            onControlResult?.invoke(false, "指令为空")
            return
        }
        // MAI-UI 本地部署不需要 API Key
        val requiresApiKey = providerId != "mai_ui"
        if (requiresApiKey && apiKey.isBlank()) {
            Toast.makeText(this, "请输入 API Key", Toast.LENGTH_SHORT).show()
            onControlResult?.invoke(false, "未配置 API Key")
            return
        }

        // 检查悬浮窗权限
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请授予悬浮窗权限", Toast.LENGTH_LONG).show()
            val intent = android.content.Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            onControlResult?.invoke(false, "需要先授予悬浮窗权限才能操控手机")
            return
        }

        // 立即设置执行状态为 true，显示停止按钮
        isExecuting.value = true

        // 根据服务商类型创建相应的客户端
        if (isGUIAgent) {
            // GUI-Owl 模式
            val guiOwlClient = GUIOwlClient(
                apiKey = apiKey,
                model = model.ifBlank { "pre-gui_owl_7b" }
            )
            mobileAgent.value = MobileAgent(
                vlmClient = null,
                controller = deviceController,
                context = this,
                guiOwlClient = guiOwlClient
            )
        } else if (providerId == "mai_ui") {
            // MAI-UI 模式
            val maiuiClient = MAIUIClient(
                baseUrl = baseUrl.ifBlank { "http://localhost:8000/v1" },
                model = model.ifBlank { "MAI-UI-2B" }
            )
            mobileAgent.value = MobileAgent(
                vlmClient = null,
                controller = deviceController,
                context = this,
                maiuiClient = maiuiClient
            )
        } else {
            // OpenAI 兼容模式 (阿里云 Qwen-VL, OpenAI, OpenRouter 等)
            val vlmClient = VLMClient(
                apiKey = apiKey,
                baseUrl = baseUrl.ifBlank { "https://dashscope.aliyuncs.com/compatible-mode/v1" },
                model = model.ifBlank { "qwen3-vl-plus" }
            )
            mobileAgent.value = MobileAgent(vlmClient, deviceController, this)
        }

        // 设置停止回调，用于取消协程
        mobileAgent.value?.onStopRequested = {
            currentExecutionJob?.cancel()
            currentExecutionJob = null
        }

        // 创建执行记录
        val record = ExecutionRecord(
            title = generateTitle(instruction),
            instruction = instruction,
            startTime = System.currentTimeMillis(),
            status = ExecutionStatus.RUNNING
        )

        // 保存当前记录 ID，用于停止后跳转
        currentRecordId.value = record.id

        // 取消之前的任务（如果有）
        currentExecutionJob?.cancel()

        currentExecutionJob = lifecycleScope.launch {
            // 保存初始记录
            executionRepository.saveRecord(record)
            executionRecords.value = executionRepository.getAllRecords()

            try {
                val result = mobileAgent.value!!.runInstruction(instruction, maxSteps)

                // 更新记录状态
                val agentState = mobileAgent.value?.state?.value
                val steps = agentState?.executionSteps ?: emptyList()
                val currentLogs = mobileAgent.value?.logs?.value ?: emptyList()

                val updatedRecord = record.copy(
                    endTime = System.currentTimeMillis(),
                    status = if (result.success) ExecutionStatus.COMPLETED else ExecutionStatus.FAILED,
                    steps = steps,
                    logs = currentLogs,
                    resultMessage = result.message
                )
                executionRepository.saveRecord(updatedRecord)
                executionRecords.value = executionRepository.getAllRecords()

                // 写入跨会话长期记忆，供后续任务保持角色与记忆连贯（长上下文）
                val memoryEntry = buildString {
                    append("【任务】").append(instruction).append('\n')
                    append("【结果】").append(result.message).append('\n')
                    val descs = steps.mapNotNull { it.description.takeIf { d -> d.isNotBlank() } }.takeLast(8)
                    if (descs.isNotEmpty()) {
                        append("【过程】").append(descs.joinToString("；"))
                    }
                }
                settingsManager.appendLongTermMemory(memoryEntry)

                onControlResult?.invoke(result.success, result.message)

                Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_LONG).show()

                // 重置执行状态
                isExecuting.value = false

                // 延迟3秒后清空日志，恢复默认状态
                kotlinx.coroutines.delay(3000)
                mobileAgent.value?.clearLogs()
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 用户取消任务 - 使用 NonCancellable 确保清理操作完成
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    val agentState = mobileAgent.value?.state?.value
                    val steps = agentState?.executionSteps ?: emptyList()
                    val currentLogs = mobileAgent.value?.logs?.value ?: emptyList()

                    println("[MainActivity] 取消任务 - steps: ${steps.size}, logs: ${currentLogs.size}")

                    val updatedRecord = record.copy(
                        endTime = System.currentTimeMillis(),
                        status = ExecutionStatus.STOPPED,
                        steps = steps,
                        logs = currentLogs,
                        resultMessage = "已取消"
                    )
                    executionRepository.saveRecord(updatedRecord)
                    executionRecords.value = executionRepository.getAllRecords()

                    // 重置执行状态
                    isExecuting.value = false

                    onControlResult?.invoke(false, "操作已取消")

                    Toast.makeText(this@MainActivity, "任务已停止", Toast.LENGTH_SHORT).show()
                    mobileAgent.value?.clearLogs()

                    // 触发跳转到记录详情页
                    shouldNavigateToRecord.value = true
                }
            } catch (e: Exception) {
                // 更新失败记录
                val currentLogs = mobileAgent.value?.logs?.value ?: emptyList()
                val updatedRecord = record.copy(
                    endTime = System.currentTimeMillis(),
                    status = ExecutionStatus.FAILED,
                    logs = currentLogs,
                    resultMessage = "错误: ${e.message}"
                )
                executionRepository.saveRecord(updatedRecord)
                executionRecords.value = executionRepository.getAllRecords()

                // 重置执行状态
                isExecuting.value = false

                onControlResult?.invoke(false, "错误: ${e.message}")

                Toast.makeText(this@MainActivity, "错误: ${e.message}", Toast.LENGTH_LONG).show()

                // 延迟3秒后清空日志，恢复默认状态
                kotlinx.coroutines.delay(3000)
                mobileAgent.value?.clearLogs()
            }
        }
    }

    private fun generateTitle(instruction: String): String {
        // 生成简短标题
        val keywords = listOf(
            "打开" to "打开应用",
            "点" to "点餐",
            "发" to "发送消息",
            "看" to "浏览内容",
            "搜" to "搜索",
            "设置" to "调整设置",
            "播放" to "播放媒体"
        )
        for ((key, title) in keywords) {
            if (instruction.contains(key)) {
                return title
            }
        }
        return if (instruction.length > 10) instruction.take(10) + "..." else instruction
    }
}
