"""Build the standalone signed ARM64 Codex local runtime extension APK."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import tarfile
import tempfile
from urllib.request import urlretrieve
import zipfile

PACKAGE = "com.codexrm.runtime"
SOURCES = {
    "codex": (
        "pool/main/c/codex/codex_0.172.0_aarch64.deb",
        "5998bb3ec96eef82c29c4f9a17f996dc5736d784d286b94df0f0ba9cf7d364c1",
    ),
    "openssl": (
        "pool/main/o/openssl/openssl_1%3A3.6.5_aarch64.deb",
        "a2fb7856875e63ef6b90442752e05974813372c3ca86d2513a78ea43c67a0f8b",
    ),
}
PREFIX = "./data/data/com.termux/files/usr/"
LIB_NAMES = {b"libssl.so.3": b"libssl_3.so", b"libcrypto.so.3": b"libcrypto_3.so"}


def run(*args, **kwargs):
    return subprocess.run([str(arg) for arg in args], check=True, **kwargs)


def output(*args):
    return run(*args, capture_output=True, text=True, encoding="utf-8", errors="replace").stdout


def digest(path):
    with path.open("rb") as f:
        return hashlib.file_digest(f, "sha256").hexdigest()


def apk_identity(aapt2, apk):
    header = output(aapt2, "dump", "badging", apk).splitlines()[0]
    info = dict(re.findall(r"(\w+)='([^']*)'", header))
    return info["name"], int(info["versionCode"]), info.get("split", "")


def signers(apksigner, apk):
    text = output(apksigner, "verify", "--print-certs", apk)
    result = re.findall(r"Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-f]+)", text)
    if not result:
        raise ValueError(f"APK has no verified signer: {apk.name}")
    return sorted(set(result))


def package(cache, name, mirror):
    url, expected = SOURCES[name]
    path = cache / (name + "-" + expected[:12] + ".deb")
    if not path.exists():
        staged = path.with_suffix(".download")
        try:
            urlretrieve(f"{mirror.rstrip('/')}/{url}", staged)
            if digest(staged) != expected:
                raise ValueError(f"Wrong SHA-256 for {name}")
            staged.replace(path)
        finally:
            staged.unlink(missing_ok=True)
    if digest(path) != expected:
        raise ValueError(f"Wrong SHA-256 for {path}")
    return path


def with_package(deb, work, callback):
    # Extract a single named ar member. Do not unpack an archive tree onto the host.
    run("tar", "-xf", deb, "-C", work, "data.tar.xz")
    with tarfile.open(work / "data.tar.xz", mode="r|xz") as tar:
        callback(tar)
    (work / "data.tar.xz").unlink()


def rename_dynamic_libraries(data):
    # ELF64 little-endian, AArch64. Rewrite only .dynstr, preserving sizes and offsets.
    if data[:6] != b"\x7fELF\x02\x01" or struct.unpack_from("<H", data, 18)[0] != 183:
        raise ValueError("Unexpected ELF target (need little-endian AArch64)")
    section_base = struct.unpack_from("<Q", data, 40)[0]
    stride, count, name_index = struct.unpack_from("<HHH", data, 58)
    if stride != 64 or not 0 < name_index < count:
        raise ValueError("Unexpected ELF section layout")
    names_base, names_size = struct.unpack_from("<QQ", data, section_base + name_index * stride + 24)
    names = data[names_base:names_base + names_size]
    for offset in range(section_base, section_base + count * stride, stride):
        name = struct.unpack_from("<I", data, offset)[0]
        if names[name:].split(b"\0", 1)[0] != b".dynstr":
            continue
        start, size = struct.unpack_from("<QQ", data, offset + 24)
        strings = data[start:start + size]
        for old, new in LIB_NAMES.items():
            if len(old) != len(new):
                raise ValueError("Library rename must preserve offsets")
            strings = strings.replace(old + b"\0", new + b"\0")
        return data[:start] + strings + data[start + size:]
    raise ValueError("ELF has no .dynstr section")


def stage_package(tar, archive, executables, documents):
    seen = set()
    for member in tar:
        if not member.isfile() or not member.name.startswith(PREFIX):
            continue
        path = member.name[len(PREFIX):]
        target = executables.get(path)
        if target is not None:
            with tar.extractfile(member) as entry:
                data = rename_dynamic_libraries(entry.read())
            archive.writestr(f"lib/arm64-v8a/{target}", data)
            seen.add(path)
        elif path.startswith(documents) and "/" not in path[len(documents):]:
            with tar.extractfile(member) as entry:
                archive.writestr("assets/runtime-notices/" + path[len("share/doc/"):], entry.read())
    if seen != set(executables):
        raise ValueError(f"Runtime files missing: {set(executables) - seen}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--extension", required=True, type=Path, help="Unsigned codex-runtime Gradle APK")
    parser.add_argument("--host", type=Path, help="Optionally verify that CodexRM and the extension have the same signer")
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--manifest", type=Path, help="Write an unpublished release manifest for codexrm-publish")
    parser.add_argument("--sdk", required=True, type=Path)
    parser.add_argument("--keystore", required=True, type=Path)
    parser.add_argument("--alias", required=True)
    parser.add_argument("--keystore-password", default="env:CODEXRM_RUNTIME_KS_PASS")
    parser.add_argument("--key-password", default="env:CODEXRM_RUNTIME_KEY_PASS")
    parser.add_argument("--cache", required=True, type=Path)
    parser.add_argument("--termux-mirror", default="https://packages.termux.dev/apt/termux-main")
    parser.add_argument("--agent", required=True, type=Path, help="ARM64 Android PIE for the local CodexRM Agent transport")
    args = parser.parse_args()

    suffix = ".exe" if os.name == "nt" else ""
    tools = args.sdk / "build-tools" / "36.0.0"
    aapt2, zipalign = (tools / (name + suffix) for name in ("aapt2", "zipalign"))
    apksigner = tools / ("apksigner.bat" if os.name == "nt" else "apksigner")
    skeleton = args.extension.resolve(strict=True)
    target = args.output.resolve()
    if target == skeleton:
        raise ValueError("Output must not replace the unsigned extension APK")
    package_name, version, split = apk_identity(aapt2, skeleton)
    if split or package_name != PACKAGE:
        raise ValueError("Expected the standalone Codex runtime APK")
    manifest = output(aapt2, "dump", "xmltree", skeleton, "--file", "AndroidManifest.xml")
    if not re.search(r"android:extractNativeLibs[^\n]*=(?:true|[^\n]*0xffffffff)", manifest):
        raise ValueError("The extension must enable native library extraction")
    cache = args.cache.resolve()
    cache.mkdir(parents=True, exist_ok=True)
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="codexrm-android-runtime-") as temp:
        work = Path(temp)
        raw, aligned, signed = (work / f"{name}.apk" for name in ("raw", "aligned", "signed"))
        with zipfile.ZipFile(skeleton) as source, zipfile.ZipFile(raw, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
            for entry in source.infolist():
                if entry.filename.startswith(("META-INF/", "lib/")):
                    continue
                archive.writestr(entry, source.read(entry))
            with_package(package(cache, "codex", args.termux_mirror), work, lambda tar: stage_package(
                tar, archive, {"bin/codex": "libcodex_android.so"}, "share/doc/codex/"))
            with_package(package(cache, "openssl", args.termux_mirror), work, lambda tar: stage_package(
                tar, archive, {"lib/libssl.so.3": "libssl_3.so", "lib/libcrypto.so.3": "libcrypto_3.so"},
                "share/doc/openssl/"))
            archive.write(args.agent, "lib/arm64-v8a/libcodexrm_local_agent.so")
        run(zipalign, "-f", "-P", "16", "4", raw, aligned)
        run(apksigner, "sign", "--ks", args.keystore.resolve(), "--ks-key-alias", args.alias,
            "--ks-pass", args.keystore_password, "--key-pass", args.key_password,
            "--out", signed, aligned)
        if apk_identity(aapt2, signed) != (package_name, version, ""):
            raise ValueError("Generated runtime APK has the wrong package or version")
        certs = signers(apksigner, signed)
        if args.host and signers(apksigner, args.host.resolve(strict=True)) != certs:
            raise ValueError("Runtime and CodexRM APKs must have the same signer")
        with zipfile.ZipFile(signed) as extension_apk:
            required = ("libcodex_android.so", "libcodexrm_local_agent.so", "libssl_3.so", "libcrypto_3.so")
            files = {entry.filename for entry in extension_apk.infolist() if entry.file_size > 0}
            missing = [name for name in required if f"lib/arm64-v8a/{name}" not in files]
            if missing:
                raise ValueError(f"Missing components in the signed runtime: {', '.join(missing)}")
        pending = target.with_suffix(".pending")
        try:
            with signed.open("rb") as source, pending.open("wb") as dest:
                while chunk := source.read(1024 * 1024):
                    dest.write(chunk)
            pending.replace(target)
        finally:
            pending.unlink(missing_ok=True)
    size, checksum = target.stat().st_size, digest(target)
    if args.manifest:
        header = output(aapt2, "dump", "badging", target).splitlines()[0]
        version_name = dict(re.findall(r"(\w+)='([^']*)'", header))["versionName"]
        args.manifest.write_text(json.dumps({
            "versionCode": version, "versionName": version_name, "downloadUrl": target.name,
            "releaseNotes": "Standalone Android ARM64 local Codex runtime",
            "fileSize": size, "sha256": checksum,
        }, indent=2) + "\n", encoding="utf-8")
    print(f"{target}: {size} bytes, SHA-256 {checksum}")


if __name__ == "__main__":
    main()
