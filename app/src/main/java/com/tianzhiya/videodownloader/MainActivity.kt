package com.tianzhiya.videodownloader

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var urlInput: EditText
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildPage()
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun buildPage() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(28, 32, 28, 32)
        }

        val title = TextView(this).apply {
            text = "浏览器打开"
            textSize = 26f
            gravity = Gravity.CENTER
        }

        val description = TextView(this).apply {
            text = "粘贴网页或视频链接，点击下方按钮交给手机浏览器打开。"
            textSize = 15f
            setPadding(0, 18, 0, 20)
        }

        urlInput = EditText(this).apply {
            hint = "https://example.com/"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
            minLines = 2
        }

        val openButton = Button(this).apply {
            text = "打开手机浏览器"
            setOnClickListener { openInBrowser() }
        }

        status = TextView(this).apply {
            text = "等待输入链接"
            textSize = 14f
            setPadding(0, 18, 0, 0)
        }

        root.addView(title, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(description, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(urlInput, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(openButton, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(status, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        setContentView(root)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val incoming = when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }
        if (!incoming.isNullOrBlank()) {
            val link = incoming.trim()
            urlInput.setText(link)
            status.text = "已填入分享的链接，点击按钮即可打开浏览器"
        }
    }

    private fun openInBrowser() {
        val address = urlInput.text.toString().trim()
        if (!(address.startsWith("https://", true) || address.startsWith("http://", true))) {
            status.text = "请输入以 http:// 或 https:// 开头的有效链接"
            return
        }

        try {
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(address)).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
            }
            startActivity(Intent.createChooser(browserIntent, "选择手机浏览器"))
            status.text = "已请求使用手机浏览器打开链接"
        } catch (e: Exception) {
            status.text = "打开失败：${e.message ?: "没有可用的浏览器"}"
        }
    }
}
