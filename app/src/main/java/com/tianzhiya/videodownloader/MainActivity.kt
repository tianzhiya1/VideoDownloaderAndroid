package com.tianzhiya.videodownloader

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.BufferedOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var downloadServer: Mp4BrowserServer
    private lateinit var urlInput: EditText
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var webView: WebView
    private lateinit var mediaContainer: LinearLayout
    private lateinit var downloadButton: Button
    private var selectedMediaAddress: String? = null
    private val capturedMedia = ConcurrentHashMap<String, Map<String, String>>()
    private var transformer: Transformer? = null
    private var converting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        downloadServer = Mp4BrowserServer()
        downloadServer.start()
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
        root.addView(TextView(this).apply {
            text = "视频下载器"
            textSize = 26f
        })
        root.addView(TextView(this).apply {
            text = "粘贴网页链接，或从浏览器分享网页到本应用。识别到媒体后，应用会将可解码的视频转换为 MP4，再交给手机浏览器下载。"
            textSize = 14f
            setPadding(0, 12, 0, 20)
        })
        urlInput = EditText(this).apply {
            hint = "https://example.com/video-page"
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            minLines = 2
        }
        root.addView(urlInput, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(Button(this).apply {
            text = "识别网页中的视频"
            setOnClickListener { startDetection(urlInput.text.toString().trim()) }
        })
        root.addView(Button(this).apply {
            text = "应用内打开网页并检测"
            setOnClickListener {
                val page = urlInput.text.toString().trim()
                if (!isHttpUrl(page)) {
                    status.text = "请先输入有效的 http/https 网页链接"
                    return@setOnClickListener
                }
                resetCandidates()
                webView.visibility = View.VISIBLE
                status.text = "正在加载网页并监听媒体请求…"
                webView.loadUrl(page)
            }
        })
        webView = WebView(this).apply {
            visibility = View.GONE
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    runOnUiThread { status.text = "网页已打开，正在监听媒体请求…" }
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
        root.addView(webView, ViewGroup.LayoutParams.MATCH_PARENT, 620)
        mediaContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 8)
        }
        root.addView(mediaContainer)
        downloadButton = Button(this).apply {
            text = "转换为 MP4 并用浏览器下载"
            isEnabled = false
            setOnClickListener { convertThenOpenBrowser() }
        }
        root.addView(downloadButton)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        root.addView(progress, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        status = TextView(this).apply {
            text = "等待链接"
            textSize = 14f
            setPadding(0, 18, 0, 0)
        }
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

    private fun isHttpUrl(value: String): Boolean =
        value.startsWith("https://", true) || value.startsWith("http://", true)

    private fun resetCandidates() {
        capturedMedia.clear()
        selectedMediaAddress = null
        mediaContainer.removeAllViews()
        downloadButton.isEnabled = false
        downloadButton.text = "转换为 MP4 并用浏览器下载"
    }

    private fun startDetection(page: String) {
        if (!isHttpUrl(page)) {
            status.text = "请输入有效的 http/https 链接"
            return
        }
        resetCandidates()
        progress.visibility = View.VISIBLE
        status.text = "正在分析网页…"
        executor.execute {
            try {
                val media = if (isMediaUrl(page)) page else findMedia(page)
                runOnUiThread {
                    progress.visibility = View.GONE
                    if (media == null) {
                        status.text = "没有找到公开媒体直链。动态播放器请使用“应用内打开网页并检测”；页面未公开媒体地址时可能无法识别。"
                    } else {
                        addMediaCandidate(media)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progress.visibility = View.GONE
                    status.text = "识别失败：${e.message ?: "网络或页面错误"}"
                }
            }
        }
    }

    private fun mediaKind(value: String): String {
        val clean = value.substringBefore('?').substringBefore('#').lowercase()
        return when {
            clean.endsWith(".m3u8") -> "HLS / M3U8"
            clean.endsWith(".mpd") -> "DASH / MPD"
            listOf(".mp4", ".m4v", ".mov", ".webm", ".mkv", ".avi", ".flv", ".3gp", ".ts", ".mpeg", ".mpg", ".wmv", ".ogv", ".mts", ".m2ts")
                .any { clean.endsWith(it) } -> "视频文件"
            else -> ""
        }
    }

    private fun isMediaUrl(value: String): Boolean = mediaKind(value).isNotEmpty()

    private fun candidatePriority(value: String): Int {
        val clean = value.substringBefore('?').substringBefore('#').lowercase()
        return when {
            clean.endsWith(".mp4") -> 0
            clean.endsWith(".m3u8") -> 1
            clean.endsWith(".mpd") -> 2
            else -> 3
        }
    }

    private fun addMediaCandidate(address: String) {
        val kind = mediaKind(address)
        if (kind.isEmpty()) return
        val current = selectedMediaAddress
        if (current == null || candidatePriority(address) < candidatePriority(current)) {
            selectedMediaAddress = address
        }
        if (mediaContainer.findViewWithTag<View>(address) == null && mediaContainer.childCount < 30) {
            mediaContainer.addView(TextView(this).apply {
                text = "检测到 $kind：${address.take(120)}"
                textSize = 12f
                tag = address
            })
        }
        downloadButton.isEnabled = !converting && selectedMediaAddress != null
        downloadButton.text = "转换为 MP4 并用浏览器下载（已识别 ${mediaContainer.childCount} 个）"
        status.text = "已选中优先级最高的视频源。点击唯一的下载按钮即可转换并交给浏览器。"
    }

    private fun convertThenOpenBrowser() {
        val address = selectedMediaAddress
        if (address.isNullOrBlank() || converting) return
        converting = true
        downloadButton.isEnabled = false
        progress.visibility = View.VISIBLE
        status.text = "正在将媒体转换为 MP4。视频较长时需要等待，请保持应用在后台运行…"

        val folder = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
        if (!folder.exists()) folder.mkdirs()
        val output = File(folder, "video_${System.currentTimeMillis()}.mp4")
        val mediaItem = MediaItem.fromUri(Uri.parse(address))
        val listener = object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                converting = false
                progress.visibility = View.GONE
                if (!output.exists() || output.length() <= 0L) {
                    downloadButton.isEnabled = selectedMediaAddress != null
                    status.text = "转换结束但没有生成有效 MP4 文件，请更换视频源重试。"
                    return
                }
                try {
                    val browserUrl = downloadServer.register(output)
                    status.text = "MP4 已生成（${output.length() / 1024 / 1024} MB），正在交给浏览器下载…"
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(browserUrl)).addCategory(Intent.CATEGORY_BROWSABLE)
                    startActivity(intent)
                } catch (e: Exception) {
                    status.text = "MP4 已生成，但无法打开浏览器：${e.message}"
                    downloadButton.isEnabled = selectedMediaAddress != null
                }
            }

            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                converting = false
                progress.visibility = View.GONE
                downloadButton.isEnabled = selectedMediaAddress != null
                status.text = "转换失败：${exportException.message ?: "该视频封装或编码不受当前设备支持，或媒体源需要登录/请求头"}"
            }
        }

        try {
            transformer = Transformer.Builder(this)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(listener)
                .build()
            transformer?.start(EditedMediaItem.Builder(mediaItem).build(), output.absolutePath)
        } catch (e: Exception) {
            converting = false
            progress.visibility = View.GONE
            downloadButton.isEnabled = selectedMediaAddress != null
            status.text = "无法启动 MP4 转换：${e.message}"
        }
    }

    private fun findMedia(page: String): String? {
        val html = requestText(page)
        val pattern = Regex(
            """https?://[^"'\\s<>]+?\\.(?:mp4|m4v|mov|webm|mkv|avi|flv|3gp|ts|mpeg|mpg|wmv|ogv|mts|m2ts|m3u8|mpd)(?:\\?[^"'\\s<>]*)?""",
            RegexOption.IGNORE_CASE
        )
        return pattern.findAll(html).map { it.value.replace("&amp;", "&") }
            .sortedBy { candidatePriority(it) }.firstOrNull()
    }

    private fun requestText(address: String): String {
        val connection = URL(address).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15000
            connection.readTimeout = 20000
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) VideoDownloader/1.1")
            CookieManager.getInstance().getCookie(address)?.let { connection.setRequestProperty("Cookie", it) }
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    override fun onDestroy() {
        webView.destroy()
        // Do not stop the loopback server here: the external browser may still be
        // downloading the generated MP4 while this Activity is in the background.
        executor.shutdown()
        super.onDestroy()
    }
}

