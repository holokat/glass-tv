#!/usr/bin/env python3
"""Verify a Glass TV release and optionally install it on one explicit TV."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True, help='Exact authorized TV serial from adb devices')
    parser.add_argument('--adb', default='adb', help='Path to installed Android platform-tools adb')
    parser.add_argument('--release-dir', type=Path, default=Path(__file__).resolve().parent)
    parser.add_argument('--install', action='store_true', help='Install after checks; otherwise read-only')
    parser.add_argument('--launch', action='store_true', help='Open Glass TV after installation')
    args = parser.parse_args()
    if args.launch and not args.install:
        parser.error('--launch requires --install')
    if not args.serial.strip() or args.serial.startswith('-'):
        parser.error('Supply an explicit device serial')
    directory = args.release_dir.resolve()
    release = json.loads((directory / 'release.json').read_text())
    if release['applicationId'] != 'xyz.glasstv.launcher':
        raise ValueError('Unexpected application ID')
    name = release['apk']['file']
    if Path(name).name != name or not name.endswith('.apk'):
        raise ValueError('Invalid APK filename in release metadata')
    apk = directory / name
    digest = hashlib.sha256()
    with apk.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    if digest.hexdigest() != release['apk']['sha256']:
        raise ValueError('APK checksum mismatch; installation refused')
    adb = shutil.which(args.adb)
    if not adb:
        raise ValueError('adb is unavailable. Install official Android platform-tools or supply --adb.')

    def run(*command, timeout=30):
        result = subprocess.run([adb, '-s', args.serial, *command],
                                capture_output=True, text=True, timeout=timeout)
        if result.returncode:
            raise RuntimeError(result.stderr.strip() or result.stdout.strip() or 'adb failed')
        return result.stdout.strip()

    if run('get-state') != 'device':
        raise ValueError('Selected TV is unavailable or unauthorized')
    sdk = int(run('shell', 'getprop', 'ro.build.version.sdk'))
    if sdk < int(release['minSdk']):
        raise ValueError(f'This release requires Android API {release["minSdk"]} or later')
    features = run('shell', 'pm', 'list', 'features').splitlines()
    if not any(line.strip() == 'feature:android.software.leanback' for line in features):
        raise ValueError('Selected device does not report Android TV support')
    model = run('shell', 'getprop', 'ro.product.model')
    print(f'Verified Glass TV {release["versionName"]} for {model} (API {sdk}).')
    print(f'APK SHA-256: {digest.hexdigest()}')
    if not args.install:
        print('Read-only check complete. Add --install to install on this selected TV.')
        return
    result = run('install', '-r', str(apk), timeout=180)
    if 'Success' not in result.splitlines():
        raise RuntimeError(f'Installation did not report success: {result}')
    print('Installed. Default launcher and existing apps were not changed.')
    if args.launch:
        run('shell', 'am', 'start', '-n',
            'xyz.glasstv.launcher/local.glasstv.launcher.MainActivity')
        print('Opened Glass TV.')


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError, TypeError, RuntimeError, subprocess.TimeoutExpired) as error:
        print(f'Error: {error}', file=sys.stderr)
        sys.exit(1)
