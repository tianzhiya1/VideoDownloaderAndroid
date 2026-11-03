import re
import sys
import subprocess
from pathlib import Path
from urllib.parse import urlparse

import imageio_ffmpeg
import requests
from PySide6.QtCore import QObject, Signal, QUrl, Qt, QThread
from PySide6.QtWidgets import (
    QApplication, QMainWindow, QWidget, QVBoxLayout, QHBoxLayout,
    QLineEdit, QPushButton, QLabel, QListWidget, QListWidgetItem,
    QMessageBox, QFileDialog, QSplitter
)
from PySide6.QtWebEngineCore import QWebEngineUrlRequestInterceptor
from PySide6.QtWebEngineWidgets import QWebEngineView

APP_VERSION = "1.3.0"
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
      "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36")
AUDIO_EXTENSIONS = (".mp3", ".m4a", ".m4b", ".aac", ".wav", ".flac",
                    ".ogg", ".oga", ".opus", ".weba", ".aiff", ".aif")
VIDEO_EXTENSIONS = (".mp4", ".m4v", ".mov", ".webm", ".mkv", ".avi",
                    ".flv", ".3gp", ".ts", ".mpeg", ".mpg", ".wmv",
                    ".ogv", ".mts", ".m2ts")
PLAYLIST_EXTENSIONS = (".m3u8", ".mpd")
ALL_EXTENSIONS = AUDIO_EXTENSIONS + VIDEO_EXTENSIONS + PLAYLIST_EXTENSIONS
EXTENSION_PATTERN = "|".join(ext[1:] for ext in ALL_EXTENSIONS)
MEDIA_RE = re.compile(
    rf"""https?://[^"'\s<>]+?\.(?:{EXTENSION_PATTERN})(?:\?[^"'\s<>]*)?""",
    re.IGNORECASE
)


def clean_url(address):
    return address.lower().split("?", 1)[0].split("#", 1)[0]


def media_kind(address):
    clean = clean_url(address)
    if clean.endswith(PLAYLIST_EXTENSIONS):
        return "播放列表（HLS / DASH）"
    if clean.endswith(AUDIO_EXTENSIONS):
        return "音频"
    if clean.endswith(VIDEO_EXTENSIONS):
        return "视频"
    return ""


def candidate_priority(address):
    if media_kind(address) == "音频":
        return 0
    if clean_url(address).endswith(".mp4"):
        return 1
    if clean_url(address).endswith(".m3u8"):
        return 2
    return 3


def resolve_bilibili(page_url):
    host = (urlparse(page_url).hostname or "").lower()
    if not (host == "bilibili.com" or host.endswith(".bilibili.com")
            or host == "b23.tv" or host.endswith(".b23.tv")):
        return None
    match = re.search(r"BV[0-9A-Za-z]{10}", page_url)
    if not match:
        return None
    bvid = match.group(0)
    headers = {"User-Agent": UA, "Referer": f"https://www.bilibili.com/video/{bvid}"}
    session = requests.Session()
    view = session.get("https://api.bilibili.com/x/web-interface/view",
                       params={"bvid": bvid}, headers=headers, timeout=15)
    view.raise_for_status()
    data = view.json()
    if data.get("code") != 0:
        return None
    cid = (data.get("data") or {}).get("cid")
    if not cid:
        return None
    play = session.get("https://api.bilibili.com/x/player/playurl",
        params={"bvid": bvid, "cid": cid, "qn": 80, "fnver": 0, "fnval": 0,
                "fourk": 1, "platform": "html5", "otype": "json"},
        headers=headers, timeout=15)
    play.raise_for_status()
    data = play.json()
    if data.get("code") != 0:
        return None
    segments = (data.get("data") or {}).get("durl") or []
    return segments[0].get("url") if segments else None


class MediaSignals(QObject):
    found = Signal(str)


class MediaRequestInterceptor(QWebEngineUrlRequestInterceptor):
    def __init__(self, signals):
        super().__init__()
        self.signals = signals

    def interceptRequest(self, info):
        address = info.requestUrl().toString()
        if media_kind(address):
            self.signals.found.emit(address)


