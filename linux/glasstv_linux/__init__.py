"""Portable core for the Glass TV Linux companion.

This package deliberately contains no GUI, pairing, credential storage, or
authenticated receiver requests.  Those features need separate approval and
implementation work.
"""

from .discovery import TLSCertificateDiscovery, discover_certificate, format_fingerprint
from .paths import AppPaths, LocalSettings
from .target import DEFAULT_PORT, ReceiverTarget, TargetValidationError, parse_target

__all__ = [
    "AppPaths",
    "DEFAULT_PORT",
    "LocalSettings",
    "ReceiverTarget",
    "TLSCertificateDiscovery",
    "TargetValidationError",
    "discover_certificate",
    "format_fingerprint",
    "parse_target",
]
