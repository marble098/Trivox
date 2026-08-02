#!/usr/bin/env python3
import argparse
import datetime as dt
import hashlib
import json
import os
import shutil
import stat
import sys
import tempfile
import zipfile
from pathlib import Path, PurePosixPath

MAX_UNCOMPRESSED = 500 * 1024 * 1024
SUPPORTED_ABIS = {"arm64-v8a", "armeabi-v7a", "x86_64"}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def validate_entries(archive: zipfile.ZipFile) -> None:
    total = 0
    for info in archive.infolist():
        name = PurePosixPath(info.filename)
        if name.is_absolute() or ".." in name.parts or "\x00" in info.filename:
            raise ValueError(f"Unsafe ZIP entry: {info.filename}")
        total += info.file_size
        if total > MAX_UNCOMPRESSED:
            raise ValueError("Archive expands beyond the 500 MiB safety limit")
        mode = info.external_attr >> 16
        if stat.S_ISLNK(mode):
            raise ValueError(f"Symbolic links are not accepted: {info.filename}")


def extract_aar(source: Path, destination: Path) -> Path:
    if source.suffix.lower() == ".aar":
        shutil.copyfile(source, destination)
        return destination
    if not zipfile.is_zipfile(source):
        raise ValueError("Core artifact is neither an AAR nor a valid ZIP archive")
    with zipfile.ZipFile(source) as archive:
        validate_entries(archive)
        candidates = [item for item in archive.infolist() if not item.is_dir() and item.filename.lower().endswith(".aar")]
        if not candidates:
            names = [PurePosixPath(item.filename).name.lower() for item in archive.infolist()]
            if "xray" in names or "xray.exe" in names:
                raise ValueError(
                    "This archive contains a standalone Xray executable, not the official Android libXray AAR. "
                    "Use XTLS/libXray release asset libxray-android.zip; an executable cannot be safely renamed into jniLibs."
                )
            raise ValueError("No Android AAR was found. Required artifact: official XTLS/libXray libxray-android.zip")
        if len(candidates) != 1:
            raise ValueError("Archive contains multiple AAR files; select the official libXray Android artifact")
        destination.parent.mkdir(parents=True, exist_ok=True)
        with archive.open(candidates[0]) as source_stream, destination.open("wb") as output:
            shutil.copyfileobj(source_stream, output)
    return destination


def inspect_aar(aar: Path) -> list[str]:
    if not zipfile.is_zipfile(aar):
        raise ValueError("Extracted file is not a valid AAR")
    with zipfile.ZipFile(aar) as archive:
        validate_entries(archive)
        names = {item.filename for item in archive.infolist()}
        if "classes.jar" not in names:
            raise ValueError("AAR has no classes.jar")
        abis = sorted({PurePosixPath(name).parts[1] for name in names if name.startswith("jni/") and len(PurePosixPath(name).parts) >= 3 and name.endswith(".so")})
        if not abis:
            raise ValueError("AAR contains no Android JNI shared libraries")
        return abis


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Validate an official libXray Android artifact and generate Trivox core manifest")
    parser.add_argument("--artifact", type=Path)
    parser.add_argument("--aar-output", type=Path, default=Path("app/libs/libXray.aar"))
    parser.add_argument("--manifest-output", type=Path, default=Path("app/src/main/assets/core-manifest.json"))
    parser.add_argument("--version", default="v26.7.28")
    parser.add_argument("--source-url", default="")
    parser.add_argument("--abis", default="arm64-v8a,armeabi-v7a,x86_64")
    parser.add_argument("--expected-sha256", default="")
    parser.add_argument("--validate-only", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    requested = [value.strip() for value in args.abis.split(",") if value.strip()]
    unknown = set(requested) - SUPPORTED_ABIS
    if unknown:
        raise ValueError(f"Unsupported requested ABI(s): {', '.join(sorted(unknown))}")
    if args.validate_only:
        manifest = json.loads(args.manifest_output.read_text(encoding="utf-8"))
        if not manifest.get("prepared") or not args.aar_output.is_file():
            raise ValueError("Xray core is not prepared. Run tools/trivox-wizard.sh --prepare")
        actual = sha256(args.aar_output)
        if actual != manifest.get("sha256"):
            raise ValueError("libXray.aar SHA-256 does not match core-manifest.json")
        available = inspect_aar(args.aar_output)
        missing = set(requested) - set(available)
        if missing:
            raise ValueError(f"AAR is missing requested ABI(s): {', '.join(sorted(missing))}")
        print(f"Validated {manifest.get('version')} ({actual})")
        return 0

    if not args.artifact or not args.artifact.is_file():
        raise ValueError("Artifact file does not exist")
    archive_sha = sha256(args.artifact)
    if args.expected_sha256 and archive_sha.lower() != args.expected_sha256.lower():
        raise ValueError(f"Downloaded archive SHA-256 mismatch: expected {args.expected_sha256}, got {archive_sha}")
    args.aar_output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="trivox-core-") as temp_dir:
        staged = Path(temp_dir) / "libXray.aar"
        extract_aar(args.artifact, staged)
        available = inspect_aar(staged)
        missing = set(requested) - set(available)
        if missing:
            raise ValueError(f"Official AAR does not contain requested ABI(s): {', '.join(sorted(missing))}")
        shutil.copyfile(staged, args.aar_output)
    aar_sha = sha256(args.aar_output)
    manifest = {
        "name": "Xray-core",
        "adapter": "libXray",
        "version": args.version,
        "artifact": args.aar_output.name,
        "sha256": aar_sha,
        "sourceUrl": args.source_url,
        "buildDate": dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat(),
        "abis": requested,
        "availableAbis": available,
        "archiveSha256": archive_sha,
        "capabilities": [
            "share-link-conversion", "config-validation", "real-delay-test", "proxy",
            "android-tun", "socket-protect", "tcp", "udp", "ipv4", "ipv6"
        ],
        "prepared": True,
    }
    args.manifest_output.parent.mkdir(parents=True, exist_ok=True)
    args.manifest_output.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Prepared {args.version}: {args.aar_output}")
    print(f"AAR SHA-256: {aar_sha}")
    print(f"ABIs: {', '.join(requested)}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, json.JSONDecodeError, zipfile.BadZipFile) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise SystemExit(2)
