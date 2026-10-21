"""The first Linux desktop window: local address entry and certificate discovery."""

from typing import Optional

from PySide6.QtCore import QObject, QThread, Qt, Signal, Slot
from PySide6.QtGui import QIcon, QPalette, QPixmap
from PySide6.QtWidgets import (
    QApplication,
    QFormLayout,
    QFrame,
    QGroupBox,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QMainWindow,
    QPushButton,
    QSpinBox,
    QVBoxLayout,
    QWidget,
)

from .discovery import DiscoveryError, discover_certificate, format_fingerprint
from .resources import asset_path
from .target import DEFAULT_PORT, TargetValidationError, parse_target


class DiscoveryWorker(QObject):
    """Run one credential-free TLS handshake outside the Qt event loop."""

    succeeded = Signal(str, str)
    failed = Signal(str)

    def __init__(self, host: str, port: int) -> None:
        super().__init__()
        self._host = host
        self._port = port

    @Slot()
    def run(self) -> None:
        try:
            target = parse_target(self._host, self._port)
            fingerprint = discover_certificate(target, timeout=8.0)
        except (TargetValidationError, ValueError, DiscoveryError) as error:
            self.failed.emit(str(error))
            return
        self.succeeded.emit(target.display, format_fingerprint(fingerprint))


