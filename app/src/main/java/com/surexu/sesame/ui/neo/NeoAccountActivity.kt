package com.surexu.sesame.ui.neo

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.surexu.sesame.R
import com.surexu.sesame.entity.UserEntity
import com.surexu.sesame.util.FileUtil
import com.surexu.sesame.util.LanguageUtil
import com.surexu.sesame.util.StringUtil
import com.surexu.sesame.util.idMap.UserIdMap

/**
 * 账号配置页：展示当前账号头像与信息，列出支付宝共享目录中的全部账号供切换。
 * 选中账号持久化到与模块版同 key 的 SharedPreferences，设置页按钮据此刷新头像/首字符。
 */
class NeoAccountActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_UI = "sesame_ui_state"
        private const val KEY_LAST_SELECTED_USER = "last_selected_user_id"
    }

    private val uiPrefs by lazy { getSharedPreferences(PREFS_UI, MODE_PRIVATE) }
    private var selectedUserId: String? = null

    override fun attachBaseContext(newBase: Context) {
        ThemeUtil.applyNightMode()
        super.attachBaseContext(LanguageUtil.setLocal(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.neo_page_account)
        window.statusBarColor = getColor(R.color.neo_base)
        window.navigationBarColor = getColor(R.color.neo_base)

        selectedUserId = restoreSelectedAccount()
        findViewById<View>(R.id.neo_account_back).setOnClickListener { finish() }
        refreshCurrentCard()
        buildAccountList()
    }

    private fun persistSelectedAccount(userId: String?) {
        uiPrefs.edit().putString(KEY_LAST_SELECTED_USER, userId).apply()
    }

    private fun restoreSelectedAccount(): String? {
        val last = uiPrefs.getString(KEY_LAST_SELECTED_USER, null) ?: return null
        return if (StringUtil.isEmpty(last)) null else last
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 刷新顶部当前账号信息卡。 */
    private fun refreshCurrentCard() {
        val avatar = findViewById<NeoAsyncAvatarView>(R.id.neo_account_avatar)
        val name = findViewById<TextView>(R.id.neo_account_name)
        val account = findViewById<TextView>(R.id.neo_account_account)
        val uid = findViewById<TextView>(R.id.neo_account_uid)

        val userId = selectedUserId
        if (StringUtil.isEmpty(userId)) {
            avatar.load(null)
            name.text = "默认配置"
            account.text = "未选择账号，配置写入公共文件"
            uid.text = null
            return
        }
        val entity = loadUser(userId!!)
        val showName = entity?.showName
        name.text = if (!StringUtil.isEmpty(showName)) showName else "账号 $userId"
        account.text = if (StringUtil.isEmpty(entity?.account)) "userId: $userId" else "账号: ${entity?.account}"
        uid.text = userId
        avatar.load(entity?.avatar)
    }

    /** 遍历支付宝共享配置目录构建账号列表（含默认项）。 */
    private fun buildAccountList() {
        val container = findViewById<LinearLayout>(R.id.neo_account_list)
        container.removeAllViews()

        val accounts = ArrayList<Triple<String?, String, String?>>()
        accounts.add(Triple(null, "默认", null))
        var canReadDir = false
        try {
            val dir = FileUtil.CONFIG_DIRECTORY_FILE
            dir.listFiles()?.forEach { configDir ->
                if (configDir.isDirectory) {
                    canReadDir = true
                    val userId = configDir.name
                    val entity = loadUser(userId)
                    val name = entity?.let { it.showName + ": " + it.account } ?: userId
                    accounts.add(Triple(userId, name, entity?.avatar))
                }
            }
        } catch (_: Exception) {
        }

        if (!canReadDir) {
            val hint = TextView(this).apply {
                text = "未检测到账号目录\n请在系统设置中授予「所有文件访问」权限"
                setTextColor(ContextCompat.getColor(this@NeoAccountActivity, R.color.neo_text_hint))
                textSize = 12f
                setLineSpacing(0f, 1.2f)
                setPadding(dp(4), dp(4), dp(4), dp(12))
            }
            container.addView(hint)
        }

        accounts.forEach { (userId, name, avatar) ->
            container.addView(buildAccountRow(userId, name, avatar))
        }
    }

    private fun buildAccountRow(userId: String?, name: String, avatar: String?): View {
        val row = layoutInflater.inflate(R.layout.neo_item_account, null)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        row.layoutParams = lp

        val icon = row.findViewById<ImageView>(R.id.neo_account_row_icon)
        val avatarView = row.findViewById<NeoAsyncAvatarView>(R.id.neo_account_row_avatar)
        val charView = row.findViewById<TextView>(R.id.neo_account_row_char)
        val nameView = row.findViewById<TextView>(R.id.neo_account_row_name)
        val accountView = row.findViewById<TextView>(R.id.neo_account_row_account)
        val selectedView = row.findViewById<TextView>(R.id.neo_account_row_selected)

        nameView.text = name
        accountView.text = if (userId == null) "不指定账号，配置写入公共文件" else "userId: $userId"

        val isSelected = selectedUserId == userId
        if (isSelected) {
            selectedView.visibility = View.VISIBLE
            nameView.setTextColor(ContextCompat.getColor(this, R.color.neo_primary))
        }

        if (userId == null) {
            avatarView.load(null)
            avatarView.visibility = View.GONE
            charView.visibility = View.GONE
            icon.visibility = View.VISIBLE
        } else {
            icon.visibility = View.GONE
            val u = loadUser(userId)
            if (!StringUtil.isEmpty(u?.avatar)) {
                avatarView.load(u?.avatar)
                avatarView.visibility = View.VISIBLE
                charView.visibility = View.GONE
            } else {
                avatarView.load(null)
                avatarView.visibility = View.GONE
                charView.text = u?.showName?.take(1) ?: userId.take(1)
                charView.visibility = View.VISIBLE
            }
        }

        row.setOnClickListener {
            selectedUserId = userId
            persistSelectedAccount(userId)
            refreshCurrentCard()
            buildAccountList()
            Toast.makeText(
                this,
                if (userId == null) "已切换为默认配置" else "已切换到：$name",
                Toast.LENGTH_SHORT
            ).show()
        }
        return row
    }

    /** 读取指定账号 self.json 得到用户实体（与模块版 ConfigTab 同款加载方式）。 */
    private fun loadUser(userId: String?): UserEntity? {
        if (StringUtil.isEmpty(userId)) return null
        UserIdMap.loadSelf(userId!!)
        return UserIdMap.get(userId)
    }
}