class Mp3Worker(QThread):
    progress = Signal(str)
    succeeded = Signal(str)
    failed = Signal(str)

    def __init__(self, source_url, page_url, output_path):
        super().__init__()
        self.source_url = source_url
        self.page_url = page_url
        self.output_path = output_path

    def run(self):
        process = None
        try:
            ffmpeg = imageio_ffmpeg.get_ffmpeg_exe()
            args = [ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
                    "-user_agent", UA]
            if self.page_url:
                args += ["-referer", self.page_url]
            args += ["-i", self.source_url, "-vn", "-codec:a", "libmp3lame",
                     "-q:a", "2", "-id3v2_version", "3",
                     "-progress", "pipe:1", "-nostats", self.output_path]
            process = subprocess.Popen(args, stdout=subprocess.PIPE,
                stderr=subprocess.PIPE, text=True, encoding="utf-8",
                errors="replace")
            for line in process.stdout:
                if self.isInterruptionRequested():
                    process.terminate()
                    raise RuntimeError("转换已取消。")
                if line.startswith("out_time="):
                    self.progress.emit("正在提取音轨并转换为 MP3……")
            stderr = process.stderr.read()
            code = process.wait()
            if self.isInterruptionRequested():
                raise RuntimeError("转换已取消。")
            if code != 0:
                raise RuntimeError(stderr.strip()[-1800:] or f"FFmpeg 退出代码：{code}")
            output = Path(self.output_path)
            if not output.exists() or output.stat().st_size == 0:
                raise RuntimeError("没有生成有效的 MP3 文件。")
            self.succeeded.emit(self.output_path)
        except Exception as exc:
            if process and process.poll() is None:
                process.terminate()
            self.failed.emit(str(exc))


class MainWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle(f"VideoDownloader MP3 音频版 v{APP_VERSION}")
        self.resize(1280, 820)
        self.candidates = []
        self.worker = None
        self.signals = MediaSignals()
        self.signals.found.connect(self.add_candidate)
        self.interceptor = MediaRequestInterceptor(self.signals)

        central = QWidget()
        self.setCentralWidget(central)
        root = QVBoxLayout(central)
        title = QLabel("VideoDownloader · MP3 音频提取")
        title.setStyleSheet("font-size: 25px; font-weight: 600;")
        root.addWidget(title)
        root.addWidget(QLabel(
            "打开网页并播放媒体，检测到音频、视频或播放列表后，选择一个候选项，"
            "点击下方唯一按钮即可保存为 MP3。"
        ))

        url_row = QHBoxLayout()
        self.url_input = QLineEdit()
        self.url_input.setPlaceholderText("https://example.com/media-page")
        self.url_input.returnPressed.connect(self.open_page)
        open_button = QPushButton("打开网页并检测")
        open_button.clicked.connect(self.open_page)
        analyze_button = QPushButton("分析网页直链")
        analyze_button.clicked.connect(self.analyze_page)
        url_row.addWidget(self.url_input, 1)
        url_row.addWidget(open_button)
        url_row.addWidget(analyze_button)
        root.addLayout(url_row)

        splitter = QSplitter(Qt.Horizontal)
        self.web = QWebEngineView()
        profile = self.web.page().profile()
        profile.setHttpUserAgent(UA)
        profile.setUrlRequestInterceptor(self.interceptor)
        self.web.loadFinished.connect(self.on_load_finished)
        splitter.addWidget(self.web)

        right = QWidget()
        right_layout = QVBoxLayout(right)
        right_layout.addWidget(QLabel("检测到的媒体（逐个选择）"))
        self.list = QListWidget()
        self.list.currentRowChanged.connect(self.on_selection_changed)
        right_layout.addWidget(self.list, 1)
        self.status = QLabel("等待输入网页链接")
        self.status.setWordWrap(True)
        right_layout.addWidget(self.status)
        self.action_button = QPushButton("选择媒体后保存为 MP3")
        self.action_button.setEnabled(False)
        self.action_button.setMinimumHeight(48)
        self.action_button.clicked.connect(self.convert_selected)
        right_layout.addWidget(self.action_button)
        splitter.addWidget(right)
        splitter.setSizes([850, 390])
        root.addWidget(splitter, 1)
        root.addWidget(QLabel(
            "支持常见音频/视频直链，并尝试处理 HLS/DASH。部分网站因 DRM、登录限制、"
            "反爬、特殊 Cookie/请求头或无音轨内容可能失败。本软件不会绕过 DRM、"
            "付费墙或访问控制。请仅保存你有权下载的媒体。"
        ))

    def valid_http(self, value):
        parsed = urlparse(value)
        return parsed.scheme in ("http", "https") and bool(parsed.netloc)

    def reset_candidates(self):
        self.candidates.clear()
        self.list.clear()
        self.action_button.setEnabled(False)
        self.action_button.setText("选择媒体后保存为 MP3")

    def open_page(self):
        address = self.url_input.text().strip()
        if not self.valid_http(address):
            QMessageBox.warning(self, "地址无效", "请输入有效的 http 或 https 网页地址。")
            return
        self.reset_candidates()
        self.status.setText("正在加载网页并监听媒体请求……")
        self.web.setUrl(QUrl(address))

    def analyze_page(self):
        address = self.url_input.text().strip()
        if not self.valid_http(address):
            QMessageBox.warning(self, "地址无效", "请输入有效的 http 或 https 网页地址。")
            return
        self.reset_candidates()
        self.status.setText("正在分析网页……")
        try:
            direct = resolve_bilibili(address)
            if direct:
                self.add_candidate(direct)
                return
            response = requests.get(address, headers={"User-Agent": UA}, timeout=20)
            response.raise_for_status()
            matches = {m.replace("&amp;", "&") for m in MEDIA_RE.findall(response.text)}
            for item in sorted(matches, key=candidate_priority):
                self.add_candidate(item)
            if not matches:
                self.status.setText(
                    "没有找到公开媒体直链。请使用“打开网页并检测”，然后在播放器中点播放。")
        except Exception as exc:
            self.status.setText(f"分析失败：{exc}")

    def on_load_finished(self, ok):
        if not ok:
            self.status.setText("网页加载失败，可能是网络、证书或网站限制。")
            return
        self.status.setText("网页已打开。请在播放器中点击播放，应用会监听媒体请求。")
        address = self.web.url().toString()
        if "bilibili.com" in address or "b23.tv" in address:
            try:
                direct = resolve_bilibili(address)
                if direct:
                    self.add_candidate(direct)
            except Exception:
                pass
        self.web.page().toHtml(self.scan_html)

    def scan_html(self, html):
        for item in set(MEDIA_RE.findall(html or "")):
            self.add_candidate(item.replace("&amp;", "&"))

    def add_candidate(self, address):
        kind = media_kind(address)
        if not kind or address in self.candidates:
            return
        self.candidates.append(address)
        self.list.addItem(QListWidgetItem(f"{kind} | {address[:145]}"))
        if self.list.count() == 1:
            self.list.setCurrentRow(0)
        self.action_button.setEnabled(True)
        self.action_button.setText(f"保存所选媒体为 MP3（共识别 {len(self.candidates)} 个）")
        self.status.setText("已发现媒体。选择一个候选项，再点击唯一按钮保存 MP3。")

    def on_selection_changed(self, row):
        if 0 <= row < len(self.candidates):
            self.status.setText(
                f"当前选择：{media_kind(self.candidates[row])}。将提取音轨并保存为 MP3。")

    def convert_selected(self):
        row = self.list.currentRow()
        if row < 0 or row >= len(self.candidates):
            return
        if self.worker and self.worker.isRunning():
            return
        output_path, _ = QFileDialog.getSaveFileName(
            self, "保存 MP3 音频", "audio.mp3", "MP3 音频 (*.mp3)")
        if not output_path:
            return
        if not output_path.lower().endswith(".mp3"):
            output_path += ".mp3"
        self.action_button.setEnabled(False)
        self.action_button.setText("正在转换 MP3……")
        self.status.setText("正在读取媒体并提取音轨，请稍候……")
        self.worker = Mp3Worker(self.candidates[row],
                                self.web.url().toString(), output_path)
        self.worker.progress.connect(self.status.setText)
        self.worker.succeeded.connect(self.on_conversion_success)
        self.worker.failed.connect(self.on_conversion_failure)
        self.worker.start()

    def on_conversion_success(self, path):
        self.action_button.setEnabled(True)
        self.action_button.setText("保存所选媒体为 MP3")
        self.status.setText(f"MP3 已保存：{path}")
        QMessageBox.information(self, "转换完成", f"MP3 文件已保存到：\n{path}")

    def on_conversion_failure(self, message):
        self.action_button.setEnabled(True)
        self.action_button.setText("保存所选媒体为 MP3")
        self.status.setText("转换失败。")
        QMessageBox.warning(self, "MP3 转换失败",
            f"{message}\n\n请确认媒体可访问且包含音轨。")


def main():
    app = QApplication(sys.argv)
    app.setApplicationName("VideoDownloader")
    window = MainWindow()
    window.show()
    sys.exit(app.exec())


if __name__ == "__main__":
    main()
