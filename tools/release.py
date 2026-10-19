#!/usr/bin/env python3
"""Prepare and verify signed GitHub releases without printing signing secrets."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
CERTIFICATE_SHA256 = "83aeeb18e7986ca34bad312dc1c0374235002cb30974cc7a8b50fa0d9b550f44"
VERSION_PATTERN = r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)"


def read_version(text):
    version = re.search(r'\bversionName\s*=\s*"([^"]+)"', text)
    code = re.search(r'\bversionCode\s*=\s*([0-9]+)\b', text)
    if not version or not re.fullmatch(VERSION_PATTERN, version[1]) or not code:
        raise ValueError("Expected a stable x.y.z versionName and integer versionCode")
    return version[1], int(code[1])


def metadata(root=ROOT):
    version, code = read_version((root / "app/build.gradle.kts").read_text(encoding="utf-8"))
    manifest = json.loads((root / "playback/mpv/native-sources.json").read_text(encoding="utf-8"))
    if manifest["release"] != version:
        raise ValueError("native-sources.json release must match versionName")
    if not (root / f"docs/releases/v{version}.md").is_file():
        raise ValueError("Release notes for this version are missing")
    if f"v{version}" not in (root / "SOURCES.md").read_text(encoding="utf-8"):
        raise ValueError("SOURCES.md must reference the current source tag")
    return version, code


def check_new_version(version, code, previous):
    current = tuple(map(int, version.split(".")))
    for old_version, old_code in previous:
        if current <= tuple(map(int, old_version.split("."))) or code <= old_code:
            raise ValueError("A new release must increase both versionName and versionCode")


def prepare(publish, output):
    version, code = metadata()
    if publish:
        previous = []
        tags = subprocess.check_output(["git", "tag", "--list", "v*"], cwd=ROOT, text=True)
        for tag in tags.splitlines():
            if not re.fullmatch("v" + VERSION_PATTERN, tag) or tag == "v" + version:
                continue
            text = subprocess.check_output(["git", "show", f"{tag}:app/build.gradle.kts"], cwd=ROOT, text=True, encoding="utf-8")
            previous.append(read_version(text))
        check_new_version(version, code, previous)
    values = {"version": version, "version_code": code, "tag": "v" + version,
              "directory": f"dist/{version}", "artifact": f"k-player-{version}-signed"}
    if output:
        with Path(output).open("a", encoding="utf-8") as stream:
            stream.write("".join(f"{key}={value}\n" for key, value in values.items()))
    print(json.dumps(values))


def restore_sources(directory):
    manifest = json.loads((ROOT / "playback/mpv/native-sources.json").read_text(encoding="utf-8"))
    cache = ROOT / ".gradle/native-sources"
    cache.mkdir(parents=True, exist_ok=True)
    restored = 0
    for path in Path(directory).glob("*-native-sources.zip"):
        with zipfile.ZipFile(path) as bundle:
            for source in manifest["sources"]:
                name = source["archive"]
                if Path(name).name != name:
                    raise ValueError("Invalid source archive name")
                try:
                    data = bundle.read("archives/" + name)
                except KeyError:
                    continue
                if hashlib.sha256(data).hexdigest() == source["sha256"]:
                    (cache / name).write_bytes(data)
                    restored += 1
    print(f"Restored {restored} verified source archives; missing sources use their pinned upstream URLs")


def verify_badging(badging, version, code):
    lines = badging.splitlines()
    package = next((line for line in lines if line.startswith("package:")), "")
    required = ["name='dev.davplayer.app'", f"versionName='{version}'", f"versionCode='{code}'"]
    if not all(value in package for value in required):
        raise ValueError("APK package or version does not match the source")
    if "application-debuggable" in badging:
        raise ValueError("Refusing to release a debuggable APK")
    if "native-code: 'arm64-v8a'" not in lines or "sdkVersion:'26'" not in lines:
        raise ValueError("Expected ARM64-only APK with minimum Android SDK 26")


def verify_signature(signature):
    digest = re.search(r"certificate SHA-256 digest: ([0-9a-f]{64})", signature)
    if not digest or digest[1] != CERTIFICATE_SHA256 or "Number of signers: 1" not in signature.splitlines():
        raise ValueError("Signing certificate differs from the fixed 0.3.1 release certificate")
    if "Verified using v2 scheme (APK Signature Scheme v2): true" not in signature:
        raise ValueError("APK Signature Scheme v2 verification failed")
    if "Verified using v3 scheme (APK Signature Scheme v3): true" not in signature:
        raise ValueError("APK Signature Scheme v3 verification failed")


def test_results(root=ROOT):
    counts = {}
    for module in ["app", "playback/mpv"]:
        suites = [ET.parse(path).getroot() for path in
                  (root / module / "build/test-results/testReleaseUnitTest").glob("TEST-*.xml")]
        if not suites or any(int(s.attrib.get(k, "0")) for s in suites for k in ["errors", "failures", "skipped"]):
            raise ValueError("Release unit tests are missing, failed or skipped")
        counts[module] = sum(int(s.attrib["tests"]) for s in suites)
        if counts[module] == 0:
            raise ValueError("Release test suite is empty")
    lint = ET.parse(root / "app/build/reports/lint-results-release.xml").getroot()
    if any(issue.attrib.get("severity") in ["Error", "Fatal"] for issue in lint.findall("issue")):
        raise ValueError("Release Lint contains errors")
    return counts, len(lint.findall("issue"))


def sign():
    version, code = metadata()
    sdk = Path(os.environ.get("ANDROID_HOME") or os.environ["ANDROID_SDK_ROOT"])
    build_tools = sdk / "build-tools/35.0.0"
    java = Path(os.environ["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java")
    apksigner = [str(java), "-jar", str(build_tools / "lib/apksigner.jar")]
    output = ROOT / f"dist/{version}"
    output.mkdir(parents=True, exist_ok=True)
    apk = output / f"k-player-{version}-arm64.apk"
    counts, warnings = test_results()
    try:
        settings = json.loads(os.environ["KPLAYER_RELEASE_SIGNING"])
        key_bytes = base64.b64decode(settings["keystore_base64"], validate=True)
        env = os.environ.copy()
        env.pop("KPLAYER_RELEASE_SIGNING", None)
        env["KPLAYER_STORE_PASSWORD"] = settings["store_password"]
        env["KPLAYER_KEY_PASSWORD"] = settings["key_password"]
        alias = settings["key_alias"]
        if not key_bytes or not alias or not env["KPLAYER_STORE_PASSWORD"] or not env["KPLAYER_KEY_PASSWORD"]:
            raise ValueError
    except (KeyError, ValueError, TypeError):
        raise ValueError("KPLAYER_RELEASE_SIGNING is missing or invalid") from None
    with tempfile.TemporaryDirectory(prefix="kplayer-signing-", dir=os.environ.get("RUNNER_TEMP")) as private:
        keystore = Path(private) / "release.jks"
        keystore.write_bytes(key_bytes)
        keystore.chmod(0o600)
        command = apksigner + ["sign", "--ks", str(keystore), "--ks-key-alias", alias,
                               "--ks-pass", "env:KPLAYER_STORE_PASSWORD", "--key-pass", "env:KPLAYER_KEY_PASSWORD",
                               "--v1-signing-enabled", "false", "--v2-signing-enabled", "true",
                               "--v3-signing-enabled", "true", "--v4-signing-enabled", "false",
                               "--out", str(apk), str(ROOT / "app/build/outputs/apk/release/app-release-unsigned.apk")]
        result = subprocess.run(command, env=env, capture_output=True)
        if result.returncode:
            raise ValueError("APK signing failed; check the configured release key and passwords")
    signature = subprocess.check_output(apksigner + ["verify", "--verbose", "--print-certs", str(apk)], text=True)
    verify_signature(signature)
    aapt = build_tools / ("aapt.exe" if os.name == "nt" else "aapt")
    badging = subprocess.check_output([str(aapt), "dump", "badging", str(apk)], text=True)
    verify_badging(badging, version, code)
    align = build_tools / ("zipalign.exe" if os.name == "nt" else "zipalign")
    subprocess.run([str(align), "-c", "-P", "16", "4", str(apk)], check=True)
    with zipfile.ZipFile(apk) as bundle:
        if {name.split("/")[1] for name in bundle.namelist() if name.startswith("lib/")} != {"arm64-v8a"}:
            raise ValueError("Unexpected APK native library architecture")
    (output / "APK-SIGNATURE.txt").write_text(signature, encoding="utf-8", newline="\n")
    report = [f"K Player {version} Release, versionCode {code}", "debuggable=false; ABI=arm64-v8a; minSdk=26",
              f"certificate SHA-256: {CERTIFICATE_SHA256}", "APK signature v2/v3 and 16 KB ZIP alignment: passed",
              f"Release unit tests: {json.dumps(counts)}; no failures/errors/skips", f"Release Lint: 0 errors, {warnings} warnings",
              "Device/emulator tests were not run by this workflow."]
    if os.environ.get("GITHUB_RUN_ID"):
        report.append(f"Workflow: {os.environ['GITHUB_SERVER_URL']}/{os.environ['GITHUB_REPOSITORY']}/actions/runs/{os.environ['GITHUB_RUN_ID']}")
    (output / "RELEASE-VERIFICATION.txt").write_text("\n".join(report) + "\n", encoding="utf-8")
    print("\n".join(report))


def checksums():
    version, _ = metadata()
    output = ROOT / f"dist/{version}"
    names = [f"k-player-{version}-arm64.apk", f"k-player-{version}-native-sources.zip",
             "APK-SIGNATURE.txt", "RELEASE-VERIFICATION.txt"]
    with zipfile.ZipFile(output / names[1]) as bundle:
        manifest = json.loads(bundle.read("native-sources.json"))
        if manifest["release"] != version or bundle.testzip() is not None:
            raise ValueError("Invalid corresponding source bundle")
        for source in manifest["sources"]:
            if hashlib.sha256(bundle.read("archives/" + source["archive"])).hexdigest() != source["sha256"]:
                raise ValueError("Source bundle checksum mismatch")
    lines = []
    for name in names:
        with (output / name).open("rb") as stream:
            lines.append(f"{hashlib.file_digest(stream, 'sha256').hexdigest()}  {name}\n")
    (output / "SHA256SUMS.txt").write_text("".join(lines), encoding="ascii")
    print("".join(lines), end="")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    metadata_parser = sub.add_parser("prepare")
    metadata_parser.add_argument("--publish", action="store_true")
    metadata_parser.add_argument("--output")
    restore = sub.add_parser("restore-sources")
    restore.add_argument("directory")
    sub.add_parser("sign")
    sub.add_parser("checksums")
    args = parser.parse_args()
    if args.command == "prepare":
        prepare(args.publish, args.output)
    elif args.command == "restore-sources":
        restore_sources(args.directory)
    elif args.command == "sign":
        sign()
    elif args.command == "checksums":
        checksums()


if __name__ == "__main__":
    try:
        main()
    except (ValueError, subprocess.CalledProcessError) as error:
        raise SystemExit(str(error)) from None
