"""Resolve the small, approved resource set used by the Linux preview."""

import os
from pathlib import Path
from typing import Mapping, Optional


RESOURCE_ROOT_ENVIRONMENT_VARIABLE = "GLASS_TV_RESOURCE_ROOT"


def resource_root(environ: Optional[Mapping[str, str]] = None) -> Path:
    """Return staged AppImage resources, or source assets for local development.

    The AppImage launcher supplies ``GLASS_TV_RESOURCE_ROOT``. Keeping that
    bundle-specific concern out of the application model leaves source runs and
    core tests independent of packaging.
    """

    environment = os.environ if environ is None else environ
    configured_root = environment.get(RESOURCE_ROOT_ENVIRONMENT_VARIABLE)
    if configured_root:
        return Path(configured_root).expanduser().resolve()
    return Path(__file__).resolve().parents[2] / "shared"


def asset_path(relative_name: str, environ: Optional[Mapping[str, str]] = None) -> Path:
    """Resolve one bundle asset without allowing a path to escape its root."""

    relative_path = Path(relative_name)
    if relative_path.is_absolute() or ".." in relative_path.parts:
        raise ValueError("Asset paths must be relative to the resource root")
    return resource_root(environ) / relative_path
