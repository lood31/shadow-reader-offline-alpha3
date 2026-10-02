"""Download a workspace-local Android build toolchain; no global configuration edits."""
import concurrent.futures
import hashlib
from pathlib import Path
import urllib.request
import zipfile
import time
import threading
import io

ROOT = Path(__file__).resolve().parent.parent
TOOLS = ROOT / ".tools"
TOOLS.mkdir(exist_ok=True)

def fetch(url, name, digest=None):
    target = TOOLS / name
    if not target.exists() or not zipfile.is_zipfile(target):
        print("Downloading", name, flush=True)
        with urllib.request.urlopen(urllib.request.Request(url, headers={"Range": "bytes=0-65535"}), timeout=40) as r:
            content_range = r.headers.get("Content-Range")
            resolved_url = r.url
            if not content_range:
                data = r.read()
                with zipfile.ZipFile(io.BytesIO(data)) as archive:
                    if archive.testzip() is not None:
                        raise RuntimeError("Archive integrity check failed: " + name)
                if digest and hashlib.sha256(data).hexdigest() != digest:
                    raise RuntimeError("Checksum mismatch: " + name)
                target.write_bytes(data)
                return target
            total = int(content_range.split("/")[-1])
        chunk_size = 256 * 1024
        partial = target.with_suffix(".partial")
        manifest = target.with_suffix(".chunks")
        completed = set()
        if partial.exists() and partial.stat().st_size == total and manifest.exists():
            completed = {int(value) for value in manifest.read_text().splitlines()}
        else:
            with partial.open("wb") as out:
                out.truncate(total)
            manifest.write_text("")
        lock = threading.Lock()
        def download(start):
            if start in completed:
                return
            end = min(start + chunk_size, total) - 1
            for attempt in range(8):
                try:
                    request = urllib.request.Request(resolved_url, headers={"Range": f"bytes={start}-{end}"})
                    with urllib.request.urlopen(request, timeout=40) as response:
                        if response.status != 206 or not response.headers.get("Content-Range", "").startswith(f"bytes {start}-{end}/"):
                            raise RuntimeError("Server returned an unexpected byte range")
                        data = response.read()
                    if len(data) != end - start + 1:
                        raise RuntimeError("Incomplete chunk")
                    with partial.open("r+b") as out:
                        out.seek(start)
                        out.write(data)
                    with lock, manifest.open("a") as out:
                        out.write(str(start) + "\n")
                    return
                except Exception:
                    if attempt == 7:
                        raise
                    time.sleep(1 + attempt)
        with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
            for count, _ in enumerate(pool.map(download, range(0, total, chunk_size)), 1):
                if count % 100 == 0:
                    print(name, min(count * chunk_size, total), "/", total, flush=True)
        with zipfile.ZipFile(partial) as archive:
            if archive.testzip() is not None:
                manifest.unlink(missing_ok=True)
                raise RuntimeError("Archive integrity check failed: " + name)
        partial.replace(target)
        manifest.unlink(missing_ok=True)
    if digest and hashlib.sha256(target.read_bytes()).hexdigest() != digest:
        target.unlink()
        raise RuntimeError("Checksum mismatch: " + name)
    return target

def java():
    url = "https://aka.ms/download-jdk/microsoft-jdk-17.0.16-windows-x64.zip"
    with urllib.request.urlopen(url + ".sha256sum.txt", timeout=30) as r:
        checksum = r.read().decode().split()[0]
    archive = fetch(url, "jdk17.zip", checksum)
    with zipfile.ZipFile(archive) as z:
        folder = z.namelist()[0].split("/")[0]
        if not (TOOLS / folder / "bin/java.exe").exists():
            z.extractall(TOOLS)
    (TOOLS / "java-home.txt").write_text(str(TOOLS / folder), encoding="utf-8")

def android():
    archive = fetch("https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip?shadowreader=1", "android-commandline.zip")
    destination = TOOLS / "android-sdk" / "cmdline-tools" / "latest"
    if not (destination / "bin/sdkmanager.bat").exists():
        with zipfile.ZipFile(archive) as z:
            for info in z.infolist():
                relative = info.filename.removeprefix("cmdline-tools/")
                if relative and not info.is_dir():
                    file = destination / relative
                    file.parent.mkdir(parents=True, exist_ok=True)
                    file.write_bytes(z.read(info))

def gradle():
    checksum_url = "https://services.gradle.org/distributions/gradle-8.9-bin.zip.sha256"
    with urllib.request.urlopen(checksum_url, timeout=60) as r:
        digest = r.read().decode().strip()
    archive = fetch("https://services.gradle.org/distributions/gradle-8.9-bin.zip", "gradle.zip", digest)
    if not (TOOLS / "gradle-8.9/bin/gradle.bat").exists():
        with zipfile.ZipFile(archive) as z:
            z.extractall(TOOLS)

if __name__ == "__main__":
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        for result in pool.map(lambda fn: fn(), [java, android, gradle]):
            pass
    print("Toolchain downloads complete.", flush=True)