/**
 * Serves the completed MP4 directly to the external browser. There is no HTML
 * interstitial page: the URL itself responds with video/mp4 and attachment headers.
 */
private class Mp4BrowserServer {
    private val files = ConcurrentHashMap<String, File>()
    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    private var port = 0

    fun start() {
        if (running) return
        try {
            serverSocket = ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"))
            port = serverSocket!!.localPort
            running = true
            Thread({
                while (running) {
                    try {
                        val socket = serverSocket?.accept() ?: break
                        Thread({ handle(socket) }, "mp4-browser-request").apply { isDaemon = true; start() }
                    } catch (_: Exception) {
                        if (!running) break
                    }
                }
            }, "mp4-browser-server").apply { isDaemon = true; start() }
        } catch (e: Exception) {
            running = false
            throw IllegalStateException("无法启动本地 MP4 下载服务：${e.message}", e)
        }
    }

    fun register(file: File): String {
        check(running && port > 0) { "本地下载服务未启动" }
        val token = UUID.randomUUID().toString().replace("-", "")
        files[token] = file
        return "http://127.0.0.1:$port/mp4/$token"
    }

    private fun handle(socket: java.net.Socket) {
        socket.use { client ->
            try {
                client.soTimeout = 30000
                val reader = client.getInputStream().bufferedReader(Charsets.US_ASCII)
                val firstLine = reader.readLine() ?: return
                val parts = firstLine.split(" ")
                val method = parts.getOrNull(0) ?: "GET"
                val path = parts.getOrNull(1) ?: "/"
                var rangeHeader: String? = null
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Range:", true)) rangeHeader = line.substringAfter(":").trim()
                }
                val token = path.substringAfterLast("/")
                val file = files[token]
                if (file == null || !file.exists()) {
                    respond(client, "404 Not Found", "text/plain; charset=utf-8", "MP4 下载地址已失效，请返回应用重新转换。")
                    return
                }
                val total = file.length()
                var start = 0L
                var end = total - 1
                var status = "200 OK"
                if (!rangeHeader.isNullOrBlank()) {
                    val match = Regex("bytes=(\\d*)-(\\d*)").find(rangeHeader!!)
                    if (match != null) {
                        start = match.groupValues[1].toLongOrNull() ?: 0L
                        end = match.groupValues[2].toLongOrNull()?.coerceAtMost(total - 1) ?: (total - 1)
                        if (start > end || start >= total) {
                            val out = client.getOutputStream()
                            out.write("HTTP/1.1 416 Range Not Satisfiable\\r\\nContent-Range: bytes */$total\\r\\nContent-Length: 0\\r\\nConnection: close\\r\\n\\r\\n".toByteArray(Charsets.US_ASCII))
                            out.flush()
                            return
                        }
                        status = "206 Partial Content"
                    }
                }
                val length = end - start + 1
                val headers = buildString {
                    append("HTTP/1.1 $status\\r\\n")
                    append("Content-Type: video/mp4\\r\\n")
                    append("Content-Disposition: attachment; filename=video.mp4\\r\\n")
                    append("Accept-Ranges: bytes\\r\\n")
                    if (status.startsWith("206")) append("Content-Range: bytes $start-$end/$total\\r\\n")
                    append("Content-Length: $length\\r\\n")
                    append("Cache-Control: no-store\\r\\n")
                    append("Connection: close\\r\\n\\r\\n")
                }
                val output = BufferedOutputStream(client.getOutputStream())
                output.write(headers.toByteArray(Charsets.US_ASCII))
                if (method != "HEAD") {
                    RandomAccessFile(file, "r").use { source ->
                        source.seek(start)
                        val buffer = ByteArray(64 * 1024)
                        var remaining = length
                        while (remaining > 0) {
                            val count = source.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            remaining -= count
                        }
                    }
                }
                output.flush()
            } catch (_: Exception) {
                // The browser may close a connection after cancelling a download.
            }
        }
    }

    private fun respond(socket: java.net.Socket, status: String, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val output = socket.getOutputStream()
        val header = "HTTP/1.1 $status\\r\\nContent-Type: $type\\r\\nContent-Length: ${bytes.size}\\r\\nConnection: close\\r\\n\\r\\n"
        output.write(header.toByteArray(Charsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }
}
