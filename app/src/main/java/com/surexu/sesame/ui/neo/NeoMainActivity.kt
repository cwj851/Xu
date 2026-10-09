package com.surexu.sesame.ui.neo

import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.text.Editable
import android.text.TextWatcher
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import java.io.RandomAccessFile
import com.fasterxml.jackson.databind.ObjectMapper
import com.surexu.sesame.R
import com.surexu.sesame.data.AppConfig
import com.surexu.sesame.data.ConfigV2
import com.surexu.sesame.data.ModelGroup
import com.surexu.sesame.data.RunType
import com.surexu.sesame.data.ViewAppInfo
import com.surexu.sesame.entity.UserEntity
import com.surexu.sesame.util.FileUtil
import com.surexu.sesame.util.LanguageUtil
import com.surexu.sesame.util.Statistics
import com.surexu.sesame.util.StringUtil
import com.surexu.sesame.util.idMap.UserIdMap
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 原创拟态 UI 主界面（原生 XML View 实现）。
 *
 * 完整 APP 骨架：底部导航栏 + 四个页面（首页 / 功能 / 日志 / 设置）。
 * 设计语言：霜白基色 #F6F9FE + 深雾蓝灰阴影 + 霓虹青主色，
 * 凸起/凹陷全部由 drawable 光照层模拟（见 res/drawable/neu_*）。
 *
 * 功能覆盖与模块版一致：一级入口基础/森林/庄园/新村/运动/其他全部真实跳转；
 * 农场(ORCHARD)并入庄园页、会员(MEMBER)并入其他页，金豆记录在日志页"记录"标签。
 */
class NeoMainActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_UI = "sesame_ui_state"
        private const val KEY_LAST_SELECTED_USER = "last_selected_user_id"

        // ===== 激活探测（与旧版模块 UI 主页面逻辑对齐）=====
        /** 激活探测最多重试次数 */
        private const val MAX_RUN_TYPE_PROBE_TIMES = 5
        /** 激活探测重试间隔(毫秒) */
        private const val RUN_TYPE_PROBE_INTERVAL_MS = 3000L

        // ===== 能量统计实时刷新 =====
        /** 能量统计卡自动刷新间隔(毫秒)：注入进程落盘 statistics.json 后 UI 定时拉新 */
        private const val STATS_REFRESH_INTERVAL_MS = 30000L

        // ===== 日志页常量（与旧版模块 UI 日志页逻辑对齐） =====
        /** 日志自动刷新间隔(毫秒) */
        const val LOG_REFRESH_INTERVAL_MS = 1000L
        /** 日志文件尾部最多读取的字节数(1MB) */
        const val LOG_MAX_TAIL_BYTES = 1024 * 1024L
        /** 日志查看时最多展示的条目数 */
        const val LOG_MAX_ENTRIES = 500
    }

    override fun attachBaseContext(newBase: Context) {
        ThemeUtil.applyNightMode()
        var ctx = LanguageUtil.setLocal(newBase)
        val scale = ctx.getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE)
            .getInt(NeoSystemActivity.KEY_UI_SCALE, 100)
        if (scale != 100 && scale >= 50 && scale <= 200) {
            val config = Configuration(ctx.resources.configuration)
            config.densityDpi = ctx.resources.displayMetrics.densityDpi * scale / 100
            ctx = ctx.createConfigurationContext(config)
        }
        super.attachBaseContext(ctx)
    }

    private lateinit var pages: List<View>
    private lateinit var navItems: List<LinearLayout>
    private lateinit var navIcons: List<ImageView>
    private lateinit var navLabels: List<TextView>

    private val REQUEST_IMPORT_BACKUP = 1001
    private val REQUEST_EXPORT_BACKUP = 1002

    /** 立即备份选择位置保存时暂存待写入的配置内容。 */
    private var pendingBackupJson: String? = null

    /** 能量统计卡定时刷新（读 statistics.json 磁盘快照，注入进程落盘后自动拉新）。 */
    private val statsRefreshHandler = Handler(Looper.getMainLooper())
    private val statsRefreshRunnable = object : Runnable {
        override fun run() {
            bindEnergyStats()
            statsRefreshHandler.postDelayed(this, STATS_REFRESH_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.neo_activity_main)
        setupSystemBars()

        val content = findViewById<FrameLayout>(R.id.neo_content)
        pages = listOf(
            layoutInflater.inflate(R.layout.neo_page_home, content, false),
            layoutInflater.inflate(R.layout.neo_page_features, content, false),
            layoutInflater.inflate(R.layout.neo_page_logs, content, false),
            layoutInflater.inflate(R.layout.neo_page_settings, content, false),
        )
        pages.forEach { content.addView(it) }

        navItems = listOf(
            findViewById(R.id.neo_nav_home),
            findViewById(R.id.neo_nav_features),
            findViewById(R.id.neo_nav_logs),
            findViewById(R.id.neo_nav_settings),
        )
        navIcons = listOf(
            findViewById(R.id.neo_nav_home_icon),
            findViewById(R.id.neo_nav_features_icon),
            findViewById(R.id.neo_nav_logs_icon),
            findViewById(R.id.neo_nav_settings_icon),
        )
        navLabels = listOf(
            findViewById(R.id.neo_nav_home_label),
            findViewById(R.id.neo_nav_features_label),
            findViewById(R.id.neo_nav_logs_label),
            findViewById(R.id.neo_nav_settings_label),
        )

        navItems.forEachIndexed { index, item ->
            item.setOnClickListener {
                haptic(item)
                switchPage(index)
            }
        }

        bindHomeActions()
        buildFeatureGrid()
        bindLogsActions()
        bindSettingsActions()
        refreshSettingsAccountButton()

        // 激活状态：初始化运行类型快照并监听变化（模块版逻辑，独立版同样可被注入后广播通知）
        ViewAppInfo.init(applicationContext)
        ViewAppInfo.setRunTypeListener {
            runOnUiThread { updateEnergyStatus() }
        }
        ViewAppInfo.checkRunType()
        updateEnergyStatus()

        val intentFilter = IntentFilter()
        intentFilter.addAction("com.surexu.sesame.status")
        intentFilter.addAction("com.surexu.sesame.update")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, intentFilter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(statusReceiver, intentFilter)
        }

        switchPage(0)
        applyFloatNav()
    }

    override fun onResume() {
        super.onResume()
        // 回到前台时若仍为未加载，按模块版机制周期性发查询广播等待支付宝进程回包（不覆盖晚到的广播信号）
        if (RunType.DISABLE == ViewAppInfo.getRunType()) {
            runTypeProbeHandler.removeCallbacks(runTypeProbeRunnable)
            runTypeProbeTimes = 0
            sendRunTypeQueryBroadcast()
            runTypeProbeHandler.postDelayed(runTypeProbeRunnable, RUN_TYPE_PROBE_INTERVAL_MS)
        }
        // 从账号配置页返回后刷新右上角账号按钮
        refreshSettingsAccountButton()
        // 系统界面设置可能已变更：即时应用悬浮底栏并刷新设置页副标题
        applyFloatNav()
        updateSystemSettingSub()
        // 能量统计实时刷新：回到前台立即拉一次，并启动定时拉新（注入进程落盘 statistics.json 后自动同步）
        bindEnergyStats()
        statsRefreshHandler.removeCallbacks(statsRefreshRunnable)
        statsRefreshHandler.postDelayed(statsRefreshRunnable, STATS_REFRESH_INTERVAL_MS)
    }

    override fun onPause() {
        super.onPause()
        // 离开前台即停止状态轮询，避免后台无谓广播与泄漏（与模块版一致）
        runTypeProbeHandler.removeCallbacks(runTypeProbeRunnable)
        // 离开前台停止能量统计定时刷新
        statsRefreshHandler.removeCallbacks(statsRefreshRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLogAutoRefresh()
        runTypeProbeHandler.removeCallbacks(runTypeProbeRunnable)
        try {
            unregisterReceiver(statusReceiver)
        } catch (e: Exception) {
            // 未注册成功时忽略
        }
    }

    private fun setupSystemBars() {
        window.statusBarColor = getColor(R.color.neo_base)
        window.navigationBarColor = getColor(R.color.neo_base)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !ThemeUtil.isNightActive(window.decorView.context)
            isAppearanceLightNavigationBars = !ThemeUtil.isNightActive(window.decorView.context)
        }
    }

    /** 切换页面与底部导航选中态。 */
    private fun switchPage(index: Int) {
        val activeColor = ContextCompat.getColor(this, R.color.neo_primary)
        val idleColor = ContextCompat.getColor(this, R.color.neo_text_hint)

        pages.forEachIndexed { i, page ->
            page.visibility = if (i == index) View.VISIBLE else View.GONE
        }
        navItems.forEachIndexed { i, item ->
            item.isSelected = i == index
            val color = if (i == index) activeColor else idleColor
            navIcons[i].setColorFilter(color)
            navLabels[i].setTextColor(color)
        }
        // 日志页常驻自动刷新：切到日志页启动，离开停止
        if (index == 2) startLogAutoRefresh() else stopLogAutoRefresh()
    }

    private fun bindHomeActions() {
        bindHitokoto()
        bindEnergyStats()
        findViewById<View>(R.id.neo_home_ai_btn).setOnClickListener {
            startActivity(Intent(this, NeoAIChatActivity::class.java))
        }
        findViewById<View>(R.id.neo_home_star_btn).setOnClickListener {
            // 通知支付宝进程内的模块重载（与模块版设置页重启同款广播）
            sendBroadcast(Intent("com.eg.android.AlipayGphone.sesame.restart"))
            Toast.makeText(this, "已发送重启支付宝指令", Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.neo_home_more_btn).setOnClickListener { showConfigMenu() }
    }

    /** 三点菜单：默认配置 / 清除配置（拟态卡片弹窗，与首页同套 UI 语言）。 */
    private fun showConfigMenu() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.neo_dialog_config_menu)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnCancelListener { }

        val width = (resources.displayMetrics.widthPixels - (64 * resources.displayMetrics.density).toInt())
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)

        dialog.findViewById<View>(R.id.neo_dialog_default).setOnClickListener {
            dialog.dismiss()
            restoreDefaultConfig()
        }
        dialog.findViewById<View>(R.id.neo_dialog_clear).setOnClickListener {
            dialog.dismiss()
            clearConfig()
        }
        dialog.findViewById<View>(R.id.neo_dialog_cancel).setOnClickListener {
            dialog.dismiss()
        }
        dialog.show()
    }

    /** 恢复默认配置：删除配置与备份后重建出厂默认，并通知支付宝进程重载。 */
    private fun restoreDefaultConfig() {
        try {
            val userId = restoreSelectedAccount()
            val file = if (StringUtil.isEmpty(userId)) FileUtil.getDefaultConfigV2File()
            else FileUtil.getConfigV2File(userId!!)
            if (file.exists()) file.delete()
            ConfigV2.unload()
            ConfigV2.load(userId)
            sendBroadcast(Intent("com.eg.android.AlipayGphone.sesame.restart"))
            Toast.makeText(this, "已恢复默认配置", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "恢复默认配置失败", Toast.LENGTH_SHORT).show()
        }
    }

    /** 清除配置：删除默认配置文件与全部滚动备份，下次加载自动重建默认。 */
    private fun clearConfig() {
        try {
            val file = FileUtil.getDefaultConfigV2File()
            if (file.exists()) file.delete()
            val backupDir = FileUtil.getBackupDirectoryFile()
            backupDir.listFiles()?.forEach { it.delete() }
            ConfigV2.unload()
            sendBroadcast(Intent("com.eg.android.AlipayGphone.sesame.restart"))
            Toast.makeText(this, "已清除配置", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "清除配置失败", Toast.LENGTH_SHORT).show()
        }
    }

    /** 首页能量统计卡：总量/今年/本月/今日 × 收取/帮收/浇水，数据来自 Statistics 快照。 */
    private fun bindEnergyStats() {
        // 独立 App 进程不随支付宝注入，先读磁盘 statistics.json 再填充（模块内由 ApplicationHook 负责 load）
        Statistics.load()
        fun fill(id: Int, tt: Statistics.TimeType, dt: Statistics.DataType) {
            findViewById<TextView>(id).text = String.format("%,d", Statistics.getData(tt, dt))
        }
        fill(R.id.neo_home_energy_total_collect, Statistics.TimeType.ALL, Statistics.DataType.COLLECTED)
        fill(R.id.neo_home_energy_total_help, Statistics.TimeType.ALL, Statistics.DataType.HELPED)
        fill(R.id.neo_home_energy_total_water, Statistics.TimeType.ALL, Statistics.DataType.WATERED)
        fill(R.id.neo_home_energy_year_collect, Statistics.TimeType.YEAR, Statistics.DataType.COLLECTED)
        fill(R.id.neo_home_energy_year_help, Statistics.TimeType.YEAR, Statistics.DataType.HELPED)
        fill(R.id.neo_home_energy_year_water, Statistics.TimeType.YEAR, Statistics.DataType.WATERED)
        fill(R.id.neo_home_energy_month_collect, Statistics.TimeType.MONTH, Statistics.DataType.COLLECTED)
        fill(R.id.neo_home_energy_month_help, Statistics.TimeType.MONTH, Statistics.DataType.HELPED)
        fill(R.id.neo_home_energy_month_water, Statistics.TimeType.MONTH, Statistics.DataType.WATERED)
        fill(R.id.neo_home_energy_today_collect, Statistics.TimeType.DAY, Statistics.DataType.COLLECTED)
        fill(R.id.neo_home_energy_today_help, Statistics.TimeType.DAY, Statistics.DataType.HELPED)
        fill(R.id.neo_home_energy_today_water, Statistics.TimeType.DAY, Statistics.DataType.WATERED)
    }

    /** 注入状态广播：模块被 LSPosed 启用并注入支付宝后标记已激活；update 广播同步刷新统计。 */
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                "com.surexu.sesame.status" -> {
                    // 模块已被 LSPosed 启用并注入支付宝，回包即标记已激活并停止轮询（与模块版一致）
                    ViewAppInfo.setRunTypeByCode(RunType.MODEL.getCode())
                    runTypeProbeTimes = 0
                    runTypeProbeHandler.removeCallbacks(runTypeProbeRunnable)
                    updateEnergyStatus()
                }

                "com.surexu.sesame.update" -> {
                    runOnUiThread { bindEnergyStats() }
                }
            }
        }
    }

    /** 向支付宝进程查询模块注入状态：旧版模块 UI 主页面同款动作 */
    private fun sendRunTypeQueryBroadcast() {
        try {
            sendBroadcast(Intent("com.eg.android.AlipayGphone.sesame.status"))
        } catch (th: Throwable) {
            Log.i("view", "sendBroadcast status err:", th)
        }
    }

    /** 能量统计卡状态条：圆点 + 已激活/已加载/未加载 + 版本号，与模块版 EnergyStatsCard 一致。 */
    private fun updateEnergyStatus() {
        val dot = findViewById<View>(R.id.neo_home_energy_status_dot)
        val text = findViewById<TextView>(R.id.neo_home_energy_status_text)
        val version = findViewById<TextView>(R.id.neo_home_energy_status_version)
        val (color, label) = when (ViewAppInfo.getRunType()) {
            RunType.MODEL -> "#4CAF50" to "已激活"
            RunType.PACKAGE -> "#4CAF50" to "已加载"
            RunType.DISABLE -> "#E0532C" to "未加载"
        }
        dot.backgroundTintList = ColorStateList.valueOf(android.graphics.Color.parseColor(color))
        text.text = label
        val versionName = ViewAppInfo.getAppVersion()
        version.text = if (versionName.isNullOrBlank()) "" else "· v$versionName"
    }

    private val hitokotoList = listOf(
        "种一棵树最好的时间是十年前，其次是现在。",
        "能量不是攒出来的，是坚持出来的。",
        "每天进步一点点，复利终会开花。",
        "蚂蚁虽小，日拱一卒。",
        "早起的鸟儿有虫吃，早起的你有点数。",
        "好事多磨，好能量多攒。",
        "行百里者半九十，坚持就是胜利。",
        "绿意盎然，全靠日常。",
        "流水不争先，争的是滔滔不绝。",
        "积土成山，风雨兴焉；积水成渊，蛟龙生焉。",
    )

    private val hitokotoClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    private fun bindHitokoto() {
        val text = findViewById<TextView>(R.id.neo_home_hitokoto_text)
        showRandomHitokoto(text)
        findViewById<View>(R.id.neo_home_hitokoto_card).setOnClickListener {
            fetchHitokoto(text)
        }
    }

    /** 一言：先展示本地兜底句，同时异步请求网络一言，成功则替换。 */
    private fun fetchHitokoto(text: TextView) {
        val request = Request.Builder().url("https://v1.hitokoto.cn/?encode=json").build()
        hitokotoClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // 网络不可用：保留当前句子
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        if (!it.isSuccessful) return
                        val body = it.body?.string().orEmpty()
                        if (body.isBlank()) return
                        val node = ObjectMapper().readTree(body)
                        val sentence = node.path("hitokoto").asText("")
                        val from = node.path("from").asText("")
                        val fromWho = node.path("from_who").asText("")
                        val suffix = when {
                            from.isNotBlank() && fromWho.isNotBlank() -> "—— $fromWho「$from」"
                            from.isNotBlank() -> "——「$from」"
                            fromWho.isNotBlank() -> "—— $fromWho"
                            else -> ""
                        }
                        if (sentence.isNotBlank()) {
                            runOnUiThread {
                                text.text = if (suffix.isNotBlank()) "$sentence $suffix" else sentence
                            }
                        }
                    }
                } catch (e: Exception) {
                    // 解析失败：保留当前句子
                }
            }
        })
    }

    private fun showRandomHitokoto(text: TextView) {
        text.text = hitokotoList.random()
    }

    private fun bindLogsActions() {
        // 日志分类标签：森林/庄园/其他/记录/错误/调试/运行，按「系统界面-日志展示」勾选过滤
        val tabBar = findViewById<LinearLayout>(R.id.neo_logs_tab_bar)
        val enabledTabs = uiPrefs.getStringSet(NeoSystemActivity.KEY_UI_LOG_TABS, null) ?: logTabs.toSet()
        val visibleTabs = logTabs.filter { enabledTabs.contains(it) }
        if (visibleTabs.isEmpty()) {
            tabBar.visibility = View.GONE
            findViewById<View>(R.id.neo_logs_list).visibility = View.GONE
            findViewById<View>(R.id.neo_logs_empty).visibility = View.VISIBLE
            return
        }
        if (currentLogTag !in visibleTabs) {
            currentLogTag = visibleTabs.first()
        }
        val tabs = mutableListOf<TextView>()
        visibleTabs.forEachIndexed { index, name ->
            val tab = layoutInflater.inflate(R.layout.neo_item_log_tab, tabBar, false) as TextView
            tab.text = name
            tab.isSelected = index == 0
            updateLogTabStyle(tab)
            tab.setOnClickListener {
                haptic(tab)
                tabs.forEach { t ->
                    t.isSelected = false
                    updateLogTabStyle(t)
                }
                tab.isSelected = true
                updateLogTabStyle(tab)
                currentLogTag = name
                lastLogStamp = null
                hideLogSearchBar()
                logEntries = loadLogEntries(logFileFor(name))
                renderLogEntries()
            }
            tabs.add(tab)
            tabBar.addView(tab)
        }

        // 首次进入：加载默认分类（森林）当日日志
        logEntries = loadLogEntries(logFileFor(currentLogTag))
        renderLogEntries()

        // 搜索：显示搜索栏并聚焦输入
        findViewById<View>(R.id.neo_logs_search_btn).setOnClickListener {
            haptic(it)
            showLogSearchBar()
        }
        findViewById<View>(R.id.neo_logs_search_close).setOnClickListener { hideLogSearchBar() }
        findViewById<EditText>(R.id.neo_logs_search_input).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (findViewById<View>(R.id.neo_logs_search_bar).visibility == View.VISIBLE) {
                    renderLogEntries()
                }
            }
        })

        // 刷新：重新读取当前分类日志文件
        findViewById<View>(R.id.neo_logs_refresh_btn).setOnClickListener {
            haptic(it)
            logEntries = loadLogEntries(logFileFor(currentLogTag))
            lastLogStamp = logFileStamp(logFileFor(currentLogTag))
            renderLogEntries()
            Toast.makeText(this, "已刷新", Toast.LENGTH_SHORT).show()
        }

        // 更多：复制全部 / 导出 / 清空 / 滚动到底 / 滚动顶部
        findViewById<View>(R.id.neo_logs_more_btn).setOnClickListener {
            haptic(it)
            showLogMoreDialog()
        }
    }

    /** 日志分类标签选中态：选中为主色软底药丸，未选中为透明底灰色文字。 */
    private fun updateLogTabStyle(tab: TextView) {
        if (tab.isSelected) {
            tab.background = ContextCompat.getDrawable(this, R.drawable.neu_tab_selected)
            tab.setTextColor(ContextCompat.getColor(this, R.color.neo_primary))
        } else {
            tab.background = null
            tab.setTextColor(ContextCompat.getColor(this, R.color.neo_text_hint))
        }
    }

    private val logTabs = listOf("森林", "庄园", "其他", "记录", "错误", "调试", "运行")

    // ==================== 日志页数据对接（读取/解析/渲染，逻辑与旧版模块 UI 日志页对齐） ====================

    private data class NeoLogEntry(val lineNumber: Int, val time: String?, val tag: String?, val body: String)

    private var currentLogTag: String = "森林"
    private var logEntries: List<NeoLogEntry> = emptyList()

    private val logRefreshHandler = Handler(Looper.getMainLooper())
    private var logRefreshRunning = false
    private var lastLogStamp: LogFileStamp? = null
    private val logRefreshRunnable = object : Runnable {
        override fun run() {
            refreshLogsIfChanged()
            if (logRefreshRunning) {
                logRefreshHandler.postDelayed(this, LOG_REFRESH_INTERVAL_MS)
            }
        }
    }

    // ===== 激活探测（与旧版模块 UI 主页面逻辑对齐）=====
    /** 激活探测轮询器：DISABLE 时周期发查询广播，直到收到模块回包或达上限 */
    private val runTypeProbeHandler = Handler(Looper.getMainLooper())
    private var runTypeProbeTimes = 0
    private val runTypeProbeRunnable = object : Runnable {
        override fun run() {
            if (ViewAppInfo.getRunType() == RunType.DISABLE) {
                runTypeProbeTimes++
                updateEnergyStatus()
                if (runTypeProbeTimes < MAX_RUN_TYPE_PROBE_TIMES) {
                    sendRunTypeQueryBroadcast()
                    runTypeProbeHandler.postDelayed(this, RUN_TYPE_PROBE_INTERVAL_MS)
                }
            } else {
                runTypeProbeTimes = 0
            }
        }
    }

    private data class LogFileStamp(val length: Long, val modified: Long)

    /** 分类标签 → 对应日志文件。'记录' 对应金豆记录（模块版 GOLDENBEANS）。 */
    private fun logFileFor(tag: String): File = when (tag) {
        "森林" -> FileUtil.getForestLogFile()
        "庄园" -> FileUtil.getFarmLogFile()
        "其他" -> FileUtil.getOtherLogFile()
        "记录" -> FileUtil.getGoldenBeansLogFile()
        "错误" -> FileUtil.getErrorLogFile()
        "调试" -> FileUtil.getDebugLogFile()
        else -> FileUtil.getRuntimeLogFile()
    }

    private fun logFileStamp(file: File?): LogFileStamp? =
        if (file != null && file.exists()) LogFileStamp(file.length(), file.lastModified()) else null

    /** 读取日志文件并按行解析为条目；无时间戳的行合并到上一条。仅读尾部，限制条目数。 */
    private fun loadLogEntries(file: File?): List<NeoLogEntry> {
        if (file == null || !file.exists()) {
            return emptyList()
        }
        // 两种格式：
        // runtime/system/debug: 13:34:10.251 RUNTIME: 执行结束-庄园
        // forest/farm/goldenbeans/other: 19:37:33.150 森林签到...
        val timeRegex = Regex("^(\\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+(?:(\\w+):\\s*)?(.*)$")
        val entries = ArrayDeque<NeoLogEntry>()
        return try {
            val text = readTailText(file, LOG_MAX_TAIL_BYTES)
            var lineNumber = 0
            for (line in text.lineSequence()) {
                lineNumber++
                val match = timeRegex.find(line)
                if (match != null) {
                    entries.addLast(
                        NeoLogEntry(
                            lineNumber = lineNumber,
                            time = match.groupValues[1],
                            tag = match.groupValues[2].takeIf { it.isNotEmpty() },
                            body = match.groupValues[3]
                        )
                    )
                } else {
                    if (entries.isNotEmpty()) {
                        val last = entries.removeLast()
                        entries.addLast(last.copy(body = last.body + "\n" + line))
                    } else {
                        entries.addLast(NeoLogEntry(lineNumber, null, null, line))
                    }
                }
                while (entries.size > LOG_MAX_ENTRIES) {
                    entries.removeFirst()
                }
            }
            entries.toList()
        } catch (e: Throwable) {
            emptyList()
        }
    }

    /** 从文件尾部读取文本，最多 maxBytes 字节；非从头读取时丢弃首个可能被截断的行。 */
    private fun readTailText(file: File, maxBytes: Long): String {
        val length = file.length()
        if (length <= 0L) {
            return ""
        }
        val start = maxOf(0L, length - maxBytes)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            val bytes = ByteArray((length - start).toInt())
            raf.readFully(bytes)
            var text = String(bytes, Charsets.UTF_8)
            if (start > 0L) {
                val idx = text.indexOf('\n')
                text = if (idx >= 0) text.substring(idx + 1) else ""
            }
            return text
        }
    }

    /** 按当前分类 + 搜索词渲染日志列表；空结果显示空态卡。 */
    private fun renderLogEntries() {
        val list = findViewById<LinearLayout>(R.id.neo_logs_list)
        val empty = findViewById<View>(R.id.neo_logs_empty)
        val query = findViewById<EditText>(R.id.neo_logs_search_input).text.toString().trim()
        val filtered = logEntries.filter { e ->
            query.isEmpty() ||
                (e.tag?.contains(query, ignoreCase = true) == true) ||
                e.body.contains(query, ignoreCase = true)
        }
        list.removeAllViews()
        if (filtered.isEmpty()) {
            empty.visibility = View.VISIBLE
            list.visibility = View.GONE
            return
        }
        empty.visibility = View.GONE
        list.visibility = View.VISIBLE
        filtered.forEach { entry ->
            val card = layoutInflater.inflate(R.layout.neo_item_log_entry, list, false)
            card.findViewById<TextView>(R.id.entry_tag).text = entry.tag ?: "日志"
            card.findViewById<TextView>(R.id.entry_time).text = entry.time ?: ""
            val body = card.findViewById<TextView>(R.id.entry_body)
            if (entry.body.isBlank()) {
                body.visibility = View.GONE
            } else {
                body.text = entry.body
            }
            card.setOnClickListener { copyLogEntry(entry) }
            card.findViewById<View>(R.id.entry_copy).setOnClickListener { copyLogEntry(entry) }
            list.addView(card)
        }
    }

    /** 复制单条日志（时间 + TAG: 正文）。 */
    private fun copyLogEntry(entry: NeoLogEntry) {
        val text = buildString {
            if (!entry.time.isNullOrBlank()) append(entry.time).append(' ')
            if (!entry.tag.isNullOrBlank()) append(entry.tag).append(": ")
            append(entry.body)
        }
        try {
            val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            manager.setPrimaryClip(ClipData.newPlainText(entry.tag ?: "日志", text))
            Toast.makeText(this, "已复制本条日志", Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            // 剪贴板写入失败不影响页面，静默忽略
        }
    }

    /** 日志自动刷新：每秒比较文件签名，有新内容才重新加载（仅日志页可见时运行）。 */
    private fun startLogAutoRefresh() {
        if (logRefreshRunning) return
        logRefreshRunning = true
        logRefreshHandler.post(logRefreshRunnable)
    }

    private fun stopLogAutoRefresh() {
        logRefreshRunning = false
        logRefreshHandler.removeCallbacks(logRefreshRunnable)
    }

    private fun refreshLogsIfChanged() {
        val file = logFileFor(currentLogTag)
        val stamp = logFileStamp(file)
        if (stamp == null || stamp == lastLogStamp) {
            return
        }
        lastLogStamp = stamp
        logEntries = loadLogEntries(file)
        renderLogEntries()
    }

    private fun showLogSearchBar() {
        findViewById<View>(R.id.neo_logs_search_bar).visibility = View.VISIBLE
        val input = findViewById<EditText>(R.id.neo_logs_search_input)
        input.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        imm?.showSoftInput(input, 0)
    }

    private fun hideLogSearchBar() {
        findViewById<View>(R.id.neo_logs_search_bar).visibility = View.GONE
        val input = findViewById<EditText>(R.id.neo_logs_search_input)
        input.setText("")
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        imm?.hideSoftInputFromWindow(input.windowToken, 0)
    }

    /** 更多菜单：复制全部（当前筛选结果） / 导出日志 / 清空日志 / 滚动到底 / 滚动顶部。 */
    private fun showLogMoreDialog() {
        val file = logFileFor(currentLogTag)
        showOptionDialog(
            "日志操作",
            listOf("复制全部", "导出日志", "清空日志", "滚动到底", "滚动顶部")
        ) { which ->
            when (which) {
                0 -> {
                    val query = findViewById<EditText>(R.id.neo_logs_search_input).text.toString().trim()
                    val filtered = logEntries.filter { e ->
                        query.isEmpty() ||
                            (e.tag?.contains(query, ignoreCase = true) == true) ||
                            e.body.contains(query, ignoreCase = true)
                    }
                    val text = filtered.reversed().joinToString("\n") {
                        buildString {
                            if (!it.time.isNullOrBlank()) append(it.time).append(' ')
                            if (!it.tag.isNullOrBlank()) append(it.tag).append(": ")
                            append(it.body)
                        }
                    }
                    if (text.isBlank()) {
                        Toast.makeText(this, "暂无可复制的日志", Toast.LENGTH_SHORT).show()
                    } else {
                        try {
                            val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            manager?.setPrimaryClip(ClipData.newPlainText(currentLogTag, text))
                            Toast.makeText(this, "已复制 ${filtered.size} 条日志", Toast.LENGTH_SHORT).show()
                        } catch (t: Throwable) {
                            Toast.makeText(this, "复制失败", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                1 -> {
                    val exported = FileUtil.exportFile(file)
                    if (exported != null) {
                        Toast.makeText(this, "已导出: " + exported.path, Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "导出失败", Toast.LENGTH_SHORT).show()
                    }
                }
                2 -> {
                    if (FileUtil.clearFile(file)) {
                        lastLogStamp = null
                        logEntries = loadLogEntries(file)
                        renderLogEntries()
                        Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "清空失败", Toast.LENGTH_SHORT).show()
                    }
                }
                3 -> {
                    findViewById<ScrollView>(R.id.neo_logs_scroll).fullScroll(ScrollView.FOCUS_DOWN)
                }
                4 -> {
                    findViewById<ScrollView>(R.id.neo_logs_scroll).fullScroll(ScrollView.FOCUS_UP)
                }
            }
        }
    }

    private fun bindSettingsActions() {
        // 右上角账号按钮：点击进入账号配置页（头像/信息/切换）
        findViewById<View>(R.id.neo_settings_account_btn).setOnClickListener {
            haptic(it)
            startActivity(Intent(this, NeoAccountActivity::class.java))
        }

        val list = findViewById<LinearLayout>(R.id.neo_setting_list)
        val marginPx = (12 * resources.displayMetrics.density).toInt()
        settings.forEach { setting ->
            val card = layoutInflater.inflate(R.layout.neo_item_setting, list, false)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(0, 0, 0, marginPx)
            card.layoutParams = lp

            card.findViewById<ImageView>(R.id.neo_setting_icon).setImageResource(setting.iconRes)
            card.findViewById<TextView>(R.id.neo_setting_name).text = setting.name
            val sub = card.findViewById<TextView>(R.id.neo_setting_sub)
            if (setting.sub != null) {
                sub.text = setting.sub
                sub.visibility = View.VISIBLE
            }
            card.setOnClickListener {
                haptic(card)
                when (setting.name) {
                    "缓存清理" -> {
                        clearAppCache()
                        Toast.makeText(this, "缓存已清理", Toast.LENGTH_SHORT).show()
                    }
                    "备份与恢复" -> showBackupRestoreDialog()
                    "关于" -> startActivity(Intent(this, NeoAboutActivity::class.java))
                    "系统界面" -> startActivity(Intent(this, NeoSystemActivity::class.java))
                    "服务" -> startActivity(Intent(this, NeoServiceActivity::class.java))
                    else -> Toast.makeText(this, "${setting.name}：功能对接中", Toast.LENGTH_SHORT).show()
                }
            }
            list.addView(card)
        }
        updateSystemSettingSub()
    }

    // ==================== 系统界面设置应用（悬浮底栏 / 触感 / 设置页摘要） ====================

    /** 应用「悬浮底栏」：开启时底部导航容器变为圆角凸起胶囊（左右 12dp + 底部 8dp），关闭恢复原样。 */
    private fun applyFloatNav() {
        val root = findViewById<View>(R.id.neo_nav_bar_root) ?: return
        val lp = root.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (uiPrefs.getBoolean(NeoSystemActivity.KEY_UI_FLOAT_NAV, false)) {
            val m = dp(12)
            val mb = dp(8)
            lp.leftMargin = m
            lp.rightMargin = m
            lp.bottomMargin = mb
            root.background = ContextCompat.getDrawable(this, R.drawable.neu_card_raised)
        } else {
            lp.leftMargin = 0
            lp.rightMargin = 0
            lp.bottomMargin = 0
            root.background = null
        }
        root.layoutParams = lp
    }

    /** 触感反馈：开关开启时执行振动。 */
    private fun haptic(view: View) {
        if (uiPrefs.getBoolean(NeoSystemActivity.KEY_UI_HAPTIC, false)) {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    /** 主题是否显式浅色（跟随系统=false 且 深色=false）。 */
    private fun themeIsLight(): Boolean =
        !(AppConfig.INSTANCE.followSystem ?: true) && !(AppConfig.INSTANCE.darkMode ?: false)

    /** 刷新设置页「系统界面」条目副标题：主题 · 缩放 · 悬浮状态。 */
    private fun updateSystemSettingSub() {
        val list = findViewById<LinearLayout>(R.id.neo_setting_list) ?: return
        if (list.childCount == 0) return
        val sub = list.getChildAt(0).findViewById<TextView>(R.id.neo_setting_sub) ?: return
        val theme = when {
            themeIsLight() -> "浅色"
            AppConfig.INSTANCE.darkMode ?: false -> "深色"
            else -> "跟随系统"
        }
        val scale = uiPrefs.getInt(NeoSystemActivity.KEY_UI_SCALE, 100)
        val floatOn = uiPrefs.getBoolean(NeoSystemActivity.KEY_UI_FLOAT_NAV, false)
        sub.text = "$theme · 缩放${scale}%" + if (floatOn) " · 悬浮开" else ""
        sub.visibility = View.VISIBLE
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private val uiPrefs by lazy { getSharedPreferences(PREFS_UI, MODE_PRIVATE) }

    /** 读取上次选中的账号 id(可能已失效,由账号页渲染时对目录校验并回退默认) */
    private fun restoreSelectedAccount(): String? {
        val last = uiPrefs.getString(KEY_LAST_SELECTED_USER, null) ?: return null
        return if (StringUtil.isEmpty(last)) null else last
    }

    /** 刷新设置页右上角账号按钮：默认人像图标 / 圆形头像 / 首字符三态。 */
    private fun refreshSettingsAccountButton() {
        val icon = findViewById<ImageView>(R.id.neo_settings_account_icon)
        val avatar = findViewById<NeoAsyncAvatarView>(R.id.neo_settings_account_avatar)
        val char = findViewById<TextView>(R.id.neo_settings_account_char)
        val userId = restoreSelectedAccount()
        if (StringUtil.isEmpty(userId)) {
            icon.visibility = View.VISIBLE
            avatar.load(null)
            avatar.visibility = View.GONE
            char.visibility = View.GONE
            return
        }
        icon.visibility = View.GONE
        val entity = loadAccountUser(userId!!)
        if (!StringUtil.isEmpty(entity?.avatar)) {
            avatar.load(entity?.avatar)
            avatar.visibility = View.VISIBLE
            char.visibility = View.GONE
        } else {
            avatar.load(null)
            avatar.visibility = View.GONE
            char.text = entity?.showName?.take(1) ?: userId.take(1)
            char.visibility = View.VISIBLE
        }
    }

    /** 读取指定账号 self.json 得到用户实体（与模块版 ConfigTab 同款加载方式）。 */
    private fun loadAccountUser(userId: String?): UserEntity? {
        if (StringUtil.isEmpty(userId)) return null
        UserIdMap.loadSelf(userId!!)
        return UserIdMap.get(userId)
    }

    private data class NeoSetting(val name: String, val iconRes: Int, val sub: String? = null)

    private val settings by lazy {
        listOf(
            NeoSetting("系统界面", R.drawable.ic_neo_sysui),
            NeoSetting("服务", R.drawable.ic_neo_service),
            NeoSetting("备份与恢复", R.drawable.ic_neo_backup),
            NeoSetting("关于", R.drawable.ic_neo_about, "Xu v" + appVersionName()),
            NeoSetting("服务器地址", R.drawable.ic_neo_server),
            NeoSetting("缓存清理", R.drawable.ic_neo_clean, cacheSizeText()),
        )
    }

    private fun appVersionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

    /** 不可清理的保留文件：配置 / token / 账号索引 / 用户数据 / 统计。 */
    private val PROTECTED_CACHE_NAMES = setOf(
        "config_v2.json", "config_v2.prev.json", "token_config.json", "accountIndex.json",
        "self.json", "friend.json", "cooperation.json", "status.json", "runtimeInfo.json",
        "statistics.json"
    )

    /** 收集 Sure-Xu 数据目录下可安全清理的缓存文件（log 目录 + 任务/榜单缓存 json），排除配置与用户数据。 */
    private fun cacheFiles(): List<File> {
        val main = FileUtil.MAIN_DIRECTORY_FILE
        if (!main.exists()) return emptyList()
        val result = mutableListOf<File>()
        main.listFiles()?.forEach { f ->
            when {
                f.isDirectory -> {
                    // log 目录整体可清；config/、bak/ 等保留
                    if (f.name == "log") result.add(f)
                }
                f.isFile && f.name.endsWith(".json") && f.name !in PROTECTED_CACHE_NAMES ->
                    result.add(f)
            }
        }
        // 各用户子目录下的榜单缓存 json（保留 self/friend/cooperation/status/runtimeInfo 等账号数据）
        FileUtil.CONFIG_DIRECTORY_FILE.listFiles()?.forEach { userDir ->
            if (userDir.isDirectory) {
                userDir.listFiles()?.forEach { f ->
                    if (f.isFile && f.name.endsWith(".json") && f.name !in PROTECTED_CACHE_NAMES) {
                        result.add(f)
                    }
                }
            }
        }
        return result
    }

    private fun cacheSizeText(): String {
        val size = cacheFiles().sumOf { if (it.isDirectory) dirSize(it) else it.length() }
        return if (size >= 1024L * 1024L) {
            String.format("%.2fM", size / 1024.0 / 1024.0)
        } else {
            String.format("%.0fK", size / 1024.0)
        }
    }

    private fun dirSize(dir: java.io.File): Long {
        if (!dir.exists() || !dir.isDirectory) return 0L
        var total = 0L
        dir.listFiles()?.forEach { f ->
            total += if (f.isDirectory) dirSize(f) else f.length()
        }
        return total
    }

    private fun clearAppCache() {
        try {
            // 清理 Sure-Xu 数据目录下的可清理缓存（log + 任务/榜单缓存 json），保留配置/token/备份/账号数据
            cacheFiles().forEach { it.deleteRecursively() }
            filesDir.listFiles()?.forEach { it.deleteRecursively() }
            cacheDir.listFiles()?.forEach { it.deleteRecursively() }
            updateCacheSizeSub()
        } catch (e: Exception) {
            // 忽略清理失败
        }
    }

    /** 清理后刷新「缓存清理」行的副标题显示。 */
    private fun updateCacheSizeSub() {
        val list = findViewById<LinearLayout>(R.id.neo_setting_list) ?: return
        for (i in 0 until list.childCount) {
            val card = list.getChildAt(i)
            val name = card.findViewById<TextView>(R.id.neo_setting_name)?.text?.toString()
            if (name == "缓存清理") {
                val sub = card.findViewById<TextView>(R.id.neo_setting_sub)
                sub.text = cacheSizeText()
                sub.visibility = View.VISIBLE
                break
            }
        }
    }

    /** 备份与恢复主弹窗：立即备份 / 从备份恢复 / 从文件导入 / 清除备份。 */
    private fun showBackupRestoreDialog() {
        showOptionDialog(
            "备份与恢复",
            listOf("立即备份", "从备份恢复", "从文件导入", "清除备份")
        ) { index ->
            when (index) {
                0 -> backupNow()
                1 -> showRestoreListDialog()
                2 -> importBackupFromFile()
                3 -> clearBackups()
            }
        }
    }

    /** 立即备份：弹框让用户自定义文件名与保存位置（默认位置备份目录或系统文件选择器）。 */
    private fun backupNow() {
        try {
            val src = FileUtil.getDefaultConfigV2File()
            if (!src.exists()) {
                Toast.makeText(this, "暂无配置文件", Toast.LENGTH_SHORT).show()
                return
            }
            val json = FileUtil.readFromFile(src)
            val defaultName = FileUtil.BACKUP_FILE_PREFIX + "default_" +
                SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date()) + FileUtil.BACKUP_FILE_EXT

            val dialog = Dialog(this)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            dialog.setContentView(R.layout.neo_dialog_field_edit)
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            dialog.setCanceledOnTouchOutside(true)
            dialog.window?.setLayout(
                resources.displayMetrics.widthPixels - (64 * resources.displayMetrics.density).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )

            dialog.findViewById<TextView>(R.id.neo_edit_title).text = "立即备份（可改文件名）"
            dialog.findViewById<TextView>(R.id.neo_edit_ok).visibility = View.GONE
            dialog.findViewById<TextView>(R.id.neo_edit_cancel).setOnClickListener { dialog.dismiss() }

            val content = dialog.findViewById<FrameLayout>(R.id.neo_edit_content)
            val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

            val nameInput = EditText(this).apply {
                setText(defaultName)
                setTextColor(ContextCompat.getColor(this@NeoMainActivity, R.color.neo_text_primary))
                setTextSize(14f)
                background = ContextCompat.getDrawable(this@NeoMainActivity, R.drawable.neu_input_bg)
                setPadding(dp(14), dp(10), dp(14), dp(10))
            }
            inner.addView(
                nameInput,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )

            fun normalizeName(): String {
                var name = nameInput.text.toString().trim()
                if (name.isEmpty()) return ""
                if (!name.endsWith(FileUtil.BACKUP_FILE_EXT)) name += FileUtil.BACKUP_FILE_EXT
                return name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            }

            inner.addView(
                buildBackupActionRow("保存到默认位置", "写入备份目录 ${FileUtil.getBackupDirectoryFile().path}") {
                    val name = normalizeName()
                    if (name.isEmpty()) {
                        Toast.makeText(this, "文件名不能为空", Toast.LENGTH_SHORT).show()
                        return@buildBackupActionRow
                    }
                    val target = File(FileUtil.getBackupDirectoryFile(), name)
                    if (FileUtil.write2File(json, target)) {
                        dialog.dismiss()
                        Toast.makeText(this, "已备份：${target.name}", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "备份失败", Toast.LENGTH_SHORT).show()
                    }
                }
            )
            inner.addView(
                buildBackupActionRow("选择位置保存", "用系统文件选择器自定义目录与文件名") {
                    val name = normalizeName()
                    if (name.isEmpty()) {
                        Toast.makeText(this, "文件名不能为空", Toast.LENGTH_SHORT).show()
                        return@buildBackupActionRow
                    }
                    try {
                        pendingBackupJson = json
                        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "application/json"
                            putExtra(Intent.EXTRA_TITLE, name)
                        }
                        startActivityForResult(intent, REQUEST_EXPORT_BACKUP)
                    } catch (e: Exception) {
                        Toast.makeText(this, "无法打开文件选择器：" + (e.message ?: ""), Toast.LENGTH_SHORT).show()
                    }
                }
            )

            content.addView(
                inner,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
            dialog.show()
        } catch (e: Exception) {
            Toast.makeText(this, "备份失败：" + (e.message ?: ""), Toast.LENGTH_SHORT).show()
        }
    }

    /** 立即备份弹窗内的操作行（拟态样式），点击执行回调。 */
    private fun buildBackupActionRow(name: String, sub: String, onClick: () -> Unit): View {
        val row = TextView(this).apply {
            text = "$name\n$sub"
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@NeoMainActivity, R.color.neo_primary))
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = ContextCompat.getDrawable(this@NeoMainActivity, R.drawable.neu_input_bg)
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, 0, 0, dp(8))
        row.layoutParams = lp
        row.setOnClickListener {
            haptic(row)
            onClick()
        }
        return row
    }

    /** 从备份恢复：列出备份目录全部配置备份，点选即写回默认配置并重载。 */
    private fun showRestoreListDialog() {
        val files = listBackupFiles()
        if (files.isEmpty()) {
            Toast.makeText(this, "暂无备份文件", Toast.LENGTH_SHORT).show()
            return
        }
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        showOptionDialog(
            "从备份恢复（点选立即恢复）",
            files.map { "${it.name}（${fmt.format(Date(it.lastModified()))}）" }
        ) { index ->
            restoreFromBackupFile(files[index])
        }
    }

    private fun restoreFromBackupFile(backup: File) {
        try {
            val json = FileUtil.readFromFile(backup)
            if (json.isNullOrEmpty()) {
                Toast.makeText(this, "备份文件为空", Toast.LENGTH_SHORT).show()
                return
            }
            val userId = restoreSelectedAccount()
            if (StringUtil.isEmpty(userId)) {
                FileUtil.setDefaultConfigV2File(json)
            } else {
                FileUtil.setConfigV2File(userId!!, json)
            }
            ConfigV2.unload()
            ConfigV2.load(userId)
            sendBroadcast(Intent("com.eg.android.AlipayGphone.sesame.restart"))
            Toast.makeText(this, "已从 ${backup.name} 恢复", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "恢复失败：" + (e.message ?: ""), Toast.LENGTH_SHORT).show()
        }
    }

    /** 从文件导入：拉起系统文件选择器选外部备份 json，写回默认配置并重载。 */
    private fun importBackupFromFile() {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "*/*"))
            }
            startActivityForResult(intent, REQUEST_IMPORT_BACKUP)
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开文件选择器：" + (e.message ?: ""), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_EXPORT_BACKUP) {
            if (resultCode == RESULT_OK && data != null && data.data != null) {
                try {
                    val json = pendingBackupJson
                    if (json != null) {
                        contentResolver.openOutputStream(data.data!!)?.use { out ->
                            out.write(json.toByteArray(Charsets.UTF_8))
                        }
                        Toast.makeText(this, "已备份到所选位置", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "备份失败：无待写入内容", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "备份失败：" + (e.message ?: ""), Toast.LENGTH_SHORT).show()
                }
            }
            pendingBackupJson = null
            return
        }
        if (requestCode != REQUEST_IMPORT_BACKUP || resultCode != RESULT_OK || data == null) return
        val uri: Uri? = data.data
        if (uri == null) return
        try {
            val json = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            if (json.isNullOrBlank()) {
                Toast.makeText(this, "文件内容为空", Toast.LENGTH_SHORT).show()
                return
            }
            val userId = restoreSelectedAccount()
            if (StringUtil.isEmpty(userId)) {
                FileUtil.setDefaultConfigV2File(json)
            } else {
                FileUtil.setConfigV2File(userId!!, json)
            }
            ConfigV2.unload()
            ConfigV2.load(userId)
            sendBroadcast(Intent("com.eg.android.AlipayGphone.sesame.restart"))
            Toast.makeText(this, "已从外部文件恢复配置", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "导入失败：" + (e.message ?: ""), Toast.LENGTH_SHORT).show()
        }
    }

    /** 清除备份：删除备份目录下全部配置备份文件（保留 config_v2.prev.json 写盘快照）。 */
    private fun clearBackups() {
        try {
            val files = listBackupFiles()
            var count = 0
            files.forEach { if (it.delete()) count++ }
            Toast.makeText(
                this,
                if (count > 0) "已清除 $count 份备份" else "无备份可清除",
                Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            Toast.makeText(this, "清除失败：" + (e.message ?: ""), Toast.LENGTH_SHORT).show()
        }
    }

    private fun listBackupFiles(): List<File> =
        FileUtil.getBackupDirectoryFile().listFiles { f ->
            f.isFile && f.name.startsWith(FileUtil.BACKUP_FILE_PREFIX) && f.name.endsWith(FileUtil.BACKUP_FILE_EXT)
        }?.sortedByDescending { it.lastModified() } ?: emptyList()

    /** 通用拟态选项弹窗：标题 + 选项列表，点选回调索引后关闭。 */
    private fun showOptionDialog(title: String, options: List<String>, onPick: (Int) -> Unit) {
        if (options.isEmpty()) {
            Toast.makeText(this, "无可用选项", Toast.LENGTH_SHORT).show()
            return
        }
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.neo_dialog_field_edit)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCanceledOnTouchOutside(true)
        val width = resources.displayMetrics.widthPixels - (64 * resources.displayMetrics.density).toInt()
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)

        dialog.findViewById<TextView>(R.id.neo_edit_title).text = title
        dialog.findViewById<TextView>(R.id.neo_edit_ok).visibility = View.GONE
        dialog.findViewById<TextView>(R.id.neo_edit_cancel).setOnClickListener { dialog.dismiss() }

        val content = dialog.findViewById<FrameLayout>(R.id.neo_edit_content)
        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        options.forEachIndexed { index, opt ->
            val optView = TextView(this).apply {
                text = opt
                setTextColor(ContextCompat.getColor(this@NeoMainActivity, R.color.neo_text_primary))
                textSize = 14f
                setPadding(
                    (14 * resources.displayMetrics.density).toInt(),
                    (10 * resources.displayMetrics.density).toInt(),
                    (14 * resources.displayMetrics.density).toInt(),
                    (10 * resources.displayMetrics.density).toInt()
                )
                background = ContextCompat.getDrawable(this@NeoMainActivity, R.drawable.neu_input_bg)
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.setMargins(0, 0, 0, (8 * resources.displayMetrics.density).toInt())
                layoutParams = lp
                setOnClickListener {
                    onPick(index)
                    dialog.dismiss()
                }
            }
            inner.addView(optView)
        }
        content.addView(
            inner,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        dialog.show()
    }

    /** 一级目录功能清单：名称 / 图标资源。逐个对接后把对应卡片 onClick 换成真实跳转。 */
    private data class NeoFeature(val name: String, val iconRes: Int)

    private val featureCards = mutableListOf<View>()

    private val features = listOf(
        NeoFeature("基础", R.drawable.ic_neo_feat_base),
        NeoFeature("森林", R.drawable.ic_neo_feat_forest),
        NeoFeature("庄园", R.drawable.ic_neo_feat_farm),
        NeoFeature("新村", R.drawable.ic_neo_feat_village),
        NeoFeature("运动", R.drawable.ic_neo_feat_sport),
        NeoFeature("其他", R.drawable.ic_neo_feat_other),
    )

    private fun buildFeatureGrid() {
        val list = findViewById<LinearLayout>(R.id.neo_feature_grid)
        val marginPx = (12 * resources.displayMetrics.density).toInt()
        features.forEach { feature ->
            val card = layoutInflater.inflate(R.layout.neo_item_feature, list, false)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(0, 0, 0, marginPx)
            card.layoutParams = lp

            card.findViewById<ImageView>(R.id.neo_item_icon).setImageResource(feature.iconRes)
            card.findViewById<TextView>(R.id.neo_item_name).text = feature.name
            card.setOnClickListener {
                haptic(card)
                when (feature.name) {
                    "基础" -> startActivity(
                        Intent(this, NeoGroupFieldsActivity::class.java).putExtra(
                            NeoGroupFieldsActivity.EXTRA_GROUP_CODE,
                            ModelGroup.BASE.code
                        )
                    )
                    "森林" -> startActivity(
                        Intent(this, NeoGroupFieldsActivity::class.java).putExtra(
                            NeoGroupFieldsActivity.EXTRA_GROUP_CODE,
                            ModelGroup.FOREST.code
                        )
                    )
                    "庄园" -> startActivity(
                        Intent(this, NeoGroupFieldsActivity::class.java).putExtra(
                            NeoGroupFieldsActivity.EXTRA_GROUP_CODE,
                            ModelGroup.FARM.code
                        )
                    )
                    "新村" -> startActivity(
                        Intent(this, NeoGroupFieldsActivity::class.java).putExtra(
                            NeoGroupFieldsActivity.EXTRA_GROUP_CODE,
                            ModelGroup.STALL.code
                        )
                    )
                    "运动" -> startActivity(
                        Intent(this, NeoGroupFieldsActivity::class.java).putExtra(
                            NeoGroupFieldsActivity.EXTRA_GROUP_CODE,
                            ModelGroup.SPORTS.code
                        )
                    )
                    "其他" -> startActivity(
                        Intent(this, NeoGroupFieldsActivity::class.java).putExtra(
                            NeoGroupFieldsActivity.EXTRA_GROUP_CODE,
                            ModelGroup.OTHER.code
                        )
                    )
                    else -> Toast.makeText(this, "${feature.name}：功能对接中，敬请期待", Toast.LENGTH_SHORT).show()
                }
            }
            list.addView(card)
            featureCards.add(card)
        }
        // 功能搜索：按名称实时过滤卡片
        findViewById<EditText>(R.id.neo_feature_search_input).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s.toString().trim()
                featureCards.forEach { card ->
                    val name = card.findViewById<TextView>(R.id.neo_item_name).text.toString()
                    card.visibility =
                        if (query.isEmpty() || name.contains(query, ignoreCase = true)) View.VISIBLE else View.GONE
                }
            }
        })
    }
}