class DiscoveryWindow(QMainWindow):
    """A native Qt discovery screen with no pairing or credential behavior."""

    def __init__(self) -> None:
        super().__init__()
        self._thread: Optional[QThread] = None
        self._worker: Optional[DiscoveryWorker] = None
        self.setWindowTitle("Glass TV")
        self.setMinimumSize(640, 560)
        self.resize(760, 650)
        self._build_ui()

    def _build_ui(self) -> None:
        root = QWidget(self)
        self.setCentralWidget(root)
        layout = QVBoxLayout(root)
        layout.setContentsMargins(28, 24, 28, 28)
        layout.setSpacing(20)

        title = QLabel("Connect to a TV")
        title_font = title.font()
        title_font.setPointSize(24)
        title_font.setBold(True)
        title.setFont(title_font)
        title.setAccessibleName("Connect to a TV")
        layout.addWidget(title)

        introduction = QLabel("Enter the TV’s numeric IPv4 address to read its TLS certificate fingerprint.")
        introduction.setWordWrap(True)
        introduction.setForegroundRole(QPalette.ColorRole.Mid)
        layout.addWidget(introduction)

        receiver_group = QGroupBox("Receiver")
        receiver_form = QFormLayout(receiver_group)
        receiver_form.setFieldGrowthPolicy(QFormLayout.FieldGrowthPolicy.AllNonFixedFieldsGrow)
        receiver_form.setSpacing(12)
        self.host_field = QLineEdit()
        self.host_field.setPlaceholderText("192.168.1.20")
        self.host_field.setClearButtonEnabled(True)
        self.host_field.setAccessibleName("TV IPv4 address")
        self.host_field.returnPressed.connect(self.start_discovery)
        receiver_form.addRow("TV address", self.host_field)
        self.port_field = QSpinBox()
        self.port_field.setRange(1, 65535)
        self.port_field.setValue(DEFAULT_PORT)
        self.port_field.setAccessibleName("TV HTTPS port")
        receiver_form.addRow("HTTPS port", self.port_field)
        layout.addWidget(receiver_group)

        action_row = QHBoxLayout()
        action_row.addStretch(1)
        self.discover_button = QPushButton("Discover certificate")
        refresh_icon = asset_path("icons/refresh.svg")
        if refresh_icon.is_file():
            self.discover_button.setIcon(QIcon(str(refresh_icon)))
        self.discover_button.setMinimumHeight(40)
        self.discover_button.setAccessibleName("Discover TV certificate")
        self.discover_button.clicked.connect(self.start_discovery)
        action_row.addWidget(self.discover_button)
        layout.addLayout(action_row)

        separator = QFrame()
        separator.setFrameShape(QFrame.Shape.HLine)
        separator.setFrameShadow(QFrame.Shadow.Sunken)
        layout.addWidget(separator)

        result_group = QGroupBox("Certificate")
        result_layout = QVBoxLayout(result_group)
        result_layout.setSpacing(10)
        self.status_label = QLabel("No certificate discovered.")
        self.status_label.setWordWrap(True)
        self.status_label.setAccessibleName("Certificate discovery status")
        result_layout.addWidget(self.status_label)
        self.fingerprint_field = QLineEdit()
        self.fingerprint_field.setReadOnly(True)
        self.fingerprint_field.setPlaceholderText("SHA-256 fingerprint")
        self.fingerprint_field.setAccessibleName("TLS certificate SHA-256 fingerprint")
        result_layout.addWidget(self.fingerprint_field)
        layout.addWidget(result_group)

        preview_group = QGroupBox("Bundled receiver background")
        preview_layout = QVBoxLayout(preview_group)
        preview_layout.setSpacing(8)
        self.preview = QLabel("Bundled reef image is unavailable in this checkout.")
        self.preview.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.preview.setWordWrap(True)
        self.preview.setMinimumHeight(150)
        self.preview.setAccessibleName("Read-only bundled receiver background preview")
        self._load_preview()
        preview_layout.addWidget(self.preview)
        preview_note = QLabel("Read-only preview. It does not show a live TV feed.")
        preview_note.setForegroundRole(QPalette.ColorRole.Mid)
        preview_layout.addWidget(preview_note)
        layout.addWidget(preview_group)
        layout.addStretch(1)

    def _load_preview(self) -> None:
        image = asset_path("backgrounds/fluorescent-anemone-reef.jpg")
        pixmap = QPixmap(str(image))
        if pixmap.isNull():
            return
        self.preview.setPixmap(pixmap.scaled(480, 220, Qt.AspectRatioMode.KeepAspectRatio, Qt.TransformationMode.SmoothTransformation))
        self.preview.setText("")

    @Slot()
    def start_discovery(self) -> None:
        if self._thread is not None:
            return
        self.fingerprint_field.clear()
        self.status_label.setText("Reading the receiver certificate…")
        self._set_discovery_controls(False)
        thread = QThread(self)
        worker = DiscoveryWorker(self.host_field.text(), self.port_field.value())
        worker.moveToThread(thread)
        thread.started.connect(worker.run)
        worker.succeeded.connect(self._discovery_succeeded)
        worker.failed.connect(self._discovery_failed)
        worker.succeeded.connect(thread.quit)
        worker.failed.connect(thread.quit)
        thread.finished.connect(worker.deleteLater)
        thread.finished.connect(self._discovery_finished)
        self._thread = thread
        self._worker = worker
        thread.start()

    @Slot(str, str)
    def _discovery_succeeded(self, target: str, fingerprint: str) -> None:
        self.status_label.setText("Certificate read from {}. Compare this fingerprint on the TV before pairing.".format(target))
        self.fingerprint_field.setText(fingerprint)

    @Slot(str)
    def _discovery_failed(self, message: str) -> None:
        self.status_label.setText("Could not read a certificate: {}".format(message))

    @Slot()
    def _discovery_finished(self) -> None:
        self._thread = None
        self._worker = None
        self._set_discovery_controls(True)

    def _set_discovery_controls(self, enabled: bool) -> None:
        self.host_field.setEnabled(enabled)
        self.port_field.setEnabled(enabled)
        self.discover_button.setEnabled(enabled)


def run_application(smoke_test: bool = False, smoke_screenshot: Optional[str] = None) -> int:
    """Run the Qt window, optionally opening and closing it without network work."""

    application = QApplication.instance() or QApplication([])
    window = DiscoveryWindow()
    window.show()
    if smoke_test:
        from PySide6.QtCore import QTimer

        def finish_smoke_test() -> None:
            if smoke_screenshot:
                # The caller supplies the artifact path. Settings and network are
                # untouched; a failed capture makes CI fail rather than silently
                # reporting a visual result that was never rendered.
                if not window.grab().save(smoke_screenshot):
                    application.exit(3)
                    return
            application.quit()

        # Give the offscreen platform one event-loop turn to polish and render.
        QTimer.singleShot(50, finish_smoke_test)
    return application.exec()
