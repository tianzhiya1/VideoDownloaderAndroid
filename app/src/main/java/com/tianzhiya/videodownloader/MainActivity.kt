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
    private lateinit var browserDownloadServer: BrowserDownloadServer
    private lateinit var urlInput: EditText
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var webView: WebView
    private lateinit var mediaContainer: LinearLayout
    private lateinit var browserDownloadButton: Button
    private var selectedMediaAddress: String? = null
    private val capturedMedia = ConcurrentHashMap<String, Map<String, String>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        browserDownloadServer = BrowserDownloadServer(this)
        browserDownloadServer.start()
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
                    selectedMediaAddress = null
                    mediaContainer.removeAllViews()
                    browserDownloadButton.isEnabled = false
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
        browserDownloadButton = Button(this).apply {
            text = "用浏览器下载视频"
            isEnabled = false
            setOnClickListener {
                val address = selectedMediaAddress
                if (address.isNullOrBlank()) {
                    status.text = "还没有检测到可下载的视频"
                } else {
                    openInBrowserForDownload(address)
                }
            }
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
        root.addView(browserDownloadButton)
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
                        status.text = "没有找到公开媒体地址。动态播放器请使用“应用内打开网页并检测视频”；加密或受保护的流可能无法识别。"
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

    private fun mediaKind(value: String): String {
        val clean = value.substringBefore('?').substringBefore('#').lowercase()
        return when {
            clean.endsWith(".m3u8") -> "HLS"
            clean.endsWith(".mpd") -> "DASH"
            listOf(".mp4", ".m4v", ".mov", ".webm", ".mkv", ".avi", ".flv", ".3gp", ".ts", ".mpeg", ".mpg", ".wmv", ".ogv", ".mts", ".m2ts").any { clean.endsWith(it) } -> "视频文件"
            else -> ""
        }
    }

    private fun isMediaUrl(value: String): Boolean = mediaKind(value).isNotEmpty()

    private fun addMediaCandidate(address: String) {
        val kind = mediaKind(address)
        if (kind.isEmpty()) return

        // Keep one download action for the whole page. Prefer a direct MP4 when
        // available; otherwise keep a supported stream candidate for processing.
        val current = selectedMediaAddress
        val currentKind = current?.let { mediaKind(it) } ?: ""
        val priority = mapOf("视频文件" to 1, "DASH" to 2, "HLS" to 3)
        if (current == null || (priority[kind] ?: 99) < (priority[currentKind] ?: 99)) {
            selectedMediaAddress = address
        }

        if (mediaContainer.findViewWithTag<android.view.View>(address) == null && mediaContainer.childCount < 30) {
            val label = TextView(this).apply {
                text = "检测到 $kind：${address.take(100)}"
                textSize = 12f
            }
            label.tag = address
            mediaContainer.addView(label)
        }

        browserDownloadButton.isEnabled = selectedMediaAddress != null
        browserDownloadButton.text = if (selectedMediaAddress != null) {
            "用浏览器下载视频（已识别 ${capturedMedia.size.coerceAtLeast(mediaContainer.childCount)} 个候选）"
        } else {
            "用浏览器下载视频"
        }
        status.text = "已识别媒体地址。页面只保留一个浏览器下载按钮。"
    }

    private fun openInBrowserForDownload(address: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(address)).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
            }
            val pageUrl = browserDownloadServer.createDownloadPage(address, capturedMedia[address] ?: emptyMap(), CookieManager.getInstance().getCookie(address))
            val pageIntent = Intent(Intent.ACTION_VIEW, Uri.parse(pageUrl)).apply { addCategory(Intent.CATEGORY_BROWSABLE) }
            startActivity(Intent.createChooser(pageIntent, "打开浏览器下载页面"))
            status.text = "已打开浏览器下载页面，请点击页面中的“下载视频”按钮。"
        } catch (e: Exception) {
            status.text = "无法打开浏览器：${e.message ?: "未找到可用浏览器"}"
        }
    }

    private fun findMedia(page: String): String? {
        val html = requestText(page)
        val pattern = Regex("""https?://[^"'\s<>]+?\.(?:mp4|m4v|mov|webm|mkv|avi|flv|3gp|ts|mpeg|mpg|wmv|ogv|mts|m2ts|m3u8|mpd)(?:\?[^"'\s<>]*)?""", RegexOption.IGNORE_CASE)
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
        browserDownloadServer.stop()
        executor.shutdown()
        super.onDestroy()
    }
}


