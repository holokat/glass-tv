"""Developer-only certificate-discovery command."""

import argparse
import os
import sys
from typing import Optional, Sequence

from .discovery import DiscoveryError, discover_certificate, format_fingerprint
from .target import DEFAULT_PORT, TargetValidationError, parse_target


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="glasstv-linux-discover",
        description="Launch the Glass TV Linux discovery window, or discover one explicit receiver certificate.",
    )
    parser.add_argument("host", nargs="?", help="Receiver numeric IPv4 address")
    parser.add_argument("--port", default=DEFAULT_PORT, help="Receiver HTTPS port (default: 8743)")
    parser.add_argument("--timeout", default=8.0, type=float, help="TLS timeout in seconds, up to 60 (default: 8)")
    parser.add_argument("--smoke-test", action="store_true", help="Launch and close the desktop window without network activity")
    parser.add_argument(
        "--smoke-screenshot",
        help="Optional existing-directory PNG output path for --smoke-test; also accepts GLASS_TV_SMOKE_SCREENSHOT",
    )
    return parser


def main(argv: Optional[Sequence[str]] = None) -> int:
    arguments = build_parser().parse_args(argv)
    screenshot_path = arguments.smoke_screenshot or os.environ.get("GLASS_TV_SMOKE_SCREENSHOT")
    if screenshot_path and not arguments.smoke_test:
        build_parser().error("--smoke-screenshot requires --smoke-test")
    if arguments.smoke_test:
        # This must precede the Qt import. CI can also set this explicitly.
        os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
        try:
            from .gui import run_application
        except ImportError:
            print("Desktop smoke test requires PySide6-Essentials==6.12.0.", file=sys.stderr)
            return 2
        return run_application(smoke_test=True, smoke_screenshot=screenshot_path)
    if arguments.host is None:
        try:
            from .gui import run_application
        except ImportError:
            print("Desktop window requires PySide6-Essentials==6.12.0.", file=sys.stderr)
            return 2
        return run_application()
    try:
        target = parse_target(arguments.host, arguments.port)
        fingerprint = discover_certificate(target, timeout=arguments.timeout)
    except (TargetValidationError, ValueError, DiscoveryError) as error:
        print("Discovery failed: {}".format(error), file=sys.stderr)
        return 1
    print("Target: {}".format(target.display))
    print("SHA-256: {}".format(format_fingerprint(fingerprint)))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
