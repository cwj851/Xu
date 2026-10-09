package com.surexu.sesame.ui.neo

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.surexu.sesame.R
import com.surexu.sesame.data.ViewAppInfo
import com.surexu.sesame.util.LanguageUtil

class NeoAboutActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        ThemeUtil.applyNightMode()
        super.attachBaseContext(LanguageUtil.setLocal(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.neo_page_about)
        window.statusBarColor = getColor(R.color.neo_base)
        window.navigationBarColor = getColor(R.color.neo_base)

        findViewById<View>(R.id.neo_about_back).setOnClickListener { finish() }
        findViewById<View>(R.id.neo_about_link_source).setOnClickListener {
            openWebUrl("https://github.com/yu1989324402/Xu")
        }
        findViewById<View>(R.id.neo_about_link_download).setOnClickListener {
            openWebUrl("https://github.com/yu1989324402/Xu/releases")
        }

        findViewById<android.widget.TextView>(R.id.neo_about_title).text = ViewAppInfo.getAppName()
        findViewById<android.widget.TextView>(R.id.neo_about_version).text =
            "版本 ${ViewAppInfo.getAppVersion()}"
    }

    private fun openWebUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开链接", Toast.LENGTH_SHORT).show()
        }
    }
}
