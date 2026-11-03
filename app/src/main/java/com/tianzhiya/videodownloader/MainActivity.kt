package com.tianzhiya.videodownloader

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ContentValues
import android.content.ClipboardManager
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
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
import dev.ffmpegkit_maintained.ytdlp.DownloadProgressCallback
import dev.ffmpegkit_maintained.ytdlp.YtDlp
import dev.ffmpegkit_maintained.ytdlp.YtDlpException
import dev.ffmpegkit_maintained.ytdlp.YtDlpRequest
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
    private lateinit var ytDlpButton: Button
    private lateinit var cookieImportButton: Button
    private var youtubeCookiesFile: File? = null
    private val cookiePickerRequest = 8021
    private lateinit var cookieButton: Button
    private val cookiePickerRequest = 7412
    private val cookieFile: File by lazy { File(filesDir, "yt-dlp-cookies.txt") }
    private var ytDlpInitialized = false
    private var youtubeCookiesFile: File? = null
    private val requestImportCookies = 4102
    private var selectedMediaAddress: String? = null
    private var manuallySelected = false
    private val candidateViews = LinkedHashMap<String, TextView>()
    private val capturedMedia = ConcurrentHashMap<String, Map<String, String>>()
    private var transformer: Transformer? = null
    private var converting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        downloadServer = Mp4BrowserServer()
        downloadServer.start()
        buildUi()
        youtubeCookiesFile = File(filesDir, "youtube-cookies.txt").takeIf { it.exists() && it.length() > 0 }
        if (youtubeCookiesFile != null) cookieImportButton.text = "YouTube Cookie 已导入（点击可更换）"
        initYtDlp()
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
            text = "粘贴网页链接并在应用内打开。识别到真实视频地址后，可在应用内查看并复制链接，不会自动跳转浏览器。"
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
        ytDlpButton = Button(this).apply {
            text = "YouTube / yt-dlp 下载视频"
            setOnClickListener { startYtDlpDownload(urlInput.text.toString().trim()) }
        }
        root.addView(ytDlpButton)
        cookieButton = Button(this).apply {
            text = if (cookieFile.exists()) "YouTube Cookie 已导入（点击更换）" else "导入 YouTube Cookie 文件"
            setOnClickListener {
                val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "text/*"
                }
                startActivityForResult(picker, cookiePickerRequest)
            }
        }
        root.addView(cookieButton)
        root.addView(Button(this).apply {
            text = "导入 YouTube Cookie（cookies.txt）"
            setOnClickListener {
                val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "text/*"
                }
                startActivityForResult(picker, requestImportCookies)
            }
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
                    runOnUiThread { status.text = "网页已打开。请在播放器中点播放，应用会监听实际媒体请求；识别到链接后可在下方点选并复制。" }
                    if (!url.isNullOrBlank() && isBilibiliPage(url)) {
                        executor.execute {
                            try {
                                val direct = resolveBilibiliMp4(url)
                                if (!direct.isNullOrBlank()) {
                                    runOnUiThread { addMediaCandidate(direct) }
                                }
                            } catch (_: Exception) {
                                // Keep normal WebView media interception as a fallback.
                            }
                        }
                    }
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
            text = "复制所选视频链接"
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

    @Deprecated("Use Activity Result APIs when modernizing this legacy Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != cookiePickerRequest || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        executor.execute {
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    cookieFile.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("无法读取所选文件")
                val valid = cookieFile.readLines().any {
                    it.startsWith("# Netscape HTTP Cookie File") ||
                    it.startsWith("# HTTP Cookie File") ||
                    (it.isNotBlank() && !it.startsWith("#") && it.split("\\t").size >= 7)
                }
                if (!valid) {
                    cookieFile.delete()
                    throw IllegalArgumentException("文件格式不正确。请选择 Netscape 格式的 cookies.txt")
                }
                runOnUiThread {
                    cookieButton.text = "YouTube Cookie 已导入（点击更换）"
                    status.text = "Cookie 已安全保存在本机应用私有目录。再次下载时会由 yt-dlp 本地读取。"
                }
            } catch (e: Exception) {
                runOnUiThread {
                    cookieFile.delete()
                    status.text = "导入 Cookie 失败：${e.message ?: "无法读取文件"}"
                }
            }
        }
    }

    private fun initYtDlp() {
        try {
            YtDlp.init(applicationContext)
            ytDlpInitialized = true
            status.text = "yt-dlp 已就绪，可解析 YouTube 和其他支持的网站。"
        } catch (e: YtDlpException) {
            ytDlpInitialized = false
            status.text = "yt-dlp 初始化失败：${e.message ?: "运行环境不可用"}"
            ytDlpButton.isEnabled = false
        }
    }

    private fun startYtDlpDownload(page: String) {
        if (!isHttpUrl(page)) {
            status.text = "请先输入有效的 YouTube 或其他视频网页链接。"
            return
        }
        if (!ytDlpInitialized) {
            status.text = "yt-dlp 尚未初始化成功，无法开始下载。"
            return
        }
        val outputDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: File(cacheDir, "yt-dlp-movies")
        if (!outputDir.exists()) outputDir.mkdirs()
        val before = outputDir.listFiles()?.map { it.name }?.toSet() ?: emptySet()
        val outputTemplate = File(outputDir, "%(title)s-%(id)s.%(ext)s").absolutePath
        val request = YtDlpRequest(page)
            .setOutputTemplate(outputTemplate)
            .addOption("--no-playlist")
            .addOption("-f", "best[height<=1080]/best")
            .addOption("--no-mtime")
        if (cookieFile.exists() && cookieFile.length() > 0L) {
            request.addOption("--cookies", cookieFile.absolutePath)
        }
        youtubeCookiesFile?.takeIf { it.isFile && it.length() > 0L }?.let {
            request.addOption("--cookies", it.absolutePath)
        }
        ytDlpButton.isEnabled = false
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = false
        progress.progress = 0
        status.text = "正在解析并下载视频……"
        executor.execute {
            try {
                val task = YtDlp.executeAsync(request, object : DownloadProgressCallback {
                    override fun onProgressUpdate(progressValue: Float, etaInSeconds: Long, line: String) {
                        runOnUiThread {
                            if (progressValue >= 0f) {
                                progress.progress = progressValue.toInt().coerceIn(0, 100)
                                status.text = "下载中：${progressValue.toInt()}%  剩余约 ${etaInSeconds}s\n${line.take(180)}"
                            } else if (line.isNotBlank()) {
                                status.text = "正在处理视频：${line.take(180)}"
                            }
                        }
                    }
                })
                val response = task.get()
                val downloaded = outputDir.listFiles()
                    ?.filter { it.isFile && it.name !in before }
                    ?.maxByOrNull { it.lastModified() }
                val saved = if (response.isSuccess && downloaded != null) {
                    publishDownloadedVideo(downloaded)
                } else null
                runOnUiThread {
                    progress.visibility = View.GONE
                    ytDlpButton.isEnabled = true
                    if (response.isSuccess && downloaded != null) {
                        status.text = if (saved != null) {
                            "下载完成，已保存到：Movies/VideoDownloader/${downloaded.name}"
                        } else {
                            "下载完成，文件保存在应用视频目录：${downloaded.absolutePath}"
                        }
                        Toast.makeText(this, "视频下载完成", Toast.LENGTH_LONG).show()
                    } else {
                        status.text = "下载未完成（退出码：${response.exitCode}）。${response.errorOutput.ifBlank { "请检查视频是否可公开访问，或稍后重试。" }}"
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progress.visibility = View.GONE
                    ytDlpButton.isEnabled = true
                    status.text = "yt-dlp 下载失败：${e.cause?.message ?: e.message ?: "未知错误"}"
                }
            }
        }
    }

    @Deprecated("Use Activity Result APIs when modernizing this screen")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != requestImportCookies || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        executor.execute {
            try {
                val destination = File(filesDir, "youtube-cookies.txt")
                contentResolver.openInputStream(uri)?.use { input ->
                    destination.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("无法读取所选文件")
                val firstLine = destination.useLines { it.firstOrNull().orEmpty().trim() }
                if (firstLine != "# Netscape HTTP Cookie File" &&
                    firstLine != "# HTTP Cookie File") {
                    destination.delete()
                    throw IllegalArgumentException("文件格式不正确。请选择 Netscape 格式的 cookies.txt。")
                }
                youtubeCookiesFile = destination
                runOnUiThread {
                    status.text = "YouTube Cookie 已导入并保存在本机应用私有目录。下次下载会自动使用。请勿分享此文件。"
                    Toast.makeText(this, "Cookie 导入成功", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Cookie 导入失败：${e.message ?: "文件读取失败"}"
                }
            }
        }
    }

    private fun chooseYoutubeCookies() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivityForResult(intent, cookiePickerRequest)
    }

    @Deprecated("Deprecated in Android API; retained for broad device compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != cookiePickerRequest || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        executor.execute {
            try {
                val content = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    ?: throw IllegalArgumentException("无法读取所选文件")
                if (content.length > 5_000_000) throw IllegalArgumentException("Cookie 文件过大")
                val validCookieRows = content.lineSequence().count { line ->
                    val row = line.trim()
                    row.isNotEmpty() && (row.startsWith("#HttpOnly_") || (!row.startsWith("#") && row.count { it == '\t' } >= 6))
                }
                if (validCookieRows == 0) throw IllegalArgumentException("文件格式不正确。请导入 Netscape 格式的 cookies.txt")
                val target = File(filesDir, "youtube-cookies.txt")
                target.writeText(content, Charsets.UTF_8)
                youtubeCookiesFile = target
                runOnUiThread {
                    cookieImportButton.text = "YouTube Cookie 已导入（点击可更换）"
                    status.text = "Cookie 已保存在本机应用私有目录。下载时将尝试使用该登录状态。"
                    Toast.makeText(this, "Cookie 导入成功", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Cookie 导入失败：${e.message ?: "文件无法读取"}"
                }
            }
        }
    }

    private fun publishDownloadedVideo(file: File): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val extension = file.extension.lowercase()
        val mime = when (extension) {
            "mp4", "m4v" -> "video/mp4"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            else -> "video/*"
        }
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, mime)
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/VideoDownloader")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            contentResolver.openOutputStream(uri)?.use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
            } ?: throw IllegalStateException("无法写入系统媒体库")
            val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            contentResolver.update(uri, done, null, null)
            uri
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            null
        }
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
        manuallySelected = false
        candidateViews.clear()
        mediaContainer.removeAllViews()
        downloadButton.isEnabled = false
        downloadButton.text = "复制所选视频链接"
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
                val media = if (isMediaUrl(page)) page else resolveBilibiliMp4(page) ?: findMedia(page)
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
        val full = value.lowercase()
        return when {
            clean.endsWith(".m3u8") || full.contains(".m3u8") -> "HLS / M3U8"
            clean.endsWith(".mpd") || full.contains(".mpd") -> "DASH / MPD"
            listOf(".mp4", ".m4v", ".mov", ".webm", ".mkv", ".avi", ".flv", ".3gp", ".ts", ".mpeg", ".mpg", ".wmv", ".ogv", ".mts", ".m2ts")
                .any { clean.endsWith(it) || full.contains("$it?") } -> "视频文件"
            else -> ""
        }
    }

    private fun isMediaUrl(value: String): Boolean = mediaKind(value).isNotEmpty()

    private fun isMp4Url(value: String): Boolean {
        val path = Uri.parse(value).path.orEmpty().lowercase()
        return path.endsWith(".mp4") || path.endsWith(".m4v")
    }

    private fun isBilibiliPage(value: String): Boolean {
        val host = Uri.parse(value).host?.lowercase().orEmpty()
        return host == "bilibili.com" || host.endsWith(".bilibili.com") ||
            host == "b23.tv" || host.endsWith(".b23.tv")
    }

    /**
     * Resolve a Bilibili video page to its current signed MP4 URL.
     * The signed URL changes/expires, so it is fetched at download time.
     * fnval=0 requests the durl/MP4 response.
     */
    private fun resolveBilibiliMp4(page: String): String? {
        if (!isBilibiliPage(page)) return null
        val bvid = Regex("""BV[0-9A-Za-z]{10}""").find(page)?.value ?: return null
        val referer = "https://www.bilibili.com/video/$bvid"
        val viewText = requestText(
            "https://api.bilibili.com/x/web-interface/view?bvid=$bvid",
            referer
        )
        val viewJson = org.json.JSONObject(viewText)
        if (viewJson.optInt("code", -1) != 0) return null
        val viewData = viewJson.optJSONObject("data") ?: return null
        val cid = viewData.optLong("cid", 0L)
        if (cid <= 0L) return null

        val playUrl = "https://api.bilibili.com/x/player/playurl" +
            "?bvid=$bvid&cid=$cid&qn=80&fnver=0&fnval=0&fourk=1&platform=html5&otype=json"
        val playText = requestText(playUrl, referer)
        val playJson = org.json.JSONObject(playText)
        if (playJson.optInt("code", -1) != 0) return null
        val data = playJson.optJSONObject("data") ?: return null
        val segments = data.optJSONArray("durl") ?: return null
        if (segments.length() == 0) return null
        return segments.optJSONObject(0)?.optString("url")?.takeIf { it.startsWith("https://") }
    }

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

        if (candidateViews.containsKey(address)) return

        // Before the user makes a choice, keep the best default candidate.
        // Once a row is tapped, never silently switch away from that choice.
        if (!manuallySelected) {
            val current = selectedMediaAddress
            if (current == null || candidatePriority(address) < candidatePriority(current)) {
                selectedMediaAddress = address
            }
        }

        if (mediaContainer.childCount < 30) {
            val row = TextView(this).apply {
                text = "○  $kind：${address.take(110)}"
                textSize = 12f
                setPadding(12, 14, 12, 14)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (converting) return@setOnClickListener
                    manuallySelected = true
                    selectedMediaAddress = address
                    refreshCandidateSelection()
                    status.text = "已选择这个视频。点击下面唯一的按钮，在弹窗中查看并复制它的真实媒体链接。"
                }
            }
            candidateViews[address] = row
            mediaContainer.addView(row)
        }
        refreshCandidateSelection()
        downloadButton.isEnabled = !converting && selectedMediaAddress != null
        downloadButton.text = "复制所选视频链接（共识别 ${candidateViews.size} 个）"
        if (!manuallySelected) {
            status.text = "已自动选中优先级较高的视频。你也可以点选列表中的任意一个，再用同一个按钮复制链接。"
        }
    }

    private fun refreshCandidateSelection() {
        candidateViews.forEach { (address, row) ->
            val selected = address == selectedMediaAddress
            val kind = mediaKind(address)
            row.text = if (selected) "● 已选择  $kind：${address.take(110)}"
                       else "○ 点此选择  $kind：${address.take(110)}"
            row.setBackgroundColor(if (selected) 0x223F83F8 else 0x00000000)
        }
    }

    private fun convertThenOpenBrowser() {
        val address = selectedMediaAddress
        if (address.isNullOrBlank() || converting) return
        showCopyLinkDialog(address)
    }

    private fun showCopyLinkDialog(address: String) {
        val editor = EditText(this).apply {
            setText(address)
            setTextIsSelectable(true)
            minLines = 3
            maxLines = 7
            setPadding(28, 20, 28, 20)
            setSelection(0)
        }
        AlertDialog.Builder(this)
            .setTitle("已提取到媒体链接")
            .setMessage("这是播放器请求的媒体地址。MP4 通常是单个视频文件；M3U8/MPD 是播放清单，可能需要原网页会话或请求来源才能使用。")
            .setView(editor)
            .setPositiveButton("复制链接") { _, _ ->
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("媒体链接", address))
                status.text = "链接已复制。你可以粘贴到支持该格式的播放器或下载工具中。"
                Toast.makeText(this, "媒体链接已复制", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("全选") { _, _ ->
                editor.requestFocus()
                editor.selectAll()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun findMedia(page: String): String? {
        val html = requestText(page)
        val pattern = Regex(
            """https?://[^"'\s<>]+?\.(?:mp4|m4v|mov|webm|mkv|avi|flv|3gp|ts|mpeg|mpg|wmv|ogv|mts|m2ts|m3u8|mpd)(?:\?[^"'\s<>]*)?""",
            RegexOption.IGNORE_CASE
        )
        return pattern.findAll(html).map { it.value.replace("&amp;", "&") }
            .sortedBy { candidatePriority(it) }.firstOrNull()
    }

    private fun requestText(address: String, referer: String? = null): String {
        val connection = URL(address).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15000
            connection.readTimeout = 20000
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/130.0.0.0 Mobile Safari/537.36")
            connection.setRequestProperty("Accept", "*/*")
            referer?.let { connection.setRequestProperty("Referer", it) }
            val cookie = CookieManager.getInstance().getCookie(referer ?: address)
            if (!cookie.isNullOrBlank()) connection.setRequestProperty("Cookie", cookie)
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
                            out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$total\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                            out.flush()
                            return
                        }
                        status = "206 Partial Content"
                    }
                }
                val length = end - start + 1
                val headers = buildString {
                    append("HTTP/1.1 $status\r\n")
                    append("Content-Type: video/mp4\r\n")
                    append("Content-Disposition: attachment; filename=video.mp4\r\n")
                    append("Accept-Ranges: bytes\r\n")
                    if (status.startsWith("206")) append("Content-Range: bytes $start-$end/$total\r\n")
                    append("Content-Length: $length\r\n")
                    append("Cache-Control: no-store\r\n")
                    append("Connection: close\r\n\r\n")
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
        val header = "HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        output.write(header.toByteArray(Charsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }
}
