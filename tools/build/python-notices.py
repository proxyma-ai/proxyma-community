#!/usr/bin/env python3
"""
python-notices.py - the Python half of THIRD-PARTY-NOTICES.txt (apps/app/third-party/notices.xml).

    python tools/build/python-notices.py generate
        Resolves requirements.txt with pip (no install, metadata only) for Windows and for Linux,
        and rewrites apps/app/third-party/python-packages.txt. Needs the network (PyPI) and pip;
        run it after changing requirements.txt, and commit the result.

    python tools/build/python-notices.py check
        Offline. Fails when requirements.txt has changed since python-packages.txt was generated
        - the list records the file's SHA-256. Runs in the pipeline's test:checks job.

    python tools/build/python-notices.py wheels <wheels-dir> <out-file>
        Reads the METADATA of every wheel (and PKG-INFO of any sdist) in <wheels-dir>, writes the
        EXACT list for that python part to <out-file>, and fails if a package there is missing
        from the committed list. Called by the Windows and Linux staging right after the wheels
        are downloaded.

WHY A COMMITTED LIST AND NOT ONLY THE WHEELS. The application jar carries THIRD-PARTY-NOTICES.txt,
and the jar is built before - and in a plain build entirely without - the wheels being downloaded.
So the jar's copy comes from this committed file, resolved from the same requirements.txt for
the same Python minor (3.13) on both platforms. requirements.txt is not pinned, so the versions a
release downloads can be newer than the ones recorded here: the `wheels` step writes the exact
list into the python part itself (wheels/THIRD-PARTY-NOTICES-python.txt), and fails the release
if a package appears that the committed list does not name at all - the moment to regenerate.
It is written INSIDE wheels/ because tools/release/parts.py names the python part by everything
except wheels/: a file derived from the wheels anywhere else would give the part a new name on
every build, and every installation would rebuild its Python runtime on every update.

A plainly GPL or AGPL package (no exception, not LGPL) fails `generate` and `wheels`: shipping one
is a decision, not something a dependency bump should do quietly.

stdlib only, and the same on Windows and on the Linux build host.
"""
import argparse
import datetime
import email.parser
import hashlib
import json
import os
import re
import subprocess
import sys
import tarfile
import tempfile
import zipfile

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
REQUIREMENTS = os.path.join(ROOT, "apps", "app", "backend", "proxyma-desktop", "src", "assembly", "setup", "requirements.txt")
LIST = os.path.join(ROOT, "apps", "app", "third-party", "python-packages.txt")
PYTHON_VERSION = "3.13"
# The platforms the python parts are built for. Linux is every manylinux tag the build host
# (Ubuntu 24.04, glibc 2.39) accepts, spelled out: pip does NOT expand a --platform tag to the
# older ones, so `manylinux_2_39_x86_64` alone found no pypdfium2 at all (its wheel is 2_17).
PLATFORMS = {
    "Windows": ["win_amd64"],
    "Linux": [f"manylinux_2_{minor}_x86_64" for minor in range(39, 4, -1)]
    + ["manylinux2014_x86_64", "manylinux2010_x86_64", "manylinux1_x86_64", "linux_x86_64"],
}
# --platform chooses WHEELS, never MARKERS: pip evaluates `; sys_platform == "win32"` against the
# machine it runs on, so a Linux resolution made on Windows still pulls in colorama and
# pyreadline3 and misses whatever is Linux-only. pip has no flag for it, so pip is run with its
# vendored packaging's default_environment() replaced by the target's - and the report's own
# `environment` field, written from that same function, is checked to prove it took.
MARKERS = {
    "Windows": {"os_name": "nt", "sys_platform": "win32", "platform_system": "Windows",
                "platform_machine": "AMD64", "platform_release": "10", "platform_version": "10.0.19045"},
    "Linux": {"os_name": "posix", "sys_platform": "linux", "platform_system": "Linux",
              "platform_machine": "x86_64", "platform_release": "6.8.0", "platform_version": "#1 SMP"},
}
PIP_WITH_MARKERS = (
    "import json, sys\n"
    "import pip._vendor.packaging.markers as markers\n"
    "env = markers.default_environment()\n"
    "env.update(json.loads(sys.argv[1]))\n"
    "markers.default_environment = lambda: dict(env)\n"
    "from pip._internal.cli.main import main\n"
    "sys.exit(main(sys.argv[2:]))\n"
)
SEPARATOR = "-" * 70
SHA_PREFIX = "requirements.txt SHA-256: "


def die(msg):
    print(f"[python-notices] {msg}", file=sys.stderr)
    sys.exit(1)


def canonical(name):
    return re.sub(r"[-_.]+", "-", name).lower()


def ascii_text(s):
    return s.encode("ascii", "replace").decode("ascii") if s else s


def sha256(path):
    """Of the text with LF line ends, so a CRLF checkout and the LF build host agree."""
    with open(path, "rb") as f:
        return hashlib.sha256(f.read().replace(b"\r\n", b"\n")).hexdigest()


# --- one package's facts, from core metadata however it arrived ------------------------------------

