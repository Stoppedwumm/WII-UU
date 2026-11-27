#!/usr/bin/env python3
"""Bundle the pinned Maven RTC jars without flattening third-party metadata."""
import hashlib
import pathlib
import shutil
import sys
import zipfile

VERSION = "1.0.1"
PLATFORMS = ("linux-x86_64", "linux-aarch64", "macos-x86_64",
             "macos-aarch64", "windows-x86_64")
PLAYER = "java-rtc.jar"
COMMON_REQUIRED = (PLAYER, f"webrtc-java-{VERSION}.jar",
                   f"webrtc-java-swing-{VERSION}.jar", "gson-2.13.2.jar")


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest() if hasattr(hashlib, "file_digest") else hashlib.sha256(stream.read()).hexdigest()


def native_name(platform):
    ext = ".dll" if platform.startswith("windows") else ".dylib" if platform.startswith("macos") else ".so"
    prefix = "" if ext == ".dll" else "lib"
    return f"{prefix}webrtc-java-{platform}{ext}"


def package(target, output):
    jars = {p.name: p for p in (target / "dependency").glob("*.jar")}
    jars[PLAYER] = target / PLAYER
    native = {f"webrtc-java-{VERSION}-{p}.jar": p for p in PLATFORMS}
    for name in (*COMMON_REQUIRED, *native):
        if name not in jars or not jars[name].is_file():
            raise RuntimeError("Missing RTC Maven artifact: " + name)
    output.mkdir(parents=True, exist_ok=False)
    records = []
    for name, path in sorted(jars.items()):
        platform = native.get(name)
        if platform is None and name.startswith(f"webrtc-java-{VERSION}-"):
            continue  # do not include host-only classifiers outside the supported matrix
        with zipfile.ZipFile(path) as z:
            if platform and native_name(platform) not in z.namelist():
                raise RuntimeError("Missing native library in " + name)
            if name == PLAYER and "wiiuu/xcloud/NativeRtcPlayer.class" not in z.namelist():
                raise RuntimeError("Missing Java RTC player class")
        shutil.copyfile(path, output / name)
        records.append(f"{platform or 'common'} {digest(path)} {name}")
    (output / "index.txt").write_text("\n".join(records) + "\n", encoding="utf-8")
    print(f"Bundled Java RTC ({len(records)} jars, {sum(p.stat().st_size for p in output.iterdir()) / (1024**2):.1f} MiB)")


def verify(app):
    with zipfile.ZipFile(app) as z:
        prefix = "xcloud/java-rtc/"
        rows = [line.split() for line in z.read(prefix + "index.txt").decode().splitlines()]
        names = set()
        platforms = set()
        for platform, sha, name in rows:
            if name in names:
                raise RuntimeError("Duplicate RTC jar " + name)
            names.add(name)
            if platform != "common":
                platforms.add(platform)
            with z.open(prefix + name) as stream:
                h = hashlib.sha256()
                for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                    h.update(chunk)
            if h.hexdigest() != sha:
                raise RuntimeError("RTC artifact checksum mismatch: " + name)
        if set(COMMON_REQUIRED) - names or platforms != set(PLATFORMS):
            raise RuntimeError("Incomplete RTC bundle")
    print("Verified bundled Java RTC module and all five platform libraries in " + str(app))


if __name__ == "__main__":
    if len(sys.argv) == 3 and sys.argv[1] == "--verify":
        verify(pathlib.Path(sys.argv[2]))
    elif len(sys.argv) == 3:
        package(pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2]))
    else:
        raise SystemExit("usage: package-java-rtc.py TARGET OUTPUT | --verify APP.jar")
