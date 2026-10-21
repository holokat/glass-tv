import unittest

from glasstv_linux.target import DEFAULT_PORT, TargetValidationError, parse_target


class TargetTests(unittest.TestCase):
    def test_accepts_canonical_numeric_ipv4_and_port(self):
        target = parse_target("192.168.1.20", "8743")
        self.assertEqual(target.host, "192.168.1.20")
        self.assertEqual(target.port, DEFAULT_PORT)
        self.assertEqual(target.display, "192.168.1.20:8743")

    def test_rejects_urls_names_ipv6_and_header_injection(self):
        invalid_hosts = (
            "https://192.168.1.20",
            "192.168.1.20/status",
            "192.168.1.20:8743",
            "192.168.1.20\r\nAuthorization: Bearer value",
            "receiver.local",
            "[::1]",
            "::1",
            " 192.168.1.20",
            "192.168.001.020",
        )
        for host in invalid_hosts:
            with self.subTest(host=host):
                with self.assertRaises(TargetValidationError):
                    parse_target(host)

    def test_rejects_invalid_ports(self):
        for port in (0, 65536, -1, True, "8\r\n", "https"):
            with self.subTest(port=port):
                with self.assertRaises(TargetValidationError):
                    parse_target("127.0.0.1", port)
