#!/usr/bin/env python3
"""Package the pinned native sources alongside release APKs (Python 3 stdlib only)."""
import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import hashlib
import json
from pathlib import Path
import tarfile
import urllib.request
import zipfile


ROOT = Path(__file__).resolve().parents[1]
LOCK = ROOT / "playback/mpv/native-sources.json"


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def download(source, cache):
    path = cache / source["archive"]
    expected = source.get("sha256")
    if not path.is_file() or (expected and digest(path) != expected):
        temporary = path.with_suffix(path.suffix + ".part")
        request = urllib.request.Request(source["url"], headers={"User-Agent": "KPlayer-source-packager"})
        try:
            with urllib.request.urlopen(request, timeout=60) as response, temporary.open("wb") as output:
                while chunk := response.read(1024 * 1024):
                    output.write(chunk)
            with tarfile.open(temporary, "r:*") as archive:
                if not archive.getmembers():
                    raise ValueError("Empty source archive")
            actual = digest(temporary)
            if expected and actual != expected:
                raise ValueError(f"Checksum mismatch for {source['name']}")
            temporary.replace(path)
        finally:
            temporary.unlink(missing_ok=True)
    return source, digest(path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--cache", type=Path, default=ROOT / ".gradle/native-sources")
    parser.add_argument("--record-hashes", action="store_true", help="Maintainer-only: record verified upstream archive hashes")
    args = parser.parse_args()
    manifest = json.loads(LOCK.read_text(encoding="utf-8"))
    if not args.record_hashes and any(not source.get("sha256") for source in manifest["sources"]):
        parser.error("Every source must have a pinned SHA-256")
    args.cache.mkdir(parents=True, exist_ok=True)
    with ThreadPoolExecutor(max_workers=4) as workers:
        jobs = [workers.submit(download, source, args.cache) for source in manifest["sources"]]
        for job in as_completed(jobs):
            source, actual = job.result()
            source["sha256"] = actual
            print(f"{source['name']}: verified {actual}", flush=True)
    if args.record_hashes:
        LOCK.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    temporary = args.output.with_suffix(".part")
    try:
        with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_STORED) as bundle:
            for source in manifest["sources"]:
                bundle.write(args.cache / source["archive"], "archives/" + source["archive"])
            bundle.write(LOCK, "native-sources.json")
            bundle.write(ROOT / "playback/mpv/THIRD_PARTY_NOTICES.md", "THIRD_PARTY_NOTICES.md")
            bundle.write(ROOT / "playback/mpv/native-dependencies.properties", "native-dependencies.properties")
            bundle.write(ROOT / "LICENSE", "LICENSE")
            bundle.write(ROOT / "SOURCES.md", "README.md")
            for path in sorted((ROOT / "playback/mpv/src/main/assets/mpv/licenses").iterdir()):
                if path.is_file():
                    bundle.write(path, "licenses/" + path.name)
            bundle.writestr("SHA256SUMS", "".join(f"{s['sha256']}  archives/{s['archive']}\n" for s in manifest["sources"]))
        temporary.replace(args.output)
    finally:
        temporary.unlink(missing_ok=True)
    print(f"Bundle: {args.output} ({args.output.stat().st_size} bytes)", flush=True)
    print(f"SHA-256: {digest(args.output)}", flush=True)


if __name__ == "__main__":
    main()