/**
 * Serves a tiny local download page to the phone browser. The browser downloads
 * the media through this loopback endpoint, which adds the captured page headers
 * and cookies and returns Content-Disposition: attachment.
 */
private class BrowserDownloadServer(private val context: Context) {
    private data class Media(val url: String, val headers: Map<String, String>, val cookie: String?)
    private val items = ConcurrentHashMap<String, Media>()
    @Volatile private var running = false
    private var serverSocket: java.net.ServerSocket? = null
    private var worker: Thread? = null
    private var port: Int = 0

    fun start() {
        if (running) return
        try {
            serverSocket = java.net.ServerSocket(0, 10, java.net.InetAddress.getByName("127.0.0.1"))
            port = serverSocket!!.localPort
            running = true
            worker = Thread {
                while (running) {
                    try {
                        val socket = serverSocket?.accept() ?: break
                        Thread { handle(socket) }.start()
                    } catch (_: Exception) {
                        if (running) continue
                    }
                }
            }.apply { name = "browser-download-server"; isDaemon = true; start() }
        } catch (e: Exception) {
            running = false
        }
    }

    fun createDownloadPage(url: String, headers: Map<String, String>, cookie: String?): String {
        check(running && port > 0) { "本地下载页面服务未启动" }
        val token = java.util.UUID.randomUUID().toString().replace("-", "")
        items[token] = Media(url, headers.toMap(), cookie)
        return "http://127.0.0.1:$port/page/$token"
    }

