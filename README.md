# VideoDownloaderAndroid

Android 视频下载器，现加入 yt-dlp 引擎，用于处理 YouTube 和 yt-dlp 支持的公开视频页面。

## 功能
- 粘贴视频网址，或从浏览器/其他应用分享链接。
- 新增 yt-dlp 下载入口：支持 YouTube 及 yt-dlp 支持的其他网站。
- 选择 yt-dlp 可用的最佳单文件视频格式（最高尝试 1080p），显示下载进度。
- Android 10 及以上系统下载完成后保存到系统“Movies/VideoDownloader”目录。
- 保留原有网页媒体请求监听、MP4/HLS/DASH 链接识别及 Bilibili 解析。
- 最低 Android 7.0（API 24）；支持 arm64-v8a 和 x86_64。

## YouTube 登录验证

如果出现“Sign in to confirm you’re not a bot”：
1. 在浏览器中正常登录 YouTube，并确认该视频能在浏览器播放。
2. 从浏览器导出 YouTube 的 Netscape 格式 `cookies.txt` 文件（文件首行应为 `# Netscape HTTP Cookie File` 或 `# HTTP Cookie File`）。
3. 在应用中点击“导入 YouTube Cookie（cookies.txt）”，选择该文件。
4. 再次下载。Cookie 仅复制到应用私有目录并在本机使用，不会上传到本项目。

Cookie 等同于登录凭证，请勿发给他人或上传到聊天、网盘、GitHub。Cookie 可能过期；YouTube 仍可能因 IP、风控或 PO Token 要求拒绝请求，导入 Cookie 不保证所有视频都可下载。若仍失败，请在浏览器确认可播放并稍后重试。请只下载你有权保存的内容。

## 使用
1. 粘贴 YouTube 视频网址。
2. 点击“**YouTube / yt-dlp 下载视频**”。
3. 等待下载完成，视频会保存到手机 Movies/VideoDownloader 文件夹。

## 技术
Kotlin、Android SDK、Media3、yt-dlp-android 2.0.2（内嵌 Python 3.13）。

## 注意
yt-dlp 引擎及其 Android 封装按其上游许可和第三方声明分发。YouTube 会持续调整播放器和访问机制，部分视频可能因地区、网络、账号权限或播放器挑战而失败。此版本不包含付费 TLS 指纹模拟组件，也不绕过 DRM、付费墙、私密视频或其他访问控制。请只下载你有权保存的内容。

由于内嵌 Python 运行环境，APK 会比旧版明显增大；当前依赖仅支持 arm64-v8a 和 x86_64。
