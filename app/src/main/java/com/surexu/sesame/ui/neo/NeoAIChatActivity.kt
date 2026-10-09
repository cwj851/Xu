package com.surexu.sesame.ui.neo

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.surexu.sesame.R
import com.surexu.sesame.data.ConfigPreload
import com.surexu.sesame.data.ConfigV2
import com.surexu.sesame.data.Model
import com.surexu.sesame.data.ModelField
import com.surexu.sesame.data.modelFieldExt.IntegerModelField
import com.surexu.sesame.model.normal.answerAI.AnswerAI
import com.surexu.sesame.util.LanguageUtil
import com.surexu.sesame.util.Statistics
import com.surexu.sesame.util.ToastUtil

/**
 * AI 聊天页（拟态风格壳）：顶栏 + 消息气泡 + 输入发送。
 * 发送后调用「功能-其他-AI答」里配置的自定义 AI 接口；未配置时给出配置引导。
 */
class NeoAIChatActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        ThemeUtil.applyNightMode()
        super.attachBaseContext(LanguageUtil.setLocal(newBase))
    }

    private lateinit var msgList: LinearLayout
    private lateinit var scrollView: ScrollView
    private val handler = Handler(Looper.getMainLooper())

    /** 配置读写跟随账号页选中的账号（与主界面恢复逻辑同一存储）；未选账号时回退默认配置。 */
    private fun currentConfigUserId(): String? {
        val last = getSharedPreferences("sesame_ui_state", Context.MODE_PRIVATE)
            .getString("last_selected_user_id", null)
        return if (last.isNullOrEmpty()) null else last
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.neo_page_ai_chat)
        window.statusBarColor = getColor(R.color.neo_base)
        window.navigationBarColor = getColor(R.color.neo_base)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !ThemeUtil.isNightActive(window.decorView.context)
            isAppearanceLightNavigationBars = !ThemeUtil.isNightActive(window.decorView.context)
        }

        msgList = findViewById(R.id.neo_ai_msg_list)
        scrollView = findViewById(R.id.neo_ai_scroll)
        findViewById<View>(R.id.neo_ai_back).setOnClickListener { finish() }

        appendMessage("你好，我是 AI 助手，随时为你解答问题。", false)

        findViewById<View>(R.id.neo_ai_config).setOnClickListener { showConfigDialog() }
        findViewById<View>(R.id.neo_ai_quick_stat).setOnClickListener { summarizeEnergy() }
        findViewById<View>(R.id.neo_ai_quick_test).setOnClickListener { testConnection() }

        findViewById<View>(R.id.neo_ai_send).setOnClickListener {
            val input = findViewById<EditText>(R.id.neo_ai_input)
            val text = input.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener
            appendMessage(text, true)
            input.text.clear()
            // 控制类指令（如"开启森林收能量"）直接操作配置开关，不走 AI
            val commandResult = AnswerAI.executeCommand(text)
            if (commandResult != null) {
                appendMessage(commandResult, false)
                return@setOnClickListener
            }
            if (AnswerAI.buildCustomAI() == null) {
                appendMessage("AI 尚未配置：请前往「功能 → 其他 → AI答」填写接口地址、模型名与令牌后使用。", false)
                return@setOnClickListener
            }
            val thinking = appendMessage("思考中…", false)
            Thread {
                val reply = AnswerAI.ask(text)
                handler.post {
                    thinking.text = if (reply.isBlank()) "AI 请求失败，请检查接口配置或网络（详见日志）。" else reply
                    msgList.post { scrollView.fullScroll(View.FOCUS_DOWN) }
                }
            }.start()
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun scrollToBottom() {
        msgList.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    private fun toast(text: String) {
        ToastUtil.show(applicationContext, text)
    }

    private fun showConfigDialog() {
        // 先预加载配置：否则 model.fields 是构造默认值，弹窗里直接改 + ConfigV2.save
        // 会把默认值整份覆盖磁盘真实配置（与功能页同源缺陷）
        ConfigPreload.prepare(currentConfigUserId())
        val model = Model.getModel(AnswerAI::class.java) ?: return
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.neo_dialog_field_edit)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.setLayout(resources.displayMetrics.widthPixels - dp(64), ViewGroup.LayoutParams.WRAP_CONTENT)

        dialog.findViewById<TextView>(R.id.neo_edit_title).text = "AI 配置"
        val content = dialog.findViewById<FrameLayout>(R.id.neo_edit_content)
        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var modified = false

        val codes = listOf("customAIUrl", "customAIModel", "customAIKey", "customAIMaxTokens")
        codes.forEach { code ->
            val field = model.fields[code] ?: return@forEach
            val row = layoutInflater.inflate(R.layout.neo_item_field, inner, false)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 0, 0, dp(10))
            row.layoutParams = lp
            val nameView = row.findViewById<TextView>(R.id.neo_field_name)
            val summaryView = row.findViewById<TextView>(R.id.neo_field_summary)
            row.findViewById<TextView>(R.id.neo_field_status).visibility = View.GONE
            row.findViewById<TextView>(R.id.neo_field_arrow).visibility = View.VISIBLE
            nameView.text = field.name
            summaryView.text = field.configValue
            val isNumber = field is IntegerModelField
            val imf = field as? IntegerModelField
            val min = imf?.minLimit
            val max = imf?.maxLimit
            row.setOnClickListener {
                showFieldEditDialog(
                    title = field.name ?: "",
                    initial = field.configValue ?: "",
                    isNumber = isNumber,
                ) { input ->
                    val filtered = if (isNumber) input.filterIndexed { index, c -> c.isDigit() || (c == '-' && index == 0) } else input
                    val parsed = filtered.toIntOrNull()
                    val belowMin = min != null && (parsed == null || parsed < min)
                    val aboveMax = max != null && (parsed == null || parsed > max)
                    if (isNumber && (parsed == null || belowMin || aboveMax)) {
                        toast("请输入合法数值")
                        return@showFieldEditDialog
                    }
                    field.setConfigValue(filtered)
                    summaryView.text = field.configValue
                    modified = true
                }
            }
            inner.addView(row)
        }
        content.addView(inner, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        dialog.findViewById<TextView>(R.id.neo_edit_cancel).setOnClickListener { dialog.dismiss() }
        dialog.findViewById<TextView>(R.id.neo_edit_ok).setOnClickListener {
            if (modified) {
                ConfigV2.save(currentConfigUserId(), false)
                toast("已保存，聊天立即生效")
            }
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun showFieldEditDialog(title: String, initial: String, isNumber: Boolean, onOk: (String) -> Unit) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.neo_dialog_field_edit)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.setLayout(resources.displayMetrics.widthPixels - dp(64), ViewGroup.LayoutParams.WRAP_CONTENT)

        dialog.findViewById<TextView>(R.id.neo_edit_title).text = title
        val edit = EditText(this).apply {
            setText(initial)
            setTextColor(ContextCompat.getColor(this@NeoAIChatActivity, R.color.neo_text_primary))
            setTextSize(14f)
            setBackgroundResource(R.drawable.neu_input_bg)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            this.inputType = if (isNumber) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED else InputType.TYPE_CLASS_TEXT
            isSingleLine = true
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

    private fun summarizeEnergy() {
        appendMessage("总结今日能量", true)
        val thinking = appendMessage("统计中…", false)
        Thread {
            Statistics.load()
            val sb = StringBuilder()
            sb.append("今日  收: ").append(Statistics.getData(Statistics.TimeType.DAY, Statistics.DataType.COLLECTED))
                .append(" 帮: ").append(Statistics.getData(Statistics.TimeType.DAY, Statistics.DataType.HELPED))
                .append(" 浇: ").append(Statistics.getData(Statistics.TimeType.DAY, Statistics.DataType.WATERED))
            sb.append("\n本月  收: ").append(Statistics.getData(Statistics.TimeType.MONTH, Statistics.DataType.COLLECTED))
                .append(" 帮: ").append(Statistics.getData(Statistics.TimeType.MONTH, Statistics.DataType.HELPED))
                .append(" 浇: ").append(Statistics.getData(Statistics.TimeType.MONTH, Statistics.DataType.WATERED))
            sb.append("\n被水次数  日: ").append(Statistics.getData(Statistics.TimeType.DAY, Statistics.DataType.WATEREDCOUNT))
                .append(" 月: ").append(Statistics.getData(Statistics.TimeType.MONTH, Statistics.DataType.WATEREDCOUNT))
            sb.append("\n浇水次数  日: ").append(Statistics.getData(Statistics.TimeType.DAY, Statistics.DataType.WATERINGCOUNT))
                .append(" 月: ").append(Statistics.getData(Statistics.TimeType.MONTH, Statistics.DataType.WATERINGCOUNT))
            val stats = sb.toString()
            val ai = AnswerAI.buildCustomAI()
            val result: String
            if (ai == null) {
                result = stats
            } else {
                val reply = ai.getAnswerStr("以下是我在蚂蚁森林的今日/本月能量数据，请用中文总结今日收能量情况，并给一句简短点评：\n" + stats)
                result = if (reply.isBlank()) stats else reply
            }
            handler.post {
                thinking.text = result
                scrollToBottom()
            }
        }.start()
    }

    private fun testConnection() {
        appendMessage("连接测试", true)
        val thinking = appendMessage("测试中…", false)
        Thread {
            val ai = AnswerAI.buildCustomAI()
            val result: String
            if (ai == null) {
                result = "AI 尚未配置：请点右上角「配置」填写接口地址、模型名与令牌。"
            } else {
                val reply = ai.getAnswerStr("这是一次接口连通性测试。请只回复 OK。")
                result = if (reply.isBlank()) "连接失败：地址/模型/令牌有误或请求超时（详见日志）。" else "连接成功：" + reply.trim().take(120)
            }
            handler.post {
                thinking.text = result
                scrollToBottom()
            }
        }.start()
    }

    private fun appendMessage(text: String, fromMe: Boolean): TextView {
        val item = layoutInflater.inflate(R.layout.neo_item_ai_msg, msgList, false)
        val bubble = item.findViewById<TextView>(R.id.neo_ai_msg_bubble)
        bubble.text = text
        bubble.background = ContextCompat.getDrawable(
            this, if (fromMe) R.drawable.neo_bubble_me else R.drawable.neo_bubble_ai
        )
        bubble.setTextColor(
            ContextCompat.getColor(this, if (fromMe) android.R.color.white else R.color.neo_text_primary)
        )
        (item as LinearLayout).gravity = if (fromMe) Gravity.END else Gravity.START
        msgList.addView(item)
        msgList.post { scrollView.fullScroll(View.FOCUS_DOWN) }
        return bubble
    }
}
