"""Credential-free TLS certificate discovery.

Discovery opens one TLS 1.2 socket and reads the leaf certificate.  It does not
write HTTP bytes, so it cannot invoke a receiver route, follow an HTTP redirect,
or transmit a pairing code or bearer token.
"""

from dataclasses import dataclass
import hashlib
import socket
import ssl
from typing import Callable, Optional

from .target import ReceiverTarget


class DiscoveryError(RuntimeError):
    """A bounded, non-sensitive discovery failure."""


SocketFactory = Callable[..., socket.socket]


def _validate_timeout(timeout: float) -> float:
    if isinstance(timeout, bool):
        raise ValueError("timeout must be greater than zero and at most 60 seconds")
    try:
        result = float(timeout)
    except (TypeError, ValueError) as error:
        raise ValueError("timeout must be greater than zero and at most 60 seconds") from error
    if not 0 < result <= 60:
        raise ValueError("timeout must be greater than zero and at most 60 seconds")
    return result


def create_discovery_context() -> ssl.SSLContext:
    """Create an isolated TLS 1.2 context for unauthenticated discovery only."""

    context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    context.check_hostname = False
    context.verify_mode = ssl.CERT_NONE
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.maximum_version = ssl.TLSVersion.TLSv1_2
    return context


def format_fingerprint(fingerprint: str) -> str:
    """Render a normalized SHA-256 hex fingerprint in readable byte groups."""

    if not isinstance(fingerprint, str) or len(fingerprint) != 64:
        raise ValueError("fingerprint must contain exactly 64 hexadecimal characters")
    try:
        int(fingerprint, 16)
    except ValueError as error:
        raise ValueError("fingerprint must contain exactly 64 hexadecimal characters") from error
    normalized = fingerprint.upper()
    return ":".join(normalized[index : index + 2] for index in range(0, len(normalized), 2))


@dataclass
class TLSCertificateDiscovery:
    """A replaceable transport adapter for production code and local tests."""

    context_factory: Callable[[], ssl.SSLContext] = create_discovery_context
    socket_factory: SocketFactory = socket.create_connection

    def discover(self, target: ReceiverTarget, timeout: float = 8.0) -> str:
        timeout = _validate_timeout(timeout)
        try:
            with self.socket_factory((target.host, target.port), timeout=timeout) as raw_socket:
                raw_socket.settimeout(timeout)
                context = self.context_factory()
                # Server name indication is unnecessary for a numeric local endpoint.
                # Omitting it also ensures no untrusted hostname is serialized into TLS.
                with context.wrap_socket(raw_socket, server_hostname=None) as tls_socket:
                    certificate = tls_socket.getpeercert(binary_form=True)
        except (OSError, ssl.SSLError) as error:
            raise DiscoveryError("could not establish a TLS connection to the receiver") from error
        if not certificate:
            raise DiscoveryError("receiver did not present a TLS certificate")
        return hashlib.sha256(certificate).hexdigest()


def discover_certificate(target: ReceiverTarget, timeout: float = 8.0) -> str:
    """Discover the target's leaf-certificate SHA-256 fingerprint.

    This helper accepts only an already-validated target.  It never performs
    pairing or persists anything.
    """

    return TLSCertificateDiscovery().discover(target, timeout=timeout)
