import io
import unittest
from contextlib import redirect_stdout
from unittest import mock

from glasstv_linux.__main__ import main


class CommandLineTests(unittest.TestCase):
    def test_success_prints_only_target_and_grouped_fingerprint(self):
        output = io.StringIO()
        with mock.patch("glasstv_linux.__main__.discover_certificate", return_value="ab" * 32):
            with redirect_stdout(output):
                result = main(["192.168.1.20", "--port", "9443"])
        self.assertEqual(result, 0)
        self.assertEqual(
            output.getvalue(),
            "Target: 192.168.1.20:9443\nSHA-256: {}\n".format(":".join(["AB"] * 32)),
        )
