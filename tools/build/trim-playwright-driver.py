#!/usr/bin/env python3
"""Strip the platform payloads this build will never run from Playwright's driver bundle.

WHY. com.microsoft.playwright:driver-bundle ships FIVE complete Node+Playwright payloads - linux,
linux-arm64, mac, mac-arm64, win32_x64 - because one artifact has to serve every consumer. A
Proxyma desktop build runs on exactly one of them: the Windows installer is built on a Windows
runner for Windows, the .deb on a Linux runner for Linux. The other four are 174 MB of an
installer the user downloads and never executes.

WHY IT IS DONE HERE AND NOT WITH A MAVEN EXCLUSION. Playwright publishes no per-platform artifact,
so there is nothing narrower to depend on. The bundle has to be rewritten, and the only moment it
is both resolved and still ours to touch is after spring-boot:repackage has embedded it.

THE TRAP THIS SCRIPT EXISTS TO AVOID. Spring Boot requires nested jars under BOOT-INF/lib to be
STORED, not deflated - it memory-maps them in place. A naive rewrite that re-compresses everything
produces a jar that looks fine, is smaller, and cannot start. Every entry is therefore copied with
its ORIGINAL compress_type preserved.

AND THE ONE THIS ONE GUARDS. Playwright resolves its driver from driver/<platform>/ on the
classpath. Remove the wrong one and Playwright.create() throws "Failed to create driver" - which
PlaywrightRenderService catches, logs at WARN and falls back to HtmlUnit, so web fetch quietly
returns the pre-JavaScript page instead of failing. This script refuses to write an archive that
does not contain the platform it was told to keep, so that cannot leave the build.
"""
import argparse
import os
import shutil
import sys
import zipfile

# Playwright's own directory names inside the bundle.
PLATFORMS = ('linux', 'linux-arm64', 'mac', 'mac-arm64', 'win32_x64')


def trim_bundle(data: bytes, keep: str) -> bytes:
    """Return the driver-bundle bytes with every platform except `keep` removed."""
    import io
    zin = zipfile.ZipFile(io.BytesIO(data))
    out = io.BytesIO()
    kept_files = 0
    with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as zout:
        for info in zin.infolist():
            name = info.filename
            if name.startswith('driver/') and name != 'driver/':
                if not name.startswith(f'driver/{keep}/'):
                    continue
                kept_files += 1
            zout.writestr(info, zin.read(name))
    if kept_files == 0:
        raise SystemExit(f"refusing to write: no driver/{keep}/ entries found in the bundle. "
                         f"Platforms present: {sorted(_platforms_in(zin))}")
    return out.getvalue()


def _platforms_in(z: zipfile.ZipFile):
    found = set()
    for n in z.namelist():
        parts = n.split('/')
        if len(parts) > 2 and parts[0] == 'driver':
            found.add(parts[1])
    return found


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument('jar', help='the repackaged Spring Boot jar to rewrite in place')
    ap.add_argument('--keep', required=True, choices=PLATFORMS,
                    help="platform payload to keep, e.g. win32_x64")
    args = ap.parse_args()

    if not os.path.isfile(args.jar):
        print(f"[trim-driver] {args.jar} not found", file=sys.stderr)
        return 1

    before = os.path.getsize(args.jar)
    tmp = args.jar + '.trimmed'
    replaced = False

    with zipfile.ZipFile(args.jar) as zin, zipfile.ZipFile(tmp, 'w') as zout:
        for info in zin.infolist():
            data = zin.read(info.filename)
            if os.path.basename(info.filename).startswith('driver-bundle-') \
                    and info.filename.startswith('BOOT-INF/lib/'):
                data = trim_bundle(data, args.keep)
                replaced = True
            # compress_type is copied from the source entry, NOT chosen here: Spring Boot
            # memory-maps nested jars and they must stay STORED.
            new = zipfile.ZipInfo(info.filename, date_time=info.date_time)
            new.compress_type = info.compress_type
            new.external_attr = info.external_attr
            new.internal_attr = info.internal_attr
            new.create_system = info.create_system
            zout.writestr(new, data)

    if not replaced:
        os.remove(tmp)
        print(f"[trim-driver] no driver-bundle found inside {args.jar} - nothing to do")
        return 0

    shutil.move(tmp, args.jar)
    after = os.path.getsize(args.jar)
    print(f"[trim-driver] kept driver/{args.keep} only: "
          f"{before/1e6:.0f} MB -> {after/1e6:.0f} MB (saved {(before-after)/1e6:.0f} MB)")
    return 0


if __name__ == '__main__':
    sys.exit(main())
