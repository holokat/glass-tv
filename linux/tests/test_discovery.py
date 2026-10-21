import hashlib
from pathlib import Path
import shutil
import socket
import ssl
import subprocess
import tempfile
import threading
import time
import unittest

from glasstv_linux.discovery import DiscoveryError, TLSCertificateDiscovery, format_fingerprint
from glasstv_linux.target import parse_target


OPENSSL = shutil.which("openssl")


class LocalTLSServer:
    def __init__(self, certificate: Path, private_key: Path, response: bytes = b""):
        self._response = response
        self._received = b""
        self._error = None
        self._ready = threading.Event()
        self._socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self._socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self._socket.bind(("127.0.0.1", 0))
        self.port = self._socket.getsockname()[1]
        self._socket.listen(1)
        self._context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        self._context.minimum_version = ssl.TLSVersion.TLSv1_2
        self._context.maximum_version = ssl.TLSVersion.TLSv1_2
        self._context.load_cert_chain(str(certificate), str(private_key))
        self._thread = threading.Thread(target=self._serve, daemon=True)

    @property
    def received(self):
        return self._received

    def start(self):
        self._thread.start()
        self._ready.wait(1)
        return self

    def close(self):
        self._thread.join(2)
        self._socket.close()
        if self._error:
            raise self._error

    def _serve(self):
        self._ready.set()
        try:
            raw_socket, _ = self._socket.accept()
            with raw_socket:
                raw_socket.settimeout(1)
                with self._context.wrap_socket(raw_socket, server_side=True) as tls_socket:
                    if self._response:
                        tls_socket.sendall(self._response)
                    try:
                        self._received = tls_socket.recv(4096)
                    except (socket.timeout, ConnectionResetError):
                        self._received = b""
        except (OSError, ssl.SSLError) as error:
            self._error = error


@unittest.skipUnless(OPENSSL, "openssl is required to create the local TLS fixture")
class DiscoveryTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls._temporary = tempfile.TemporaryDirectory()
        root = Path(cls._temporary.name)
        cls.certificate = root / "certificate.pem"
        cls.private_key = root / "private-key.pem"
        subprocess.run(
            [
                OPENSSL,
                "req",
                "-x509",
                "-newkey",
                "rsa:2048",
                "-nodes",
                "-keyout",
                str(cls.private_key),
                "-out",
                str(cls.certificate),
                "-subj",
                "/CN=localhost",
                "-days",
                "1",
            ],
            check=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        cls.expected_fingerprint = hashlib.sha256(
            ssl.PEM_cert_to_DER_cert(cls.certificate.read_text(encoding="ascii"))
        ).hexdigest()

    @classmethod
    def tearDownClass(cls):
        cls._temporary.cleanup()

    def test_discovers_exact_leaf_certificate_and_sends_no_application_data(self):
        server = LocalTLSServer(self.certificate, self.private_key).start()
        try:
            fingerprint = TLSCertificateDiscovery().discover(parse_target("127.0.0.1", server.port), timeout=1)
        finally:
            server.close()
        self.assertEqual(fingerprint, self.expected_fingerprint)
        self.assertEqual(server.received, b"")

    def test_ignores_http_redirect_payload_without_issuing_an_http_request(self):
        server = LocalTLSServer(
            self.certificate,
            self.private_key,
            response=b"HTTP/1.1 302 Found\r\nLocation: https://example.invalid/\r\nContent-Length: 0\r\n\r\n",
        ).start()
        try:
            fingerprint = TLSCertificateDiscovery().discover(parse_target("127.0.0.1", server.port), timeout=1)
        finally:
            server.close()
        self.assertEqual(fingerprint, self.expected_fingerprint)
        self.assertEqual(server.received, b"")

    def test_tls_handshake_timeout_is_bounded(self):
        listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        listener.bind(("127.0.0.1", 0))
        listener.listen(1)
        port = listener.getsockname()[1]

        def accept_without_tls():
            connection, _ = listener.accept()
            with connection:
                time.sleep(0.3)

        thread = threading.Thread(target=accept_without_tls, daemon=True)
        thread.start()
        try:
            with self.assertRaises(DiscoveryError):
                TLSCertificateDiscovery().discover(parse_target("127.0.0.1", port), timeout=0.05)
        finally:
            thread.join(1)
            listener.close()

    def test_formats_fingerprint_in_uppercase_byte_groups(self):
        self.assertEqual(format_fingerprint("ab" * 32), ":".join(["AB"] * 32))
