package com.surexu.sesame.ui.neo

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.surexu.sesame.R
import com.surexu.sesame.data.AppConfig
import com.surexu.sesame.data.ConfigPreload
import com.surexu.sesame.data.ConfigV2
import com.surexu.sesame.data.Model
import com.surexu.sesame.data.ModelConfig
import com.surexu.sesame.data.ModelField
import com.surexu.sesame.data.ModelGroup
import com.surexu.sesame.data.modelFieldExt.ChoiceModelField
import com.surexu.sesame.data.modelFieldExt.EmptyModelField
import com.surexu.sesame.data.modelFieldExt.IntegerModelField
import com.surexu.sesame.ui.neo.NeoSelectionEditActivity
import com.surexu.sesame.util.LanguageUtil

/** 分组配置二级页：拟态风格渲染某 ModelGroup 全部配置项，改动写回 ConfigV2。 */
class NeoGroupFieldsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_GROUP_CODE = "group_code"
    }

    private var group: ModelGroup? = null
    private var modified = false
    private var currentQuery = ""

    override fun attachBaseContext(newBase: Context) {
        ThemeUtil.applyNightMode()
        super.attachBaseContext(LanguageUtil.setLocal(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.neo_page_group_fields)
        window.statusBarColor = getColor(R.color.neo_base)
        window.navigationBarColor = getColor(R.color.neo_base)

        val code = intent.getStringExtra(EXTRA_GROUP_CODE)
        group = ModelGroup.getByCode(code)
        val g = group
        if (g == null) {
            finish()
            return
        }

        Model.initAllModel()
        // 必须先预加载账号配置：否则字段对象是构造默认值，未开开关直接返回时
        // save() 会把默认值当成改动整份覆盖磁盘真实配置，且 UI 显示的开关状态与真实配置不一致
        ConfigPreload.prepare(currentConfigUserId())

        findViewById<TextView>(R.id.neo_group_title).text = g.name
        findViewById<View>(R.id.neo_group_back).setOnClickListener { saveAndFinish() }
        findViewById<View>(R.id.neo_group_execute).setOnClickListener {
            try {
                val i = Intent("com.eg.android.AlipayGphone.sesame.execute")
                i.putExtra("group", g.code)
                sendBroadcast(i)
                Toast.makeText(this, "已发送执行请求：${g.name}", Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                Toast.makeText(this, "执行失败：${t.message}", Toast.LENGTH_SHORT).show()
            }
        }

        val searchInput = findViewById<EditText>(R.id.neo_group_search_input)
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                currentQuery = s?.toString() ?: ""
                rebuildRows()
            }
        })
        findViewById<View>(R.id.neo_group_search_close).setOnClickListener {
            searchInput.text.clear()
            rebuildRows()
        }

        buildRows()
    }

    override fun onBackPressed() {
        saveAndFinish()
    }

    override fun onResume() {
        super.onResume()
        // 从四级页(SELECT/SELECT_AND_COUNT 编辑)返回时父字段可能已变化，
        // 立即重算依赖字段可见性，无需返回重进页面
        rebuildRows()
    }

    /** 保存字段改动并通知支付宝进程重载；无改动直接返回，不保存不广播。 */
    private fun saveAndFinish() {
        try {
            // hasFieldChanges 依赖 ConfigPreload 已加载（valueBaseline 非空），
            // 未加载时恒为 false，因此必须保证 onCreate 里先 prepare 再进本方法。
            if (ConfigV2.hasFieldChanges() && ConfigV2.save(currentConfigUserId(), false)) {
                sendBroadcast(Intent("com.eg.android.AlipayGphone.sesame.restart"))
                modified = false
            }
        } catch (t: Throwable) {
            Toast.makeText(this, "保存失败：${t.message}", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 配置读写跟随账号页选中的账号（与主界面恢复逻辑同一存储）；未选账号时回退默认配置。 */
    private fun currentConfigUserId(): String? {
        val last = getSharedPreferences("sesame_ui_state", Context.MODE_PRIVATE)
            .getString("last_selected_user_id", null)
        return if (last.isNullOrEmpty()) null else last
    }

    /** 重建配置项列表（搜索过滤后用）；保留滚动位置，避免重建后列表跳回顶部。 */
    private fun rebuildRows() {
        // 取消待执行的防抖重建，避免重复重建
        rebuildHandler.removeCallbacks(rebuildRunnable)
        val scroll = findViewById<ScrollView>(R.id.neo_group_scroll)
        val prevY = scroll.scrollY
        findViewById<LinearLayout>(R.id.neo_group_container).removeAllViews()
        buildRows()
        if (prevY > 0) {
            scroll.post { scroll.scrollTo(0, prevY) }
        }
    }

    private val rebuildHandler = Handler(Looper.getMainLooper())
    private val rebuildRunnable = Runnable { rebuildRows() }

    /** 开关点击后的防抖重建：合并快速连点，仅最后一下触发全列表重建。 */
    private fun scheduleRebuild() {
        rebuildHandler.removeCallbacks(rebuildRunnable)
        rebuildHandler.postDelayed(rebuildRunnable, 120L)
    }

    override fun onDestroy() {
        super.onDestroy()
        rebuildHandler.removeCallbacks(rebuildRunnable)
    }

    /** 三级页搜索匹配：字段名、编码或描述包含关键字（忽略大小写），与四级页一致 */
    private fun matchesSearch(query: String, field: ModelField<*>): Boolean {
        return field.name?.contains(query, ignoreCase = true) == true
                || field.code.contains(query, ignoreCase = true)
                || field.description?.contains(query, ignoreCase = true) == true
    }

    private fun buildRows() {
        val g = group ?: return
        val container = findViewById<LinearLayout>(R.id.neo_group_container)
        val marginPx = dp(12)
        val topPx = dp(14)
        val query = currentQuery.trim()

        // 会员组并入"其他"页：会员排第一，动物竞猜等 OTHER 项紧随其后
        // 农场组并入"庄园"页：庄园配置后追加农场配置
        val renderGroups = when (g) {
            ModelGroup.OTHER -> listOf(ModelGroup.MEMBER, ModelGroup.OTHER)
            ModelGroup.FARM -> listOf(ModelGroup.FARM, ModelGroup.ORCHARD)
            else -> listOf(g)
        }
        renderGroups.forEach { rg ->
            Model.getGroupModelConfig(rg).values.forEach { mc ->
            val visibleFields = mc.fields.values.filter {
                it.isVisible(mc) && (query.isBlank() || matchesSearch(query, it))
            }
            if (visibleFields.isEmpty()) {
                return@forEach
            }
            val header = TextView(this).apply {
                text = mc.name
                setTextColor(ContextCompat.getColor(this@NeoGroupFieldsActivity, R.color.neo_primary))
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, topPx, 0, dp(8))
            }
            container.addView(header)

            visibleFields.forEach { field ->
                container.addView(buildFieldRow(mc, field, marginPx, rg))
                if (rg == ModelGroup.BASE && field.code == "debugMode") {
                    container.addView(
                        buildAppConfigRow(
                            "开启状态栏禁删",
                            marginPx,
                            getter = { AppConfig.INSTANCE.enableOnGoing ?: false },
                            setter = { v -> AppConfig.INSTANCE.enableOnGoing = v }
                        )
                    )
                    container.addView(
                        buildAppConfigRow(
                            "屏蔽部分弹窗",
                            marginPx,
                            getter = { AppConfig.INSTANCE.closeCaptchaDialog ?: true },
                            setter = { v -> AppConfig.INSTANCE.closeCaptchaDialog = v }
                        )
                    )
                    container.addView(
                        buildAppConfigRow(
                            "气泡提示",
                            marginPx,
                            getter = { AppConfig.INSTANCE.showToast ?: true },
                            setter = { v -> AppConfig.INSTANCE.showToast = v }
                        )
                    )
                    container.addView(buildToastOffsetRow(marginPx))
                }
            }
            }
        }
    }

    /** 构建字段条目卡片；返回条目 View，供点击后刷新自身状态。 */
    private fun buildFieldRow(mc: ModelConfig, field: ModelField<*>, marginPx: Int, ownerGroup: ModelGroup): View {
        val row = layoutInflater.inflate(R.layout.neo_item_field, null)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(0, 0, 0, marginPx)
        row.layoutParams = lp

        val nameView = row.findViewById<TextView>(R.id.neo_field_name)
        val summaryView = row.findViewById<TextView>(R.id.neo_field_summary)
        val statusView = row.findViewById<TextView>(R.id.neo_field_status)
        val arrowView = row.findViewById<TextView>(R.id.neo_field_arrow)

        nameView.text = field.name
        summaryView.text = field.description

        when (field.type) {
            "BOOLEAN" -> {
                arrowView.visibility = View.GONE
                statusView.visibility = View.VISIBLE
                updateBooleanStatus(statusView, field.value as? Boolean ?: false)
                row.setOnClickListener {
                    val next = !(field.value as? Boolean ?: false)
                    field.setObjectValue(next)
                    updateBooleanStatus(statusView, next)
                    modified = true
                    // 该开关可能是其他字段的父依赖：延迟合并重建列表让依赖字段即时显示/隐藏，
                    // 快速连点只重建一次，避免全列表反复 inflate（卡顿根因之一）
                    scheduleRebuild()
                }
            }

            "INTEGER", "MULTIPLY_INTEGER" -> {
                arrowView.visibility = View.VISIBLE
                statusView.visibility = View.GONE
                val imf = field as? IntegerModelField
                val min = imf?.minLimit
                val max = imf?.maxLimit
                val hint = when {
                    min == null && max == null -> ""
                    min != null && min < 0 -> "（-1 表示按最大额度）"
                    min != null && max != null -> "（${min}~${max}）"
                    max != null -> "（上限 ${max}）"
                    else -> "（下限 ${min}）"
                }
                summaryView.text = field.configValue + hint
                row.setOnClickListener {
                    showEditDialog(
                        title = field.name ?: "",
                        initial = field.configValue ?: "",
                        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED,
                        multiLine = false,
                    ) { input ->
                        val filtered = input.filterIndexed { index, c -> c.isDigit() || (c == '-' && index == 0) }
                        val parsed = filtered.toIntOrNull()
                        val belowMin = min != null && (parsed == null || parsed < min)
                        val aboveMax = max != null && (parsed == null || parsed > max)
                        if (parsed == null || belowMin || aboveMax) {
                            Toast.makeText(this, "请输入合法数值", Toast.LENGTH_SHORT).show()
                            return@showEditDialog
                        }
                        field.setConfigValue(filtered)
                        summaryView.text = field.configValue + hint
                        modified = true
                    }
                }
            }

            "STRING", "TEXT" -> {
                arrowView.visibility = View.VISIBLE
                statusView.visibility = View.GONE
                summaryView.text = field.configValue
                row.setOnClickListener {
                    showEditDialog(
                        title = field.name ?: "",
                        initial = field.configValue ?: "",
                        inputType = InputType.TYPE_CLASS_TEXT,
                        multiLine = field.type == "TEXT",
                    ) { input ->
                        field.setConfigValue(input)
                        summaryView.text = field.configValue
                        modified = true
                    }
                }
            }

            "LIST" -> {
                arrowView.visibility = View.VISIBLE
                statusView.visibility = View.GONE
                val list = (field.value as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
                summaryView.text = list.joinToString(",")
                row.setOnClickListener {
                    showEditDialog(
                        title = field.name ?: "",
                        initial = list.joinToString("\n"),
                        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
                        multiLine = true,
                    ) { input ->
                        val newList = input.lines().map { it.trim() }.filter { it.isNotEmpty() }
                        field.setObjectValue(newList)
                        summaryView.text = newList.joinToString(",")
                        modified = true
                    }
                }
            }

            "CHOICE" -> {
                arrowView.visibility = View.VISIBLE
                statusView.visibility = View.GONE
                val cmf = field as? ChoiceModelField
                val options = cmf?.expandKey ?: emptyArray()
                val current = field.value as? Int ?: 0
                summaryView.text = options.getOrNull(current) ?: "未选择"
                row.setOnClickListener {
                    showChoiceDialog(field.name ?: "", options, current) { index ->
                        field.setObjectValue(index)
                        summaryView.text = options.getOrNull(index) ?: "未选择"
                        modified = true
                    }
                }
            }

            "READ_TEXT", "URL_TEXT" -> {
                arrowView.visibility = View.GONE
                statusView.visibility = View.GONE
                summaryView.text = field.configValue
                row.setOnClickListener { Toast.makeText(this, "只读配置", Toast.LENGTH_SHORT).show() }
            }

            "SELECT", "SELECT_ONE", "SELECT_AND_COUNT", "SELECT_AND_COUNT_ONE" -> {
                arrowView.visibility = View.VISIBLE
                statusView.visibility = View.GONE
                summaryView.text = "点击编辑"
                row.setOnClickListener {
                    startActivity(
                        Intent(this, NeoSelectionEditActivity::class.java).apply {
                            putExtra(NeoSelectionEditActivity.EXTRA_GROUP_CODE, ownerGroup.code)
                            putExtra(NeoSelectionEditActivity.EXTRA_FIELD_CODE, field.code)
                            putExtra(NeoSelectionEditActivity.EXTRA_MODEL_CODE, mc.code)
                        }
                    )
                }
            }

            "EMPTY" -> {
                arrowView.visibility = View.VISIBLE
                statusView.visibility = View.GONE
                summaryView.text = null
                row.setOnClickListener {
                    (field as? EmptyModelField)?.clickRunner?.run()
                }
            }

            else -> {
                arrowView.visibility = View.GONE
                statusView.visibility = View.GONE
                row.setOnClickListener { Toast.makeText(this, "暂不支持该类型", Toast.LENGTH_SHORT).show() }
            }
        }
        return row
    }

    /** 布尔开关胶囊：开=主色软底青字，关=灰凹陷。 */
    private fun updateBooleanStatus(status: TextView, on: Boolean) {
        if (on) {
            status.text = "开"
            status.background = ContextCompat.getDrawable(this, R.drawable.neu_pill_on)
            status.setTextColor(ContextCompat.getColor(this, R.color.neo_primary))
        } else {
            status.text = "关"
            status.background = ContextCompat.getDrawable(this, R.drawable.neu_pill)
            status.setTextColor(ContextCompat.getColor(this, R.color.neo_text_hint))
        }
    }

    /** AppConfig 全局布尔开关行：即时写 AppConfig 并落盘 + 广播重载。 */
    private fun buildAppConfigRow(title: String, marginPx: Int, getter: () -> Boolean, setter: (Boolean) -> Unit): View {
        val row = layoutInflater.inflate(R.layout.neo_item_field, null)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(0, 0, 0, marginPx)
        row.layoutParams = lp

        val nameView = row.findViewById<TextView>(R.id.neo_field_name)
        val summaryView = row.findViewById<TextView>(R.id.neo_field_summary)
        val statusView = row.findViewById<TextView>(R.id.neo_field_status)
        val arrowView = row.findViewById<TextView>(R.id.neo_field_arrow)

        nameView.text = title
        summaryView.text = null
        arrowView.visibility = View.GONE
        statusView.visibility = View.VISIBLE
        updateBooleanStatus(statusView, getter())
        row.setOnClickListener {
            val next = !getter()
            setter(next)
            AppConfig.saveAsync()
            sendBroadcast(Intent("com.eg.android.AlipayGphone.sesame.reloadConfig"))
            updateBooleanStatus(statusView, next)
        }
        return row
    }

    /** AppConfig 气泡纵向偏移：数值输入行。 */
    private fun buildToastOffsetRow(marginPx: Int): View {
        val row = layoutInflater.inflate(R.layout.neo_item_field, null)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(0, 0, 0, marginPx)
        row.layoutParams = lp

        val nameView = row.findViewById<TextView>(R.id.neo_field_name)
        val summaryView = row.findViewById<TextView>(R.id.neo_field_summary)
        val statusView = row.findViewById<TextView>(R.id.neo_field_status)
        val arrowView = row.findViewById<TextView>(R.id.neo_field_arrow)

        nameView.text = "气泡纵向偏移"
        arrowView.visibility = View.VISIBLE
        statusView.visibility = View.GONE
        fun refresh() {
            summaryView.text = (AppConfig.INSTANCE.toastOffsetY ?: 0).toString() + " px（正数向下）"
        }
        refresh()
        row.setOnClickListener {
            showEditDialog(
                title = "气泡纵向偏移",
                initial = (AppConfig.INSTANCE.toastOffsetY ?: 0).toString(),
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED,
                multiLine = false,
            ) { input ->
                val filtered = input.filterIndexed { index, c -> c.isDigit() || (c == '-' && index == 0) }
                val parsed = filtered.toIntOrNull()
                if (parsed == null) {
                    Toast.makeText(this, "请输入合法数值", Toast.LENGTH_SHORT).show()
                    return@showEditDialog
                }
                AppConfig.INSTANCE.toastOffsetY = parsed
                AppConfig.saveAsync()
                sendBroadcast(Intent("com.eg.android.AlipayGphone.sesame.reloadConfig"))
                refresh()
            }
        }
        return row
    }

    /** 通用文本编辑弹窗：title + EditText + 取消/确定。 */
    private fun showEditDialog(
        title: String,
        initial: String,
        inputType: Int,
        multiLine: Boolean,
        onOk: (String) -> Unit,
    ) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.neo_dialog_field_edit)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCanceledOnTouchOutside(true)
        val width = resources.displayMetrics.widthPixels - dp(64)
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)

        dialog.findViewById<TextView>(R.id.neo_edit_title).text = title
        val edit = EditText(this).apply {
            setText(initial)
            setTextColor(ContextCompat.getColor(this@NeoGroupFieldsActivity, R.color.neo_text_primary))
            setTextSize(14f)
            setBackgroundResource(R.drawable.neu_input_bg)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            this.inputType = inputType
            isSingleLine = !multiLine
            if (multiLine) {
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
                minLines = 3
                maxLines = 8
            }
        }
        dialog.findViewById<FrameLayout>(R.id.neo_edit_content).addView(
            edit,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        dialog.findViewById<TextView>(R.id.neo_edit_cancel).setOnClickListener { dialog.dismiss() }
        dialog.findViewById<TextView>(R.id.neo_edit_ok).setOnClickListener {
            onOk(edit.text?.toString() ?: "")
            dialog.dismiss()
        }
        dialog.show()
    }

    /** 选项单选弹窗：title + 选项列表，点选即生效。 */
    private fun showChoiceDialog(title: String, options: Array<String>, current: Int, onPick: (Int) -> Unit) {
        if (options.isEmpty()) {
            Toast.makeText(this, "无可用选项", Toast.LENGTH_SHORT).show()
            return
        }
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.neo_dialog_field_edit)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCanceledOnTouchOutside(true)
        val width = resources.displayMetrics.widthPixels - dp(64)
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)

        dialog.findViewById<TextView>(R.id.neo_edit_title).text = title
        dialog.findViewById<TextView>(R.id.neo_edit_ok).visibility = View.GONE
        dialog.findViewById<TextView>(R.id.neo_edit_cancel).setOnClickListener { dialog.dismiss() }

        val content = dialog.findViewById<FrameLayout>(R.id.neo_edit_content)
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        options.forEachIndexed { index, opt ->
            val optView = TextView(this).apply {
                text = opt
                setTextColor(
                    ContextCompat.getColor(
                        this@NeoGroupFieldsActivity,
                        if (index == current) R.color.neo_primary else R.color.neo_text_primary
                    )
                )
                textSize = 15f
                setTypeface(typeface, if (index == current) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                setPadding(dp(6), dp(10), dp(6), dp(10))
                background = ContextCompat.getDrawable(this@NeoGroupFieldsActivity, R.drawable.neu_input_bg)
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.setMargins(0, 0, 0, dp(8))
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
}
