#!/usr/bin/env python3
"""One source version; validated build identities shared by Java, ZIPs, feeds and CI."""
import argparse
import datetime as dt
import json
import os
from pathlib import Path
import re
import subprocess

CORE = r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)"


def git(*args, fallback="unknown"):
    try:
        return subprocess.check_output(["git", *args], text=True, stderr=subprocess.DEVNULL).strip()
    except (OSError, subprocess.CalledProcessError):
        return fallback


def identity(base, env):
    if not re.fullmatch(CORE, base):
        raise ValueError("Main.VERSION must contain a canonical MAJOR.MINOR.PATCH")
    ref = env.get("GITHUB_REF", "")
    tag = ref.removeprefix("refs/tags/") if ref.startswith("refs/tags/") else ""
    channel = env.get("WIIUU_BUILD_CHANNEL", "auto")
    release_branch = ref.removeprefix("refs/heads/release/") if ref.startswith("refs/heads/release/") else ""
    if release_branch and release_branch != base:
        raise ValueError(f"Release branch {release_branch!r} does not match target {base}")
    if channel == "auto":
        channel = "stable" if release_branch or tag and "-" not in tag else "beta"
    if channel == "release":
        channel = "stable"  # compatibility for existing build scripts
    if channel not in ("stable", "beta"):
        raise ValueError("WIIUU_BUILD_CHANNEL must be auto, stable or beta (release is an alias)")
    number = env.get("WIIUU_BUILD_NUMBER", env.get("GITHUB_RUN_NUMBER", "0"))
    if not re.fullmatch(r"0|[1-9][0-9]*", number):
        raise ValueError("Build number must be a nonnegative integer without leading zeroes")
    attempt = env.get("GITHUB_RUN_ATTEMPT", "")
    if attempt and not re.fullmatch(r"[1-9][0-9]*", attempt):
        raise ValueError("Invalid run attempt")
    if tag:
        expected = rf"v{re.escape(base)}" + (r"-beta\.(0|[1-9][0-9]*)(?:\.([1-9][0-9]*))?" if channel == "beta" else "")
        match = re.fullmatch(expected, tag)
        if not match:
            raise ValueError(f"Tag {tag!r} does not match release target/channel ({base}, {channel})")
        if channel == "beta":
            number = match.group(1)
            attempt = match.group(2) or ""
    release = base if channel == "stable" else f"{base}-beta.{number}" + (f".{attempt}" if attempt else "")
    commit = env.get("GITHUB_SHA") or git("rev-parse", "HEAD")
    if not re.fullmatch(r"[0-9a-fA-F]{7,64}|unknown", commit):
        raise ValueError("Invalid commit identity")
    short = commit[:12]
    dirty = not env.get("GITHUB_SHA") and bool(git("status", "--porcelain", "--untracked-files=no", fallback=""))
    version = release if channel == "stable" else f"{release}+g{short}" + (".dirty" if dirty else "")
    epoch = env.get("SOURCE_DATE_EPOCH") or git("show", "-s", "--format=%ct", fallback="0")
    built_at = dt.datetime.fromtimestamp(int(epoch), dt.timezone.utc).isoformat().replace("+00:00", "Z")
    branch = env.get("GITHUB_HEAD_REF") or env.get("GITHUB_REF_NAME") or git("branch", "--show-current")
    return dict(version=version, releaseVersion=release, baseVersion=base, channel=channel,
                build=number, attempt=attempt, dirty="true" if dirty else "false", commit=short, builtAt=built_at, branch=branch)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", default="build")
    parser.add_argument("--base", default="src/wiiuu/Main.java")
    args = parser.parse_args()
    source = Path(args.base).read_text().strip()
    if args.base.endswith('.java'):
        matches = re.findall(r'^\s*public static final String VERSION = "([^\"]+)";\s*$', source, re.MULTILINE)
        if len(matches) != 1:
            raise ValueError("Expected exactly one Main.VERSION release target")
        source = matches[0]
    data = identity(source, os.environ)
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    (out / "version.json").write_text(json.dumps(data, indent=2) + "\n")
    def escape(value):
        return value.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")
    (out / "wiiuu-build.properties").write_text("".join(f"{k}={escape(v)}\n" for k, v in data.items()))
    print(data["version"])


if __name__ == "__main__":
    main()
