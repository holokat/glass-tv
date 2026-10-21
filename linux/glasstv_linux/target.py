"""Strict receiver target validation for the local Glass TV protocol."""

from dataclasses import dataclass
import ipaddress
from typing import Union


DEFAULT_PORT = 8743


class TargetValidationError(ValueError):
    """Raised when a value cannot name one receiver endpoint safely."""


def _parse_port(value: Union[int, str]) -> int:
    if isinstance(value, bool):
        raise TargetValidationError("port must be an integer from 1 through 65535")
    if isinstance(value, str):
        if not value or not value.isascii() or not value.isdecimal():
            raise TargetValidationError("port must be an integer from 1 through 65535")
        value = int(value)
    if not isinstance(value, int) or not 1 <= value <= 65535:
        raise TargetValidationError("port must be an integer from 1 through 65535")
    return value


@dataclass(frozen=True)
class ReceiverTarget:
    """A numeric IPv4 receiver endpoint.

    Protocol version 1 is documented for numeric IPv4 targets.  Host names,
    URLs, IPv6 literals, paths, and credentials are intentionally rejected.
    """

    host: str
    port: int = DEFAULT_PORT

    def __post_init__(self) -> None:
        if not isinstance(self.host, str) or not self.host or self.host != self.host.strip():
            raise TargetValidationError("host must be a numeric IPv4 address")
        try:
            address = ipaddress.IPv4Address(self.host)
        except ipaddress.AddressValueError as error:
            raise TargetValidationError("host must be a numeric IPv4 address") from error
        if str(address) != self.host:
            raise TargetValidationError("host must be a canonical numeric IPv4 address")
        object.__setattr__(self, "port", _parse_port(self.port))

    @property
    def display(self) -> str:
        return "{}:{}".format(self.host, self.port)


def parse_target(host: str, port: Union[int, str] = DEFAULT_PORT) -> ReceiverTarget:
    """Return a validated protocol v1 target without resolving DNS."""

    return ReceiverTarget(host=host, port=_parse_port(port))