    private fun handle(socket: java.net.Socket) {
        socket.use { client ->
            try {
                client.soTimeout = 30000
                val input = client.getInputStream().bufferedReader(Charsets.US_ASCII)
                val first = input.readLine() ?: return
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isEmpty()) break
                }
                val path = first.split(" ").getOrNull(1) ?: return
                val token = path.substringAfterLast("/")
                val media = items[token]
                if (media == null) {
                    respond(client, "404 Not Found", "text/plain; charset=utf-8", "下载链接已失效，请返回应用重新识别。")
                    return
                }
                if (path.startsWith("/page/")) {
                    val html = """
                        <!doctype html><html lang="zh-CN"><meta charset="utf-8">
                        <meta name="viewport" content="width=device-width,initial-scale=1">
                        <title>视频下载</title>
                        <style>
                        body{font-family:system-ui,-apple-system,sans-serif;margin:0;padding:28px 20px;background:#f5f6f8;color:#202124}
                        main{max-width:520px;margin:8vh auto;background:white;padding:26px;border-radius:18px;box-shadow:0 4px 24px #0001}
                        h2{margin-top:0}p{color:#555;line-height:1.6}
                        a{display:block;text-align:center;text-decoration:none;background:#1677ff;color:white;padding:16px;border-radius:12px;font-size:18px;font-weight:600;margin-top:24px}
                        small{display:block;margin-top:18px;color:#777;line-height:1.5}
                        </style><main><h2>视频已准备好</h2>
                        <p>点击下面的按钮，浏览器将下载视频文件并保存到手机的下载目录。</p>
                        <a href="/download/$token">下载视频</a>
                        <small>如果浏览器询问保存位置，请选择手机上的保存目录。请仅下载你有权保存的内容。</small>
                        </main></html>
                    """.trimIndent()
                    respond(client, "200 OK", "text/html; charset=utf-8", html)
                } else if (path.startsWith("/download/")) {
                    proxyDownload(client, media)
                } else {
                    respond(client, "404 Not Found", "text/plain; charset=utf-8", "Not found")
                }
            } catch (_: Exception) {
                // Browser may close the connection after the download has begun.
            }
        }
    }

    private fun proxyDownload(client: java.net.Socket, media: Media) {
        val connection = (URL(media.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000
            readTimeout = 60000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", media.headers["User-Agent"] ?: "Mozilla/5.0 (Android)")
            media.headers.forEach { (key, value) -> setRequestProperty(key, value) }
            if (!media.cookie.isNullOrBlank()) setRequestProperty("Cookie", media.cookie)
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                respond(client, "$code Upstream Error", "text/plain; charset=utf-8", "视频源返回 HTTP $code。可能需要重新打开网页或登录。")
                return
            }
            if (media.url.substringBefore("?").substringBefore("#").endsWith(".m3u8", true)) {
                connection.disconnect()
                proxyHlsDownload(client, media)
                return
            }
            val pathName = Uri.parse(media.url).lastPathSegment?.substringBefore("?")
                ?.replace(Regex("[^A-Za-z0-9._-]"), "_")?.take(80)
            val filename = if (pathName.isNullOrBlank()) "video.bin" else pathName
            val output = client.getOutputStream()
            val safeName = filename.replace("\"", "")
            val header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/octet-stream\r\n" +
                "Content-Disposition: attachment; filename=\"$safeName\"\r\n" +
                "Cache-Control: no-store\r\n" +
                "Connection: close\r\n\r\n"
            output.write(header.toByteArray(Charsets.US_ASCII))
            connection.inputStream.use { source -> source.copyTo(output, 64 * 1024) }
            output.flush()
        } finally {
            connection.disconnect()
        }
    }

    private fun proxyHlsDownload(client: java.net.Socket, media: Media) {
        fun open(url: URL): HttpURLConnection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000
            readTimeout = 60000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", media.headers["User-Agent"] ?: "Mozilla/5.0 (Android)")
            media.headers.forEach { (key, value) -> setRequestProperty(key, value) }
            if (!media.cookie.isNullOrBlank()) setRequestProperty("Cookie", media.cookie)
        }
        var playlistUrl = URL(media.url)
        var connection = open(playlistUrl)
        try {
            var playlist = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            connection.disconnect()
            if (playlist.contains("#EXT-X-STREAM-INF")) {
                val lines = playlist.lines()
                var bestBandwidth = -1L
                var selected: String? = null
                var pending = 0L
                for (line in lines) {
                    val t = line.trim()
                    if (t.startsWith("#EXT-X-STREAM-INF")) {
                        pending = Regex("""BANDWIDTH=(\d+)""").find(t)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                    } else if (t.isNotEmpty() && !t.startsWith("#")) {
                        if (pending > bestBandwidth) { bestBandwidth = pending; selected = t }
                    }
                }
                playlistUrl = URL(playlistUrl, selected ?: error("HLS 主播放列表没有可用画质"))
                connection = open(playlistUrl)
                playlist = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                connection.disconnect()
            }
            if (playlist.contains("#EXT-X-KEY") && !playlist.contains("#EXT-X-KEY:METHOD=NONE")) {
                respond(client, "415 Unsupported Media Type", "text/plain; charset=utf-8", "该 HLS 流使用加密密钥，本版本不处理加密流。")
                return
            }
            val lines = playlist.lines().map { it.trim() }
            val initLine = lines.firstOrNull { it.startsWith("#EXT-X-MAP:") }
            val initUrl = initLine?.let { Regex("""URI="([^"]+)"""").find(it)?.groupValues?.get(1) }
                ?.let { URL(playlistUrl, it) }
            val segments = lines.filter { it.isNotEmpty() && !it.startsWith("#") }.map { URL(playlistUrl, it) }
            if (segments.isEmpty()) {
                respond(client, "422 Unprocessable Content", "text/plain; charset=utf-8", "HLS 播放列表中没有可下载的视频分片。")
                return
            }
            val output = client.getOutputStream()
            val header = "HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nContent-Disposition: attachment; filename=video.ts\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
            output.write(header.toByteArray(Charsets.US_ASCII))
            if (initUrl != null) open(initUrl).let { c -> c.inputStream.use { it.copyTo(output, 64 * 1024) }; c.disconnect() }
            segments.forEach { segment ->
                val c = open(segment)
                c.inputStream.use { it.copyTo(output, 64 * 1024) }
                c.disconnect()
            }
            output.flush()
        } catch (e: Exception) {
            try { respond(client, "502 Bad Gateway", "text/plain; charset=utf-8", "HLS 下载失败：${e.message ?: "媒体源错误"}") } catch (_: Exception) {}
        }
    }

    private fun respond(socket: java.net.Socket, status: String, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().apply {
            write(header.toByteArray(Charsets.US_ASCII))
            write(bytes)
            flush()
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        items.clear()
    }
}
