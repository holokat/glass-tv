import unittest

from glasstv_linux.connection import ConnectionActivity, ConnectionFacts, ConnectionState, resolve_connection_state, retry_delay


class ConnectionTests(unittest.TestCase):
    def test_activity_and_verified_status_control_presentation(self):
        self.assertEqual(resolve_connection_state(ConnectionFacts()), ConnectionState.UNPAIRED)
        self.assertEqual(
            resolve_connection_state(ConnectionFacts(is_paired=True, has_authenticated_status=False)),
            ConnectionState.DISCONNECTED,
        )
        self.assertEqual(
            resolve_connection_state(ConnectionFacts(is_paired=True, has_authenticated_status=True)),
            ConnectionState.CONNECTED,
        )
        self.assertEqual(
            resolve_connection_state(
                ConnectionFacts(activity=ConnectionActivity.CHECKING, is_paired=True, has_authenticated_status=False)
            ),
            ConnectionState.CHECKING,
        )
        self.assertEqual(
            resolve_connection_state(ConnectionFacts(has_certificate_challenge=True)),
            ConnectionState.AWAITING_CODE,
        )

    def test_existing_retry_schedule_is_capped(self):
        self.assertEqual([retry_delay(value) for value in range(0, 7)], [20, 2, 4, 8, 10, 10, 10])
