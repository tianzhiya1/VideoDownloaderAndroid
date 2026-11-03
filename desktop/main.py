import re
import sys
import requests
from urllib.parse import urlparse
from PySide6.QtCore import QObject, Signal, QUrl, Qt
from PySide6.QtGui import QGuiApplication
from PySide6.QtWidgets import (
    QApplication, QMainWindow, QWidget, QVBoxLayout, QHBoxLayout,
    QLineEdit, QPushButton, QLabel, QListWidget, QListWidgetItem,
    QMessageBox, QDialog, QTextEdit, QSplitter
)
from PySide6.QtWebEngineCore import QWebEngineUrlRequestInterceptor
from PySide6.QtWebEngineWidgets import QWebEngineView

APP_VERSION = "1.2.0"
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
      "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36")
MEDIA_EXTENSIONS = (
    ".mp4", ".m4v", ".mov", ".webm", ".mkv", ".avi", ".flv",
    ".3gp", ".ts", ".mpeg", ".mpg", ".wmv", ".ogv", ".mts",
    ".m2ts", ".m3u8", ".mpd"
)
MEDIA_RE = re.compile(
    r"""https?://[^"'\s<>]+?\.(?:mp4|m4v|mov|webm|mkv|avi|flv|3gp|ts|mpeg|mpg|wmv|ogv|mts|m2ts|m3u8|mpd)(?:\?[^"'\s<>]*)?""",
    re.IGNORECASE
)

def media_kind(address: str) -> str:
    lower = address.lower()
    clean = lower.split("?", 1)[0].split("#", 1)[0]
    if ".m3u8" in lower:
        return "HLS / M3U8"
    if ".mpd" in lower:
        return "DASH / MPD"
    if any(clean.endswith(ext) or (ext + "?") in lower for ext in MEDIA_EXTENSIONS):
        return "视频文件"
    return ""

def candidate_priority(address: str) -> int:
    clean = address.lower().split("?", 1)[0].split("#", 1)[0]
    if clean.endswith(".mp4"):
        return 0
    if clean.endswith(".m3u8"):
        return 1
    if clean.endswith(".mpd"):
        return 2
    return 3

def resolve_bilibili(page_url: str):
    host = (urlparse(page_url).hostname or "").lower()
    if not (host == "bilibili.com" or host.endswith(".bilibili.com")
            or host == "b23.tv" or host.endswith(".b23.tv")):
        return None
    match = re.search(r"BV[0-9A-Za-z]{10}", page_url)
    if not match:
        return None
    bvid = match.group(0)
    referer = f"https://www.bilibili.com/video/{bvid}"
    headers = {"User-Agent": UA, "Referer": referer}
    session = requests.Session()
    view = session.get(
        "https://api.bilibili.com/x/web-interface/view",
        params={"bvid": bvid}, headers=headers, timeout=15
    )
    view.raise_for_status()
    view_data = view.json()
    if view_data.get("code") != 0:
        return None
    cid = (view_data.get("data") or {}).get("cid")
    if not cid:
        return None
    play = session.get(
        "https://api.bilibili.com/x/player/playurl",
        params={
            "bvid": bvid, "cid": cid, "qn": 80, "fnver": 0,
            "fnval": 0, "fourk": 1, "platform": "html5", "otype": "json"
        },
        headers=headers, timeout=15
    )
    play.raise_for_status()
    play_data = play.json()
    if play_data.get("code") != 0:
        return None
    segments = (play_data.get("data") or {}).get("durl") or []
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

class CopyDialog(QDialog):
    def __init__(self, address, parent=None):
        super().__init__(parent)
        self.setWindowTitle("已提取到媒体链接")
        self.resize(720, 260)
        layout = QVBoxLayout(self)
        layout.addWidget(QLabel(
            "这是网页播放器请求的媒体地址。MP4 通常是单个文件；"
            "M3U8/MPD 是播放清单，可能需要原网页会话或请求来源才能使用。"
        ))
        editor = QTextEdit()
        editor.setPlainText(address)
        editor.setReadOnly(True)
        layout.addWidget(editor)
        buttons = QHBoxLayout()
        copy_button = QPushButton("复制链接")
        close_button = QPushButton("关闭")
        copy_button.clicked.connect(
            lambda: (QGuiApplication.clipboard().setText(address),
                     QMessageBox.information(self, "已复制", "媒体链接已复制到剪贴板。"))
        )
        close_button.clicked.connect(self.accept)
        buttons.addStretch(1)
        buttons.addWidget(copy_button)
        buttons.addWidget(close_button)
        layout.addLayout(buttons)

class MainWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle(f"视频下载器（电脑版） v{APP_VERSION}")
        self.resize(1280, 820)
        self.candidates = []
        self.signals = MediaSignals()
        self.signals.found.connect(self.add_candidate)
        self.interceptor = MediaRequestInterceptor(self.signals)

        central = QWidget()
        self.setCentralWidget(central)
        root = QVBoxLayout(central)
        title = QLabel("视频下载器")
        title.setStyleSheet("font-size: 25px; font-weight: 600;")
        root.addWidget(title)
        root.addWidget(QLabel(
            "输入网页地址，在内置浏览器中播放网页视频。检测到媒体地址后，"
            "选择一个候选项，再点击下方唯一的复制按钮。"
        ))

        url_row = QHBoxLayout()
        self.url_input = QLineEdit()
        self.url_input.setPlaceholderText("https://example.com/video-page")
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
        right_layout.addWidget(QLabel("检测到的媒体地址（逐个选择，不会合并）"))
        self.list = QListWidget()
        self.list.currentRowChanged.connect(self.on_selection_changed)
        right_layout.addWidget(self.list, 1)
        self.status = QLabel("等待输入网页链接")
        self.status.setWordWrap(True)
        right_layout.addWidget(self.status)
        self.copy_button = QPushButton("复制所选视频链接")
        self.copy_button.setEnabled(False)
        self.copy_button.setMinimumHeight(46)
        self.copy_button.clicked.connect(self.copy_selected)
        right_layout.addWidget(self.copy_button)
        splitter.addWidget(right)
        splitter.setSizes([850, 390])
        root.addWidget(splitter, 1)

        root.addWidget(QLabel(
            "说明：仅提取网页公开请求到的媒体地址；不绕过 DRM、付费墙、登录限制或其他访问控制。"
            "某些动态播放器、加密流或需要特殊会话的网站可能无法识别。"
        ))

    def valid_http(self, value):
        parsed = urlparse(value)
        return parsed.scheme in ("http", "https") and bool(parsed.netloc)

    def reset_candidates(self):
        self.candidates.clear()
        self.list.clear()
        self.copy_button.setEnabled(False)
        self.copy_button.setText("复制所选视频链接")

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
                    "没有找到公开媒体直链。请使用“打开网页并检测”，然后在网页播放器中点播放。"
                )
        except Exception as exc:
            self.status.setText(f"分析失败：{exc}")

    def on_load_finished(self, ok):
        if not ok:
            self.status.setText("网页加载失败，可能是网络、证书或网站限制。")
            return
        self.status.setText(
            "网页已打开。请在播放器中点击播放；应用会监听 MP4/M3U8/MPD 等媒体请求。"
        )
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
        if not media_kind(address) or address in self.candidates:
            return
        self.candidates.append(address)
        label = f"{media_kind(address)}  |  {address[:150]}"
        self.list.addItem(QListWidgetItem(label))
        if self.list.count() == 1:
            self.list.setCurrentRow(0)
        self.copy_button.setEnabled(True)
        self.copy_button.setText(f"复制所选视频链接（共识别 {len(self.candidates)} 个）")
        self.status.setText("已发现媒体地址。选择列表中的一个候选项，再点击唯一的复制按钮。")

    def on_selection_changed(self, row):
        if 0 <= row < len(self.candidates):
            self.status.setText(
                f"当前选择：{media_kind(self.candidates[row])}。点击下方唯一按钮查看并复制链接。"
            )

    def copy_selected(self):
        row = self.list.currentRow()
        if row < 0 or row >= len(self.candidates):
            return
        CopyDialog(self.candidates[row], self).exec()

def main():
    app = QApplication(sys.argv)
    app.setApplicationName("视频下载器")
    window = MainWindow()
    window.show()
    sys.exit(app.exec())

if __name__ == "__main__":
    main()
