package com.surexu.sesame.ui.neo

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.surexu.sesame.R
import com.surexu.sesame.entity.UserEntity
import com.surexu.sesame.model.task.friendManage.FriendManage
import com.surexu.sesame.util.LanguageUtil

/**
 * 「服务」→「单删好友」二级页：搜索 + 勾选单向好友 + 删除（删除走广播到支付宝进程 RPC）。
 * 视觉风格与好友统计页一致：拟态卡片列表 + 顶栏全选/反选 + 底部主按钮。
 */
class NeoFriendManageActivity : AppCompatActivity() {

    private lateinit var searchInput: EditText
    private lateinit var container: LinearLayout
    private lateinit var summary: TextView
    private lateinit var deleteBtn: Button

    private val single = ArrayList<UserEntity>()      // 全部单向好友
    private val displayed = ArrayList<UserEntity>()   // 当前过滤后的展示列表
    private val selected = HashSet<String>()          // 已勾选 userId
    private var totalFriends = 0

    override fun attachBaseContext(newBase: Context) {
        ThemeUtil.applyNightMode()
        super.attachBaseContext(LanguageUtil.setLocal(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.neo_page_friend_manage)
        window.statusBarColor = getColor(R.color.neo_base)
        window.navigationBarColor = getColor(R.color.neo_base)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !ThemeUtil.isNightActive(window.decorView.context)
            isAppearanceLightNavigationBars = !ThemeUtil.isNightActive(window.decorView.context)
        }

        findViewById<View>(R.id.neo_fm_back).setOnClickListener { finish() }
        findViewById<View>(R.id.neo_fm_select_all).setOnClickListener {
            selected.addAll(displayed.map { it.userId })
            renderList()
        }
        findViewById<View>(R.id.neo_fm_invert).setOnClickListener {
            val ids = displayed.map { it.userId }
            val wasUnselected = ids.filter { it !in selected }.toSet()
            selected.removeAll(ids.toSet())
            selected.addAll(wasUnselected)
            renderList()
        }
        searchInput = findViewById(R.id.neo_fm_search_input)
        container = findViewById(R.id.neo_fm_list)
        summary = findViewById(R.id.neo_fm_summary)
        deleteBtn = findViewById(R.id.neo_fm_delete_btn)
        deleteBtn.setOnClickListener { onDeleteClick() }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                displayed.clear()
                val kw = s?.toString()?.lowercase() ?: ""
                for (ue in single) {
                    val hay = (ue.showName + " " + (ue.account ?: "") + " " + ue.userId).lowercase()
                    if (kw.isEmpty() || hay.contains(kw)) displayed.add(ue)
                }
                renderList()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        val data = FriendManage.loadSingleDeleted(this)
        if (data == null) {
            finish()
            return
        }
        single.addAll(data.single)
        totalFriends = data.totalFriends
        displayed.addAll(data.single)
        renderList()
        updateSummary()
    }

    private fun renderList() {
        container.removeAllViews()
        if (displayed.isEmpty()) {
            val empty = TextView(this).apply {
                text = if (single.isEmpty()) "没有检测到单向好友" else "没有匹配的好友"
                textSize = 13f
                setTextColor(getColor(R.color.neo_text_hint))
                gravity = android.view.Gravity.CENTER
                setPadding(dp(4), dp(24), dp(4), dp(24))
            }
            container.addView(empty)
            updateDeleteBtn()
            return
        }

        displayed.forEach { ue ->
            val item = layoutInflater.inflate(R.layout.neo_item_friend_manage, container, false)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 0, 0, dp(12))
            item.layoutParams = lp

            item.findViewById<NeoAsyncAvatarView>(R.id.neo_fm_avatar).load(ue.avatar)
            item.findViewById<TextView>(R.id.neo_fm_name).text = ue.showName
            item.findViewById<TextView>(R.id.neo_fm_sub).text =
                if (ue.account.isNullOrEmpty()) ue.userId else ue.account

            val checked = ue.userId in selected
            item.findViewById<View>(R.id.neo_fm_check_circle).background =
                if (checked) getDrawable(R.drawable.neu_check_selected) else getDrawable(R.drawable.neu_check_unselected)
            item.findViewById<TextView>(R.id.neo_fm_check_mark).visibility =
                if (checked) View.VISIBLE else View.INVISIBLE

            item.setOnClickListener {
                if (!selected.add(ue.userId)) selected.remove(ue.userId)
                renderList()
            }
            container.addView(item)
        }
        updateDeleteBtn()
    }

    private fun updateDeleteBtn() {
        val n = displayed.count { it.userId in selected }
        deleteBtn.text = if (n == 0) "删除已选" else "删除已选 ($n)"
        deleteBtn.isEnabled = n > 0
        deleteBtn.alpha = if (n > 0) 1f else 0.5f
    }

    private fun updateSummary() {
        summary.text = "单向好友 ${single.size} 人 / 好友总数 $totalFriends"
    }

    private fun onDeleteClick() {
        val toDelete = displayed.filter { it.userId in selected }
        if (toDelete.isEmpty()) {
            toast("请先勾选要删除的好友")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("确认删除")
            .setMessage("确认删除 ${toDelete.size} 个单向好友? 不可恢复!")
            .setPositiveButton("确认删除") { _, _ ->
                FriendManage.doDeleteNow(this, toDelete)
                // 请求已发出, 从页面移除这些项
                val removedIds = toDelete.map { it.userId }.toSet()
                single.removeAll(toDelete.toSet())
                displayed.removeAll(toDelete.toSet())
                selected.removeAll(removedIds)
                totalFriends = maxOf(0, totalFriends - toDelete.size)
                renderList()
                updateSummary()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
