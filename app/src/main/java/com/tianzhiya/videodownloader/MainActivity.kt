package com.tianzhiya.videodownloader

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.ConcurrentHashMap
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var urlInput: EditText
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var webView: WebView
    private lateinit var mediaContainer: LinearLayout
    private val capturedMedia = ConcurrentHashMap<String, Map<String, String>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 32, 24, 24)
            gravity = Gravity.TOP
        }
        val heading = TextView(this).apply {
            text = "视频下载器"
            textSize = 26f
        }
        val intro = TextView(this).apply {
            text = "粘贴网页链接，或从浏览器使用“分享”发送到本应用。仅支持页面公开暴露且你有权下载的媒体文件。"
            textSize = 14f
            setPadding(0, 12, 0, 20)
        }
        urlInput = EditText(this).apply {
            hint = "https://example.com/video-page"
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            minLines = 2
        }
        val start = Button(this).apply {
            text = "识别网页源码中的直链"
            setOnClickListener { startDetection(urlInput.text.toString().trim()) }
        }
        val openBrowser = Button(this).apply {
            text = "应用内打开网页并检测视频"
            setOnClickListener {
                val page = urlInput.text.toString().trim()
                if (page.startsWith("https://") || page.startsWith("http://")) {
                    capturedMedia.clear()
                    mediaContainer.removeAllViews()
                    webView.visibility = android.view.View.VISIBLE
                    status.text = "正在应用内加载网页，检测公开 MP4/M3U8 请求…"
                    webView.loadUrl(page)
                } else {
                    status.text = "请先输入有效的网页链接"
                }
            }
        }
        webView = WebView(this).apply {
            visibility = android.view.View.GONE
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    runOnUiThread { status.text = "网页已打开，正在监听公开媒体请求…" }
                }

                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): android.webkit.WebResourceResponse? {
                    val address = request?.url?.toString() ?: return null
                    if (isMediaUrl(address)) {
                        val headers = mutableMapOf<String, String>()
                        request.requestHeaders["Referer"]?.let { headers["Referer"] = it }
                        request.requestHeaders["User-Agent"]?.let { headers["User-Agent"] = it }
                        capturedMedia[address] = headers
                        runOnUiThread { addMediaCandidate(address) }
                    }
                    return null
                }
            }
        }
        mediaContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 8)
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = android.view.View.GONE
        }
        status = TextView(this).apply {
            text = "等待链接"
            textSize = 14f
            setPadding(0, 18, 0, 0)
        }
        root.addView(heading)
        root.addView(intro)
        root.addView(urlInput, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(start)
        root.addView(openBrowser)
        root.addView(webView, ViewGroup.LayoutParams.MATCH_PARENT, 620)
        root.addView(mediaContainer)
        root.addView(progress, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(status)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val shared = when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }
        if (!shared.isNullOrBlank()) urlInput.setText(shared.trim())
    }

    private fun startDetection(page: String) {
        if (page.isBlank() || !(page.startsWith("http://") || page.startsWith("https://"))) {
            status.text = "请输入有效的 http/https 链接"
            return
        }
        progress.visibility = android.view.View.VISIBLE
        status.text = "正在分析页面…"
        executor.execute {
            try {
                val media = if (isMediaUrl(page)) page else findMedia(page)
                runOnUiThread {
                    progress.visibility = android.view.View.GONE
                    if (media == null) {
                        status.text = "没有找到可直接访问的 MP4 或 M3U8。动态播放器请使用下面的“应用内打开网页并检测视频”。"
                    } else {
                        status.text = "找到媒体直链：$media"
                        addMediaCandidate(media)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progress.visibility = android.view.View.GONE
                    status.text = "识别失败：${e.message ?: "网络或页面错误"}"
                }
            }
        }
    }

    private fun isMediaUrl(value: String): Boolean {
        val clean = value.substringBefore('?').substringBefore('#')
        return clean.endsWith(".mp4", true) || clean.endsWith(".m3u8", true)
    }

    private fun addMediaCandidate(address: String) {
        if (mediaContainer.childCount > 30 || mediaContainer.findViewWithTag<android.view.View>(address) != null) return
        val isHls = address.substringBefore('?').substringBefore('#').endsWith(".m3u8", true)
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 6, 0, 6)
        }
        val label = TextView(this).apply {
            text = if (isHls) "检测到 HLS 播放列表：${address.take(100)}" else "检测到 MP4 视频：${address.take(100)}"
            textSize = 12f
        }
        item.tag = address
        item.addView(label)
        val headers = capturedMedia[address] ?: emptyMap()
        if (!isHls) {
            item.addView(Button(this).apply {
                text = "交给手机浏览器下载 MP4"
                setOnClickListener { openInBrowserForDownload(address) }
            })
        }
        item.addView(Button(this).apply {
            text = if (isHls) "在应用内下载 HLS" else "使用应用内下载 MP4"
            setOnClickListener {
                status.text = "准备应用内下载…"
                if (isHls) downloadHls(address, headers) else downloadMp4(address, headers)
            }
        })
        mediaContainer.addView(item)
        status.text = "已检测到 ${mediaContainer.childCount} 个媒体地址，请选择下载方式"
    }

    private fun openInBrowserForDownload(address: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(address)).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
            }
            startActivity(Intent.createChooser(intent, "选择浏览器下载视频"))
            status.text = "已交给浏览器打开。若浏览器直接播放而不是下载，请长按视频或使用浏览器的下载功能。"
        } catch (e: Exception) {
            status.text = "无法打开浏览器：${e.message ?: "未找到可用浏览器"}"
        }
    }

    private fun findMedia(page: String): String? {
        val html = requestText(page)
        val pattern = Regex("""https?://[^"'\s<>]+?\.(?:mp4|m3u8)(?:\?[^"'\s<>]*)?""", RegexOption.IGNORE_CASE)
        return pattern.findAll(html)
            .map { it.value.replace("&amp;", "&") }
            .firstOrNull()
    }

    private fun requestText(address: String, headers: Map<String, String> = emptyMap()): String {
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 20000
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) VideoDownloader/1.0")
        headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
        CookieManager.getInstance().getCookie(address)?.let { connection.setRequestProperty("Cookie", it) }
        return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            .also { connection.disconnect() }
    }

    private fun downloadMp4(address: String, headers: Map<String, String> = emptyMap()) {
        try {
            val name = "video_${System.currentTimeMillis()}.mp4"
            val request = DownloadManager.Request(Uri.parse(address))
                .setTitle(name)
                .setDescription("视频下载")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
            headers.forEach { (key, value) -> request.addRequestHeader(key, value) }
            CookieManager.getInstance().getCookie(address)?.let { request.addRequestHeader("Cookie", it) }
            val id = (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            progress.visibility = android.view.View.GONE
            status.text = "已加入系统下载队列（任务编号：$id），可在通知栏查看进度。"
        } catch (e: Exception) {
            progress.visibility = android.view.View.GONE
            status.text = "无法开始下载：${e.message}"
        }
    }

    private fun downloadHls(address: String, headers: Map<String, String> = emptyMap()) {
        executor.execute {
            try {
                var playlistUrl = URL(address)
                var playlist = requestText(playlistUrl.toString(), headers)
                if (playlist.contains("#EXT-X-STREAM-INF")) {
                    val lines = playlist.lines()
                    val variants = mutableListOf<Pair<Long, String>>()
                    var bandwidth = 0L
                    for (line in lines) {
                        val trimmed = line.trim()
                        if (trimmed.startsWith("#EXT-X-STREAM-INF")) {
                            bandwidth = Regex("""BANDWIDTH=(\d+)""").find(trimmed)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                        } else if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                            variants += bandwidth to URL(playlistUrl, trimmed).toString()
                            bandwidth = 0L
                        }
                    }
                    playlistUrl = URL(variants.maxByOrNull { it.first }?.second
                        ?: error("主播放列表中没有可用画质"))
                    playlist = requestText(playlistUrl.toString(), headers)
                }
                val keyLine = playlist.lines().firstOrNull { it.trim().startsWith("#EXT-X-KEY") }
                if (keyLine != null && !keyLine.contains("METHOD=NONE")) {
                    error("检测到加密 HLS，本版本不处理加密流")
                }
                val segments = playlist.lines().map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .map { URL(playlistUrl, it) }
                if (segments.isEmpty()) error("播放列表没有媒体分片")
                val folder = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
                val output = File(folder, "video_${System.currentTimeMillis()}.ts")
                FileOutputStream(output).use { out ->
                    segments.forEachIndexed { index, segment ->
                        val connection = segment.openConnection() as HttpURLConnection
                        connection.connectTimeout = 15000
                        connection.readTimeout = 30000
                        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) VideoDownloader/1.0")
                        headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
                        CookieManager.getInstance().getCookie(segment.toString())?.let { connection.setRequestProperty("Cookie", it) }
                        BufferedInputStream(connection.inputStream).use { input -> input.copyTo(out) }
                        connection.disconnect()
                        runOnUiThread { status.text = "正在下载 HLS 分片：${index + 1}/${segments.size}" }
                    }
                }
                runOnUiThread {
                    progress.visibility = android.view.View.GONE
                    status.text = "HLS 下载完成：${output.absolutePath}"
                    Toast.makeText(this, "下载完成", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progress.visibility = android.view.View.GONE
                    status.text = "HLS 下载失败：${e.message ?: "未知错误"}"
                }
            }
        }
    }

    override fun onDestroy() {
        webView.destroy()
        executor.shutdown()
        super.onDestroy()
    }
}
