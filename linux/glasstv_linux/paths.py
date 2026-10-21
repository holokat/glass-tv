"""App-isolated XDG paths and atomic storage for non-sensitive local settings."""

import json
import os
from dataclasses import dataclass
from pathlib import Path
import tempfile
from typing import Any, Mapping, MutableMapping, Optional


APP_DIRECTORY = "glass-tv-linux"


class StoragePathError(ValueError):
    """Raised when XDG storage configuration would escape the user's home area."""


class SettingsError(RuntimeError):
    """Raised when local non-sensitive settings cannot be read or saved."""


def _absolute_xdg_path(environ: Mapping[str, str], name: str, fallback: Path) -> Path:
    value = environ.get(name)
    if value is None or value == "":
        return fallback
    candidate = Path(value).expanduser()
    if not candidate.is_absolute():
        raise StoragePathError("{} must be an absolute path".format(name))
    return candidate


@dataclass(frozen=True)
class AppPaths:
    """The Linux companion's own XDG locations, without credential storage."""

    config: Path
    data: Path
    state: Path
    cache: Path

    @classmethod
    def from_environment(
        cls, environ: Optional[Mapping[str, str]] = None, home: Optional[Path] = None
    ) -> "AppPaths":
        environment = os.environ if environ is None else environ
        home_path = Path.home() if home is None else Path(home)
        if not home_path.is_absolute():
            raise StoragePathError("home path must be absolute")
        return cls(
            config=_absolute_xdg_path(environment, "XDG_CONFIG_HOME", home_path / ".config") / APP_DIRECTORY,
            data=_absolute_xdg_path(environment, "XDG_DATA_HOME", home_path / ".local" / "share") / APP_DIRECTORY,
            state=_absolute_xdg_path(environment, "XDG_STATE_HOME", home_path / ".local" / "state") / APP_DIRECTORY,
            cache=_absolute_xdg_path(environment, "XDG_CACHE_HOME", home_path / ".cache") / APP_DIRECTORY,
        )

    @property
    def settings_file(self) -> Path:
        return self.config / "settings.json"

    def ensure_directories(self) -> None:
        for directory in (self.config, self.data, self.state, self.cache):
            directory.mkdir(parents=True, exist_ok=True, mode=0o700)
            try:
                directory.chmod(0o700)
            except OSError:
                # The attempted mode is a best effort on filesystems that do not
                # implement POSIX permissions. Atomic replacement still applies.
                pass


class LocalSettings:
    """Atomically store JSON settings that contain no credentials or pairing data.

    Credentials are deliberately outside this module until an approved Linux
    secret-service adapter exists.  Callers must only save ordinary local UI
    preferences or drafts here.
    """

    def __init__(self, path: Path):
        self.path = Path(path)

    def load(self, default: Optional[MutableMapping[str, Any]] = None) -> MutableMapping[str, Any]:
        try:
            with self.path.open("r", encoding="utf-8") as handle:
                value = json.load(handle)
        except FileNotFoundError:
            return {} if default is None else dict(default)
        except (OSError, json.JSONDecodeError) as error:
            raise SettingsError("could not read local settings") from error
        if not isinstance(value, dict):
            raise SettingsError("local settings must contain a JSON object")
        return value

    def save(self, value: Mapping[str, Any]) -> None:
        if not isinstance(value, Mapping):
            raise SettingsError("local settings must be a JSON object")
        try:
            encoded = json.dumps(dict(value), ensure_ascii=False, sort_keys=True, indent=2, allow_nan=False)
        except (TypeError, ValueError) as error:
            raise SettingsError("local settings must be JSON serializable") from error
        payload = (encoded + "\n").encode("utf-8")
        parent = self.path.parent
        try:
            parent.mkdir(parents=True, exist_ok=True, mode=0o700)
            try:
                parent.chmod(0o700)
            except OSError:
                pass
            descriptor, temporary_name = tempfile.mkstemp(prefix=".settings-", suffix=".tmp", dir=str(parent))
            try:
                with os.fdopen(descriptor, "wb") as handle:
                    descriptor = -1
                    handle.write(payload)
                    handle.flush()
                    os.fsync(handle.fileno())
                os.chmod(temporary_name, 0o600)
                os.replace(temporary_name, self.path)
                self._fsync_directory(parent)
            finally:
                if descriptor != -1:
                    os.close(descriptor)
                try:
                    os.unlink(temporary_name)
                except FileNotFoundError:
                    pass
        except OSError as error:
            raise SettingsError("could not save local settings") from error

    @staticmethod
    def _fsync_directory(directory: Path) -> None:
        if not hasattr(os, "O_DIRECTORY"):
            return
        descriptor = os.open(str(directory), os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)
