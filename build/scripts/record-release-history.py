"""Record the public notes from RM manifests, withholding package URLs and hashes."""

import fcntl
import json
import os
from datetime import datetime, timezone
from pathlib import Path
import pwd
import sys
import tempfile


PLATFORMS = {
    "android-update.json": "android",
    "update.json": "windows",
    "linux-update.json": "linux",
}


def record(directory, web_directory, owner, name, expected_code, expected_hash):
    history_path = directory / "release-history.json"
    public_path = web_directory / history_path.name
    if not web_directory.is_dir():
        raise ValueError("RM web directory does not exist")
    account = pwd.getpwnam(owner)
    with (directory / ".release-history.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        if public_path.is_symlink():
            if public_path.resolve() != history_path.resolve():
                raise ValueError("Unexpected release history symlink")
        elif public_path.exists():
            raise ValueError("RM web directory already contains a release history file")

        history = json.loads(history_path.read_text(encoding="utf-8")) if history_path.exists() else {
            "schemaVersion": 1, "releases": []
        }
        if history["schemaVersion"] != 1:
            raise ValueError("Unsupported release history format")
        releases = {(entry["platform"], entry["versionCode"]): entry for entry in history["releases"]}
        manifests = [name] if name else PLATFORMS
        for filename in manifests:
            with (directory / filename).open(encoding="utf-8-sig") as source:
                manifest = json.load(source)
                modified = os.fstat(source.fileno()).st_mtime
            code = manifest["versionCode"]
            if expected_code and (code != expected_code or manifest["sha256"].lower() != expected_hash):
                raise ValueError("Active manifest changed after the public verification")
            version = manifest["versionName"].strip()
            if type(code) is not int or code <= 0 or not version:
                raise ValueError("Invalid release version")
            platform = PLATFORMS[filename]
            key = (platform, code)
            timestamp = datetime.fromtimestamp(modified, timezone.utc).isoformat(timespec="seconds")
            releases[key] = {
                "platform": platform,
                "versionCode": code,
                "versionName": version,
                "publishedAt": releases.get(key, {}).get("publishedAt", timestamp),
                "releaseNotes": (manifest.get("releaseNotes") or "").strip(),
            }
        history["releases"] = sorted(releases.values(), key=lambda entry: (
            entry["publishedAt"], entry["versionCode"], entry["platform"]
        ), reverse=True)
        try:
            with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=directory,
                                             prefix=".release-history-", delete=False) as staged:
                json.dump(history, staged, ensure_ascii=False, indent=2)
                staged.write("\n")
                staged.flush()
                os.fchown(staged.fileno(), account.pw_uid, account.pw_gid)
                os.fsync(staged.fileno())
            os.replace(staged.name, history_path)
        finally:
            if "staged" in locals():
                Path(staged.name).unlink(missing_ok=True)
        if not public_path.is_symlink():
            public_path.symlink_to(history_path)


if __name__ == "__main__":
    directory, web_directory, owner, *manifest = sys.argv[1:]
    name, code, sha256 = (manifest[0], int(manifest[1]), manifest[2]) if manifest else (None, None, None)
    record(Path(directory), Path(web_directory), owner, name, code, sha256)