VAGUE = {"", "unknown", "dual license", "other/proprietary license", "license.txt", "see license"}


def license_of(expression, license_field, classifiers):
    """SPDX expression first; then the License field when it is a name rather than a pasted text;
    then the trove classifiers. Never guessed."""
    if expression:
        return expression.strip()
    field = (license_field or "").strip()
    named = [c.split("::")[-1].strip() for c in classifiers if c.startswith("License ::")]
    if field and "\n" not in field and len(field) <= 80 and field.lower() not in VAGUE:
        return field
    if named:
        return " / ".join(named)
    if field:
        return "stated in the package's own license file"
    return "not stated in the package metadata - see the package's license file"


def url_of(home_page, project_urls):
    if home_page and home_page.strip() and home_page.strip().upper() != "UNKNOWN":
        return home_page.strip()
    labelled = {}
    for entry in project_urls:
        label, _, url = entry.partition(",")
        labelled[label.strip().lower()] = url.strip()
    for key in ("homepage", "home", "source", "source code", "repository", "github", "documentation"):
        if labelled.get(key):
            return labelled[key]
    return next(iter(labelled.values()), None)


def is_plain_gpl(lic):
    """True for a license that is GPL or AGPL with no exception, on every alternative of an OR."""
    alternatives = re.split(r"\s+OR\s+|\s+/\s+", lic.replace("(", " ").replace(")", " "), flags=re.I)

    def bad(alt):
        if re.search(r"\bWITH\b|exception", alt, re.I):
            return False
        if re.search(r"Lesser|Library|LGPL", alt, re.I):
            return False
        return bool(re.search(r"\bA?GPL|General Public License", alt, re.I))

    return all(bad(a) for a in alternatives if a.strip())


def from_report_metadata(m):
    return {
        "name": m["name"],
        "version": m["version"],
        "license": license_of(m.get("license_expression"), m.get("license"), m.get("classifier", [])),
        "url": url_of(m.get("home_page"), m.get("project_url", [])),
    }


def from_metadata_text(text):
    msg = email.parser.Parser().parsestr(text, headersonly=True)
    return {
        "name": msg["Name"],
        "version": msg["Version"],
        "license": license_of(msg.get("License-Expression"), msg.get("License"), msg.get_all("Classifier") or []),
        "url": url_of(msg.get("Home-page"), msg.get_all("Project-URL") or []),
    }


# --- the list file ----------------------------------------------------------------------------------

def render(title, intro, packages):
    """packages: name -> {name, version, license, url, only}. Sorted by name, case-insensitively."""
    lines = [title, "=" * len(title), ""] + intro + ["", SEPARATOR, ""]
    for key in sorted(packages, key=str.lower):
        p = packages[key]
        head = f"{p['name']} {p['version']}"
        if p.get("only"):
            head += f" ({p['only']} only)"
        lines.append(ascii_text(head))
        if p.get("url"):
            lines.append("  " + ascii_text(p["url"]))
        lines.append("  License: " + ascii_text(p["license"]))
        lines.append("")
    return "\n".join(lines)


def listed_names(path):
    """The canonical names a list file names - every unindented line after the separator."""
    names, after = set(), False
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if line == SEPARATOR:
                after = True
            elif after and line and not line.startswith(" "):
                names.add(canonical(line.split(" ", 1)[0]))
    return names


def refuse_gpl(packages):
    bad = [p for p in packages.values() if is_plain_gpl(p["license"])]
    if bad:
        for p in bad:
            print(f"  GPL without an exception: {p['name']} {p['version']} - {p['license']}", file=sys.stderr)
        die("a GPL or AGPL package would ship in the python part; decide that deliberately, not here")


# --- commands ---------------------------------------------------------------------------------------

def resolve(label):
    markers = dict(MARKERS[label], python_version=PYTHON_VERSION)
    with tempfile.TemporaryDirectory() as tmp:
        report = os.path.join(tmp, "report.json")
        # --platform needs --only-binary and --target; nothing is written to the target, since
        # --dry-run only resolves (and fetches metadata, not wheels, wherever PyPI serves it).
        cmd = [sys.executable, "-c", PIP_WITH_MARKERS, json.dumps(markers),
               "install", "--dry-run", "--ignore-installed", "--quiet",
               "--disable-pip-version-check", "--report", report, "--only-binary=:all:",
               "--python-version", PYTHON_VERSION, "--implementation", "cp",
               "--target", os.path.join(tmp, "target"), "-r", REQUIREMENTS]
        for tag in PLATFORMS[label]:
            cmd += ["--platform", tag]
        subprocess.run(cmd, check=True)
        with open(report, encoding="utf-8") as f:
            data = json.load(f)
        seen = data.get("environment", {})
        if any(seen.get(k) != v for k, v in markers.items()):
            die(f"pip resolved {label} under the wrong environment markers ({seen.get('sys_platform')}) -"
                " has pip changed how it evaluates them? The list would be the host's, not the target's.")
        return {canonical(i["metadata"]["name"]): from_report_metadata(i["metadata"])
                for i in data["install"]}


