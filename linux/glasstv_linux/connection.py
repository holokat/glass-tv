"""Pure connection presentation and retry policy, independent of a GUI."""

from dataclasses import dataclass
from enum import Enum


class ConnectionActivity(str, Enum):
    IDLE = "idle"
    CHECKING = "checking"
    DISCOVERING = "discovering"
    PAIRING = "pairing"


class ConnectionState(str, Enum):
    UNPAIRED = "unpaired"
    DISCONNECTED = "disconnected"
    CONNECTED = "connected"
    CHECKING = "checking"
    DISCOVERING = "discovering"
    AWAITING_CODE = "awaiting_code"
    PAIRING = "pairing"


@dataclass(frozen=True)
class ConnectionFacts:
    activity: ConnectionActivity = ConnectionActivity.IDLE
    is_paired: bool = False
    has_certificate_challenge: bool = False
    has_authenticated_status: bool = False


def resolve_connection_state(facts: ConnectionFacts) -> ConnectionState:
    if facts.activity is ConnectionActivity.CHECKING:
        return ConnectionState.CONNECTED if facts.is_paired and facts.has_authenticated_status else ConnectionState.CHECKING
    if facts.activity is ConnectionActivity.DISCOVERING:
        return ConnectionState.DISCOVERING
    if facts.activity is ConnectionActivity.PAIRING:
        return ConnectionState.PAIRING
    if facts.has_certificate_challenge:
        return ConnectionState.AWAITING_CODE
    if not facts.is_paired:
        return ConnectionState.UNPAIRED
    return ConnectionState.CONNECTED if facts.has_authenticated_status else ConnectionState.DISCONNECTED


def retry_delay(failures: int) -> int:
    """Return the existing receiver probe interval in seconds."""

    if failures <= 0:
        return 20
    return (2, 4, 8, 10)[min(failures - 1, 3)]
