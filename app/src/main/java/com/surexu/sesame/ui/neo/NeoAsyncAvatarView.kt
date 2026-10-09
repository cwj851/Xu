package com.surexu.sesame.ui.neo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 原生拟态圆形异步头像控件（View 版异步头像）。
 *
 * 用法：布局中直接引用本控件，调用 [load] 传入头像 URL 即自动加载并圆形裁剪；
 * 未加载完成或 URL 为空时保留 XML 中设置的 src（人像占位图标）。
 * 加载逻辑与模块版一致：OkHttp + 支付宝 UA/Referer + 静态 Bitmap 内存缓存。
 */
class NeoAsyncAvatarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val handler = Handler(Looper.getMainLooper())
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var roundBitmap: Bitmap? = null
    private var targetUrl: String? = null

    /** 加载头像 URL；空值清空当前头像并回落 src 占位图。 */
    fun load(url: String?) {
        val finalUrl = if (url.isNullOrBlank()) null else if (url.startsWith("//")) "https:$url" else url
        targetUrl = finalUrl
        if (finalUrl == null) {
            roundBitmap = null
            invalidate()
            return
        }
        avatarCache[finalUrl]?.let {
            roundBitmap = it
            invalidate()
            return
        }
        roundBitmap = null
        invalidate()
        fetchThreadExecutor.execute {
            try {
                val bmp = fetchBitmap(finalUrl)
                if (bmp != null && targetUrl == finalUrl) {
                    handler.post {
                        if (targetUrl == finalUrl) {
                            roundBitmap = bmp
                            invalidate()
                        }
                    }
                }
            } catch (_: Throwable) {
                // 网络失败：保留占位图，不打扰用户
            }
        }
    }

    private fun fetchBitmap(url: String): Bitmap? {
        val req = Request.Builder()
            .url(url)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 12; M2007J3SC Build/SKQ1.211006.001) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/89.0.4389.72 Mobile Safari/537.36 AlipayClient/12.12.12.8000"
            )
            .header("Referer", "https://render.alipay.com/")
            .build()
        okHttpClient.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) {
                resp.body?.bytes()?.let {
                    BitmapFactory.decodeByteArray(it, 0, it.size)?.also { bmp ->
                        avatarCache[url] = bmp
                        return bmp
                    }
                }
            }
        }
        return null
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = roundBitmap
        if (bmp != null) {
            val radius = minOf(width, height) / 2f
            paint.shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            canvas.drawCircle(width / 2f, height / 2f, radius, paint)
            paint.shader = null
            return
        }
        super.onDraw(canvas)
    }

    companion object {
        /** 与旧版模块 UI 同款静态缓存：跨页面复用，避免重复请求头像。 */
        private val avatarCache = HashMap<String, Bitmap>()

        private val okHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build()
        }

        private val fetchThreadExecutor by lazy { Executors.newSingleThreadExecutor() }
    }
}
