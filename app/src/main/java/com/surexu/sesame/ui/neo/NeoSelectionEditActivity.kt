package com.surexu.sesame.ui.neo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.surexu.sesame.R
import com.surexu.sesame.data.ConfigPreload
import com.surexu.sesame.data.ConfigV2
import com.surexu.sesame.data.Model
import com.surexu.sesame.data.ModelField
import com.surexu.sesame.data.ModelFields
import com.surexu.sesame.data.modelFieldExt.SelectAndCountModelField
import com.surexu.sesame.data.modelFieldExt.SelectAndCountOneModelField
import com.surexu.sesame.data.modelFieldExt.SelectModelField
import com.surexu.sesame.data.modelFieldExt.SelectOneModelField
import com.surexu.sesame.entity.AlipayUser
import com.surexu.sesame.entity.IdAndName
import com.surexu.sesame.entity.KVNode
import com.surexu.sesame.entity.MemberBenefit
import com.surexu.sesame.util.Log
import com.surexu.sesame.util.LanguageUtil
import com.surexu.sesame.util.ToastUtil
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 选择编辑页（新 UI）：编辑 SELECT/SELECT_ONE/SELECT_AND_COUNT/SELECT_AND_COUNT_ONE 类型字段。
 */
class NeoSelectionEditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_GROUP_CODE = "groupCode"
        const val EXTRA_FIELD_CODE = "fieldCode"
        const val EXTRA_MODEL_CODE = "modelCode"

        /** 兑换请求：UI 进程 → 支付宝进程（由 AlipayBroadcastReceiver 处理） */
        const val ACTION_MEMBER_EXCHANGE = "com.eg.android.AlipayGphone.sesame.memberExchange"

        /** 兑换结果回传：支付宝进程 → UI 进程 */
        const val ACTION_MEMBER_EXCHANGE_RESULT = "com.surexu.sesame.memberExchangeResult"

        /** 权益商品缩略图静态缓存：跨页面复用，避免重复请求图片 */
        private val benefitCache = ConcurrentHashMap<String, Bitmap>()
    }

    private var groupCode: String? = null
    private var fieldCode: String? = null
    private var modelCode: String? = null

    /** 待回传的兑换请求：requestId → 回调（UI 进程无支付宝宿主环境，统一走广播） */
    private val pendingExchanges = ConcurrentHashMap<String, (String) -> Unit>()
    private val timeoutHandler = Handler(Looper.getMainLooper())

    private val exchangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_MEMBER_EXCHANGE_RESULT) {
                val requestId = intent.getStringExtra("requestId")
                val result = intent.getStringExtra("result")
                Log.i("SelectionEdit", "memberExchange result: requestId=$requestId result=$result")
                if (requestId != null) {
                    pendingExchanges.remove(requestId)?.invoke(result ?: "兑换失败")
                }
            }
        }
    }

    // 字段与选中状态（dirty 表示有未保存改动）
    private var liveField: ModelField<*>? = null
    private var options: List<IdAndName> = emptyList()
    private var single = false
    private var withCount = false
    private var valueRangeMin = 0f
    private var valueRangeMax = 100f

    private val mainHandler = Handler(Looper.getMainLooper())
    private val imageLoadExecutor = Executors.newSingleThreadExecutor()

    private var selectedIds: Set<String> = emptySet()
    private var initialCounts: Map<String, Int> = emptyMap()
    private var sel: MutableSet<String> = linkedSetOf()
    private var counts: MutableMap<String, Int> = linkedMapOf()
    private var dirty = false
    private var currentQuery = ""

    override fun attachBaseContext(newBase: Context) {
        ThemeUtil.applyNightMode()
        super.attachBaseContext(LanguageUtil.setLocal(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.neo_page_selection_edit)
        window.statusBarColor = getColor(R.color.neo_base)
        window.navigationBarColor = getColor(R.color.neo_base)

        // 注册兑换结果回传接收器：支付宝进程与模块 App 是不同 UID，Android 13+ 必须导出
        val exchangeFilter = IntentFilter(ACTION_MEMBER_EXCHANGE_RESULT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(exchangeReceiver, exchangeFilter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(exchangeReceiver, exchangeFilter)
        }

        groupCode = intent.getStringExtra(EXTRA_GROUP_CODE)
        fieldCode = intent.getStringExtra(EXTRA_FIELD_CODE)
        modelCode = intent.getStringExtra(EXTRA_MODEL_CODE)

        // 与字段页一致：本页可能被系统重建/直接拉起，需自行注册模型并预加载账号配置，
        // 否则 ConfigV2.INSTANCE 中取不到字段 → 页面显示「字段不存在」。
        Model.initAllModel()
        ConfigPreload.prepare(currentConfigUserId())

        val fCode = fieldCode
        val mCode = modelCode
        if (fCode != null && mCode != null) {
            @Suppress("UNCHECKED_CAST")
            val field = (ConfigV2.INSTANCE.getModelFields(mCode) as? ModelFields)?.get(fCode) as? ModelField<*>
            if (field != null) {
                initField(field)
            } else {
                Toast.makeText(this, "字段不存在: $fCode", Toast.LENGTH_SHORT).show()
                finish()
                return
            }
        } else {
            Toast.makeText(this, "缺少参数", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        findViewById<TextView>(R.id.neo_sel_title).text = liveField?.name ?: ""
        findViewById<View>(R.id.neo_sel_back).setOnClickListener { saveAndFinish() }

        // 全选/反选/取消：仅多选显示
        val selectAll = findViewById<TextView>(R.id.neo_sel_select_all)
        val invert = findViewById<TextView>(R.id.neo_sel_invert)
        val cancel = findViewById<TextView>(R.id.neo_sel_cancel)
        if (single) {
            selectAll.visibility = View.GONE
            invert.visibility = View.GONE
            cancel.visibility = View.GONE
        } else {
            selectAll.setOnClickListener {
                options.filter { matchesQuery(it, currentQuery.trim()) }
                    .forEach { opt -> sel.add(opt.id) }
                dirty = true
                rebuildRows()
            }
            invert.setOnClickListener {
                val filtered = options.filter { matchesQuery(it, currentQuery.trim()) }
                filtered.forEach { opt ->
                    if (opt.id in sel) sel.remove(opt.id) else sel.add(opt.id)
                }
                dirty = true
                rebuildRows()
            }
            cancel.setOnClickListener {
                // 取消本次操作：恢复进入时的勾选快照，不写盘并退出
                sel.clear()
                sel.addAll(selectedIds)
                counts.clear()
                counts.putAll(selectedIds.associateWith { initialCounts[it] ?: 1 })
                dirty = false
                finish()
            }
        }

        val searchInput = findViewById<EditText>(R.id.neo_sel_search_input)
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                currentQuery = s?.toString() ?: ""
                rebuildRows()
            }
        })
        findViewById<View>(R.id.neo_sel_search_close).setOnClickListener {
            searchInput.text.clear()
            rebuildRows()
        }

        rebuildRows()
    }

    private fun initField(field: ModelField<*>) {
        liveField = field
        single = field.type == "SELECT_ONE" || field.type == "SELECT_AND_COUNT_ONE"
        withCount = field.type == "SELECT_AND_COUNT"

        @Suppress("UNCHECKED_CAST")
        val smf = when (field.type) {
            "SELECT" -> field as? SelectModelField
            "SELECT_ONE" -> field as? SelectOneModelField
            "SELECT_AND_COUNT" -> field as? SelectAndCountModelField
            "SELECT_AND_COUNT_ONE" -> field as? SelectAndCountOneModelField
            else -> null
        }
        @Suppress("UNCHECKED_CAST")
        options = (smf?.expandValue ?: emptyList<Any>()) as List<IdAndName>

        val v = field.value
        selectedIds = when (field.type) {
            "SELECT" -> (v as? Set<*>)?.mapNotNull { it?.toString() }?.toSet() ?: emptySet()
            "SELECT_ONE" -> {
                val sv = v as? String
                if (sv != null && sv.isNotEmpty()) setOf(sv) else emptySet()
            }
            "SELECT_AND_COUNT_ONE" -> {
                val key = (v as? KVNode<*, *>)?.key?.toString()
                if (key != null && key.isNotEmpty()) setOf(key) else emptySet()
            }
            else -> (v as? Map<*, *>)?.keys?.mapNotNull { it?.toString() }?.toSet() ?: emptySet()
        }
        initialCounts = when {
            withCount -> (v as? Map<*, *>)
                ?.mapValues { (_, value) -> (value as? Int) ?: 1 }
                ?.mapKeys { (k, _) -> k as? String ?: "" }
                ?.filterKeys { it in selectedIds } ?: emptyMap()
            field.type == "SELECT_AND_COUNT_ONE" -> {
                val kv = v as? KVNode<*, *>
                val key = kv?.key?.toString()
                val count = (kv?.value as? Int) ?: 1
                if (key != null && key.isNotEmpty()) mapOf(key to count) else emptyMap()
            }
            else -> emptyMap()
        }

        val sacf = field as? SelectAndCountModelField
        valueRangeMin = sacf?.valueRangeMin ?: 0f
        valueRangeMax = sacf?.valueRangeMax ?: 100f

        sel = linkedSetOf<String>().apply { addAll(selectedIds) }
        counts = linkedMapOf<String, Int>().apply {
            putAll(selectedIds.associateWith { initialCounts[it] ?: 1 })
        }

        Log.i("SelectionEdit", "Entry: field=${field.code}, type=${field.type}, value=${field.value}, selectedIds=$selectedIds")
    }

    override fun onBackPressed() {
        saveAndFinish()
    }

    override fun onDestroy() {
        super.onDestroy()
        timeoutHandler.removeCallbacksAndMessages(null)
        try {
            unregisterReceiver(exchangeReceiver)
        } catch (_: Exception) {
        }
        pendingExchanges.clear()
    }

    /** 顶部返回与系统返回统一入口：有改动先保存再退出。 */
    private fun saveAndFinish() {
        if (dirty) {
            applyAndSave()
        }
        finish()
    }

    private fun applyAndSave() {
        val configField = liveField
        if (configField == null) {
            finish()
            return
        }
        when (configField.type) {
            "SELECT" -> configField.setObjectValue(sel)
            "SELECT_ONE" -> configField.setObjectValue(sel.firstOrNull())
            "SELECT_AND_COUNT" -> {
                val csmf = configField as? SelectAndCountModelField
                csmf?.clear()
                sel.forEach { id -> csmf?.add(id, counts[id] ?: 1) }
            }
            "SELECT_AND_COUNT_ONE" -> {
                val csmf = configField as? SelectAndCountOneModelField
                csmf?.clear()
                csmf?.add(sel.firstOrNull() ?: "", counts[sel.firstOrNull()] ?: 1)
            }
        }
        // 跟随账号页选中的账号保存（未选账号时落默认配置，与旧行为一致）
        val saved = ConfigV2.save(currentConfigUserId(), true)
        Log.i("SelectionEdit", "applyAndSave: field=${configField.code}, saved=$saved, value=${configField.value}")
        if (saved) {
            dirty = false
            ToastUtil.show(this, "已保存")
            // 通知支付宝主进程重启模块，让新配置立即生效（与设置页同款）。
            try {
                sendBroadcast(Intent("com.eg.android.AlipayGphone.sesame.restart"))
            } catch (th: Throwable) {
                Log.printStackTrace(th)
            }
        } else {
            ToastUtil.show(this, "保存失败")
        }
    }

    private fun matchesQuery(opt: IdAndName, query: String): Boolean {
        return query.isBlank()
                || opt.name.contains(query, ignoreCase = true)
                || opt.id.contains(query, ignoreCase = true)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 配置读写跟随账号页选中的账号（与主界面恢复逻辑同一存储）；未选账号时回退默认配置。 */
    private fun currentConfigUserId(): String? {
        val last = getSharedPreferences("sesame_ui_state", Context.MODE_PRIVATE)
            .getString("last_selected_user_id", null)
        return if (last.isNullOrEmpty()) null else last
    }

    private fun rebuildRows() {
        val container = findViewById<LinearLayout>(R.id.neo_sel_container)
        container.removeAllViews()
        val query = currentQuery.trim()
        val filtered = options.filter { matchesQuery(it, query) }
        // 选中项自动置顶
        val sorted = filtered.sortedByDescending { it.id in sel }
        sorted.forEach { opt ->
            container.addView(buildOptionRow(opt))
        }
        if (sorted.isEmpty()) {
            val empty = TextView(this).apply {
                text = "无匹配选项"
                setTextColor(ContextCompat.getColor(this@NeoSelectionEditActivity, R.color.neo_text_hint))
                textSize = 14f
                gravity = android.view.Gravity.CENTER
                setPadding(0, dp(24), 0, dp(24))
            }
            container.addView(empty)
        }
    }

    private fun buildOptionRow(opt: IdAndName): View {
        val isChecked = opt.id in sel
        val isBenefit = opt is MemberBenefit

        // 卡片容器（拟态凸起）
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(this@NeoSelectionEditActivity, R.drawable.neu_card_raised)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        val cardLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        cardLp.setMargins(0, 0, 0, dp(12))
        card.layoutParams = cardLp

        // 主行：头像 + 名称 + 勾选标记（+ 兑换按钮）
        val mainRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        card.addView(
            mainRow,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        // 权益商品缩略图（仅 MemberBenefit 有）
        if (isBenefit) {
            val b = opt as MemberBenefit
            val picIv = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(10).toFloat()
                    setColor(ContextCompat.getColor(this@NeoSelectionEditActivity, R.color.neo_text_hint))
                }
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            mainRow.addView(picIv)
            val space0 = View(this)
            space0.layoutParams = LinearLayout.LayoutParams(dp(10), 1)
            mainRow.addView(space0)
            loadBenefitImage(picIv, b.pic)
        }

        // 头像（仅 AlipayUser 有）
        val avatarUrl = (opt as? AlipayUser)?.avatar
        if (!avatarUrl.isNullOrBlank()) {
            val avatar = NeoAsyncAvatarView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
                load(avatarUrl)
            }
            mainRow.addView(avatar)
            val space = View(this)
            space.layoutParams = LinearLayout.LayoutParams(dp(10), 1)
            mainRow.addView(space)
        }

        // 名称（+ 权益价格）
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val textColLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        textCol.layoutParams = textColLp

        val nameView = TextView(this).apply {
            text = opt.name
            setTextColor(ContextCompat.getColor(this@NeoSelectionEditActivity, R.color.neo_text_primary))
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        textCol.addView(nameView)

        if (isBenefit) {
            val b = opt as MemberBenefit
            val point = b.point
            val yuan = b.yuan
            val priceText = when {
                !point.isNullOrEmpty() && !yuan.isNullOrEmpty() -> "${point}积分 + ${yuan}元"
                !point.isNullOrEmpty() -> "${point}积分"
                !yuan.isNullOrEmpty() -> "${yuan}元"
                else -> ""
            }
            if (priceText.isNotEmpty()) {
                val priceView = TextView(this).apply {
                    text = priceText
                    setTextColor(ContextCompat.getColor(this@NeoSelectionEditActivity, R.color.neo_text_hint))
                    textSize = 12f
                }
                textCol.addView(priceView)
            }
        }
        mainRow.addView(textCol)

        // 勾选标记（自绘：多选方块 / 单选圆点）
        val checkBox = TextView(this).apply {
            text = if (isChecked) "✓" else ""
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = android.view.Gravity.CENTER
        }
        val cbSize = dp(24)
        checkBox.layoutParams = LinearLayout.LayoutParams(cbSize, cbSize)
        checkBox.background = if (isChecked) {
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                cornerRadius = if (single) cbSize / 2f else dp(6).toFloat()
                setColor(ContextCompat.getColor(this@NeoSelectionEditActivity, R.color.neo_primary))
            }
        } else {
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                cornerRadius = if (single) cbSize / 2f else dp(6).toFloat()
                setColor(ContextCompat.getColor(this@NeoSelectionEditActivity, R.color.neo_text_hint))
            }
        }
        // 单选未选中时显示圆环：用描边
        if (single && !isChecked) {
            checkBox.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(2), ContextCompat.getColor(this@NeoSelectionEditActivity, R.color.neo_text_hint))
                setColor(Color.TRANSPARENT)
            }
        }
        mainRow.addView(checkBox)

        // 权益兑换按钮
        if (isBenefit) {
            val space2 = View(this)
            space2.layoutParams = LinearLayout.LayoutParams(dp(6), 1)
            mainRow.addView(space2)
            val exchangeBtn = TextView(this).apply {
                text = "兑换"
                setTextColor(ContextCompat.getColor(this@NeoSelectionEditActivity, R.color.neo_primary))
                textSize = 13f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                background = ContextCompat.getDrawable(this@NeoSelectionEditActivity, R.drawable.neu_pill)
                setPadding(dp(10), dp(5), dp(10), dp(5))
                setOnClickListener {
                    exchangeBenefit(opt.name)
                }
            }
            mainRow.addView(exchangeBtn)
        }

        // 主行点击：切换勾选
        mainRow.setOnClickListener {
            if (single) {
                sel.clear()
                sel.add(opt.id)
                dirty = true
                rebuildRows()
            } else if (opt.id in sel) {
                sel.remove(opt.id)
                dirty = true
                rebuildRows()
            } else {
                sel.add(opt.id)
                if (!counts.containsKey(opt.id)) {
                    counts[opt.id] = initialCounts[opt.id] ?: 1
                }
                dirty = true
                rebuildRows()
            }
        }

        // 数量行：仅 SELECT_AND_COUNT 且已勾选时显示
        if (withCount && isChecked) {
            val countRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val countRowLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            countRowLp.setMargins(0, dp(6), 0, 0)
            countRow.layoutParams = countRowLp

            val label = TextView(this).apply {
                text = "数量"
                setTextColor(ContextCompat.getColor(this@NeoSelectionEditActivity, R.color.neo_text_hint))
                textSize = 13f
            }
            countRow.addView(
                label,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )

            val seek = SeekBar(this).apply {
                val min = valueRangeMin.toInt()
                val max = valueRangeMax.toInt()
                if (max > min) {
                    this.max = max - min
                    progress = ((counts[opt.id] ?: 1) - min).coerceIn(0, max - min)
                }
                setPadding(dp(8), 0, dp(8), 0)
                progressTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this@NeoSelectionEditActivity, R.color.neo_primary)
                )
                thumbTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this@NeoSelectionEditActivity, R.color.neo_primary)
                )
            }
            val seekLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            seek.layoutParams = seekLp

            val curCount = TextView(this).apply {
                text = (counts[opt.id] ?: 1).toString()
                setTextColor(ContextCompat.getColor(this@NeoSelectionEditActivity, R.color.neo_primary))
                textSize = 13f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                textAlignment = android.view.View.TEXT_ALIGNMENT_CENTER
            }
            curCount.layoutParams = LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.WRAP_CONTENT)

            val min = valueRangeMin.toInt()
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        curCount.text = (progress + min).toString()
                    }
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}

                override fun onStopTrackingTouch(sb: SeekBar?) {
                    counts[opt.id] = (sb?.progress ?: 0) + min
                    dirty = true
                    curCount.text = (counts[opt.id] ?: 1).toString()
                }
            })

            countRow.addView(seek)
            countRow.addView(curCount)
            card.addView(countRow)
        }

        return card
    }

    /** 轻量网络图片加载（OkHttp + 静态 Bitmap 缓存，与旧 Compose 版 BenefitAsyncImage 对齐） */
    private fun loadBenefitImage(imageView: ImageView, url: String?) {
        val finalUrl = if (url.isNullOrBlank()) null else if (url.startsWith("//")) "https:$url" else url
        if (finalUrl == null) {
            return
        }
        benefitCache[finalUrl]?.let {
            imageView.setImageBitmap(it)
            return
        }
        imageView.tag = finalUrl
        imageLoadExecutor.execute {
            try {
                val req = Request.Builder()
                    .url(finalUrl)
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 12; M2007J3SC Build/SKQ1.211006.001) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/89.0.4389.72 Mobile Safari/537.36 AlipayClient/12.12.12.8000"
                    )
                    .header("Referer", "https://render.alipay.com/")
                    .build()
                OkHttpClient().newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val bytes = resp.body?.bytes()
                        if (bytes != null) {
                            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            if (bmp != null) {
                                benefitCache[finalUrl] = bmp
                                mainHandler.post {
                                    if (imageView.tag == finalUrl) {
                                        imageView.setImageBitmap(bmp)
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
                // 网络失败：保留占位背景，不打扰用户
            }
        }
    }

    /** 兑换单个权益：UI 进程无支付宝宿主环境，发广播给支付宝进程执行，结果回传后提示。 */
    private fun exchangeBenefit(name: String) {
        if (name.isBlank()) {
            ToastUtil.show(this, "兑换失败：权益名为空")
            return
        }
        val requestId = UUID.randomUUID().toString()
        val timeoutRunnable = Runnable {
            pendingExchanges.remove(requestId)
            ToastUtil.show(this, "兑换超时：请确认支付宝已运行且模块已注入")
        }
        pendingExchanges[requestId] = { result ->
            timeoutHandler.removeCallbacks(timeoutRunnable)
            ToastUtil.show(this, result)
        }
        timeoutHandler.postDelayed(timeoutRunnable, 30_000)
        val intent = Intent(ACTION_MEMBER_EXCHANGE)
        intent.putExtra("name", name)
        intent.putExtra("requestId", requestId)
        sendBroadcast(intent)
    }
}
