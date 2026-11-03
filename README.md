# VideoDownloaderAndroid

Android 视频链接提取工具。输入视频网页后，软件使用 yt-dlp 或内置网页请求监听解析媒体直链；复制链接后，使用手机浏览器自行下载。**应用本身不下载视频文件。**

## 功能
- 粘贴视频网址，或从浏览器/其他应用分享链接。
- 使用 yt-dlp 解析 YouTube 及其他支持网站的视频直链。
- 识别网页公开请求到的 MP4、HLS/M3U8、DASH/MPD 等媒体地址。
- 解析后显示媒体链接，可复制到手机浏览器。
- 支持导入 Netscape 格式 YouTube cookies.txt。
- 保留 Bilibili 公开视频链接解析。
- 最低 Android 7.0（API 24）；支持 arm64-v8a 和 x86_64。

## 使用方法
1. 粘贴视频网页地址。
2. 点击“解析视频并获取下载链接”，或使用“识别网页中的视频”。
3. 解析成功后，点击复制按钮复制媒体直链。
4. 打开 Chrome 等手机浏览器，将链接粘贴到地址栏访问/下载。
5. 如果链接是 M3U8/MPD 播放清单，浏览器可能无法直接保存为完整视频；这类格式需要支持对应流格式的下载工具或播放器。

## YouTube 登录验证
如果 YouTube 提示“Sign in to confirm you're not a bot”，请先在自己的浏览器登录 YouTube，并确认视频可正常播放；然后导出 Netscape 格式的 cookies.txt，在应用中点击“导入 YouTube Cookie 文件”。Cookie 只保存在本机应用私有目录，不会上传。Cookie 等同于账号登录凭据，请勿分享或上传。导入 Cookie 不保证解决所有验证，YouTube 仍可能因 IP、风控或 PO Token 要求拒绝请求。

## 技术
Kotlin、Android SDK、Media3、yt-dlp-android 2.0.2（内嵌 Python 3.13）。

## 限制
直链可能有时效、来源页或请求头要求；部分网站会返回分离的音视频流，或加密/动态播放清单，因此不保证所有链接都能直接在浏览器下载。软件不会绕过 DRM、付费墙、私密内容或其他访问控制。请只提取和保存你有权访问的内容。