def generate(_args):
    per_platform = {label: resolve(label) for label in PLATFORMS}
    merged = {}
    for label, packages in per_platform.items():
        for key, p in packages.items():
            if key in merged:
                if merged[key]["version"] != p["version"]:
                    merged[key]["version"] += f" (Windows) / {p['version']} ({label})"
                merged[key]["only"] = None
            else:
                merged[key] = dict(p, only=label)
    refuse_gpl(merged)
    intro = [
        f"Proxyma's Python runtime installs these {len(merged)} packages from PyPI, for its vector store",
        "(ChromaDB), document conversion (MarkItDown), PDF reading and OCR, and the Office Files",
        "tools. Each is listed with the version resolved when this list was made, its project page",
        "and its license as the package's own metadata states it. The full license text of each",
        "package is installed with it, in its .dist-info folder in Proxyma's Python runtime.",
        "",
        "requirements.txt is not pinned, so a release may carry a newer version than the one",
        "named here; the python part of each release carries the exact list for its own wheels,",
        "as wheels/THIRD-PARTY-NOTICES-python.txt.",
        "",
        "Generated by tools/build/python-notices.py from",
        "apps/app/backend/proxyma-desktop/src/assembly/setup/requirements.txt",
        f"for CPython {PYTHON_VERSION} on Windows (win_amd64) and Linux (manylinux x86_64), {datetime.date.today().isoformat()}.",
        SHA_PREFIX + sha256(REQUIREMENTS),
    ]
    with open(LIST, "w", encoding="ascii", newline="\n") as f:
        f.write(render("PYTHON PACKAGES", intro, merged))
    print(f"[python-notices] {len(merged)} packages -> {os.path.relpath(LIST, ROOT)}")


def check(_args):
    if not os.path.isfile(LIST):
        die(f"{LIST} is missing - run: python tools/build/python-notices.py generate")
    with open(LIST, encoding="utf-8") as f:
        recorded = next((l[len(SHA_PREFIX):].strip() for l in f if l.startswith(SHA_PREFIX)), None)
    actual = sha256(REQUIREMENTS)
    if recorded != actual:
        die("requirements.txt changed since apps/app/third-party/python-packages.txt was generated.\n"
            "  Regenerate it (needs the network): python tools/build/python-notices.py generate")
    print(f"[python-notices] python-packages.txt matches requirements.txt ({len(listed_names(LIST))} packages)")


def read_archive(path):
    if path.endswith(".whl"):
        with zipfile.ZipFile(path) as z:
            meta = [n for n in z.namelist() if re.fullmatch(r"[^/]+\.dist-info/METADATA", n)]
            return z.read(meta[0]).decode("utf-8", "replace") if meta else None
    if path.endswith((".tar.gz", ".tgz")):
        with tarfile.open(path) as t:
            for m in t.getmembers():
                if re.fullmatch(r"[^/]+/PKG-INFO", m.name):
                    return t.extractfile(m).read().decode("utf-8", "replace")
    if path.endswith(".zip"):
        with zipfile.ZipFile(path) as z:
            meta = [n for n in z.namelist() if re.fullmatch(r"[^/]+/PKG-INFO", n)]
            return z.read(meta[0]).decode("utf-8", "replace") if meta else None
    return None


def wheels(args):
    packages = {}
    for name in sorted(os.listdir(args.dir)):
        text = read_archive(os.path.join(args.dir, name))
        if text is None:
            if name.endswith((".whl", ".tar.gz", ".tgz", ".zip")):
                die(f"no METADATA or PKG-INFO in {name}")
            continue
        p = from_metadata_text(text)
        packages[canonical(p["name"])] = p
    if not packages:
        die(f"no wheels in {args.dir}")
    refuse_gpl(packages)
    intro = [
        f"The {len(packages)} Python packages in this python part, read from the wheels themselves -",
        "each with its version, project page and license as its own metadata states it. The full",
        "license text of each is installed with it, in its .dist-info folder in Proxyma's Python",
        "runtime. THIRD-PARTY-NOTICES.txt in the Proxyma installation folder covers the rest.",
    ]
    with open(args.out, "w", encoding="ascii", newline="\n") as f:
        f.write(render("PYTHON PACKAGES IN THIS PART", intro, packages))
    print(f"[python-notices] {len(packages)} packages -> {args.out}")
    missing = sorted(set(packages) - listed_names(LIST))
    if missing:
        die("these packages are in the wheels but not in apps/app/third-party/python-packages.txt,\n"
            "  so the notices inside the application would not name them: " + ", ".join(missing) + "\n"
            "  Regenerate the list (python tools/build/python-notices.py generate), commit it and rebuild.")


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("generate").set_defaults(run=generate)
    sub.add_parser("check").set_defaults(run=check)
    w = sub.add_parser("wheels")
    w.add_argument("dir")
    w.add_argument("out")
    w.set_defaults(run=wheels)
    args = parser.parse_args()
    args.run(args)


if __name__ == "__main__":
    main()
