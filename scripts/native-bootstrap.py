"""Pinned whisper sources and official workspace-local NDK/CMake; no global changes."""
import concurrent.futures
import hashlib
from pathlib import Path
import urllib.request
import zipfile
import time
import threading

ROOT = Path(__file__).resolve().parent.parent
TOOLS = ROOT / '.tools'
SDK = TOOLS / 'android-sdk'
VERSION = '1.9.4'
SDK_PACKAGES = {
    'ndk;27.2.12479018': ('android-ndk-r27c-windows.zip', 'ac5f7762764b1f15341094e148ad4f847d050c38'),
    'cmake;3.22.1': ('cmake-3.22.1-windows.zip', '292778f32a7d5183e1c49c7897b870653f2d2c1b'),
}

def file_hash(file, algorithm):
    digest = hashlib.new(algorithm)
    with file.open('rb') as source:
        while chunk := source.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()

def download(url, file, digest=None, algorithm='sha1'):
    if file.exists() and (not digest or file_hash(file, algorithm) == digest):
        return file
    request = urllib.request.Request(url, headers={'Range': 'bytes=0-0'})
    with urllib.request.urlopen(request, timeout=90) as r:
        if r.status != 206:
            data = r.read()
            file.write_bytes(data)
        else:
            total = int(r.headers['Content-Range'].split('/')[-1])
            resolved = r.url
            partial = file.with_suffix('.partial')
            size = 256 * 1024
            manifest = file.with_suffix('.ranges')
            completed = set()
            if partial.exists() and partial.stat().st_size == total:
                if manifest.exists():
                    completed = {int(x) for x in manifest.read_text().splitlines()}
                else:
                    # Upgrade interrupted downloads written in atomic 4 MiB blocks by the earlier downloader.
                    with partial.open('rb') as previous:
                        for start in range(0, total, 4 * 1024 * 1024):
                            end = min(start + 4 * 1024 * 1024, total)
                            previous.seek(end - 64)
                            if any(previous.read(64)):
                                completed.update(range(start, end, size))
                    manifest.write_text(''.join(str(x) + '\n' for x in completed))
            else:
                with partial.open('wb') as out:
                    out.truncate(total)
                manifest.write_text('')
            lock = threading.Lock()
            def part(start):
                if start in completed:
                    return
                end = min(start + size, total) - 1
                for attempt in range(8):
                    try:
                        req = urllib.request.Request(resolved, headers={'Range': f'bytes={start}-{end}'})
                        with urllib.request.urlopen(req, timeout=45) as response:
                            data = response.read()
                            if response.status != 206 or len(data) != end - start + 1 or not response.headers.get('Content-Range', '').startswith(f'bytes {start}-{end}/'):
                                raise RuntimeError('Invalid range')
                        with partial.open('r+b') as out:
                            out.seek(start)
                            out.write(data)
                        with lock, manifest.open('a') as out:
                            out.write(str(start) + '\n')
                        return
                    except Exception:
                        if attempt == 7:
                            raise
                        time.sleep(attempt + 1)
            starts = [x for x in range(0, total, size) if x not in completed]
            with concurrent.futures.ThreadPoolExecutor(max_workers=24) as pool:
                futures = [pool.submit(part, x) for x in starts]
                for count, future in enumerate(concurrent.futures.as_completed(futures), 1):
                    future.result()
                    if count % 100 == 0:
                        print(file.name, min((count + len(completed)) * size, total), '/', total, flush=True)
            partial.replace(file)
    if digest and file_hash(file, algorithm) != digest:
        raise RuntimeError('Checksum mismatch: ' + file.name)
    return file

def sdk_package(path, marker):
    destination = SDK / path.replace(';', '/')
    if (destination / marker).exists():
        return
    if not (SDK / 'licenses/android-sdk-license').exists():
        raise RuntimeError('Accept the Android SDK license in Android Studio or sdkmanager --licenses first.')
    url, checksum = SDK_PACKAGES[path]
    file = download('https://dl.google.com/android/repository/' + url, TOOLS / url, checksum)
    destination.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(file) as z:
        for info in z.infolist():
            relative = info.filename
            if path.startswith('ndk;'):
                relative = relative.partition('/')[2]
            if relative and not info.is_dir():
                target = destination / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(z.read(info))
    print('Installed', path, flush=True)

def sources():
    destination = ROOT / 'third_party' / ('whisper.cpp-' + VERSION)
    if (destination / 'src/whisper.cpp').exists():
        return
    file = download('https://codeload.github.com/ggml-org/whisper.cpp/zip/refs/tags/v' + VERSION,
                    TOOLS / ('whisper.cpp-' + VERSION + '.zip'),
                    '873e67727d51213d3a14a6700c7415900a6645b78c4e6edad9328eea90e53572', 'sha256')
    with zipfile.ZipFile(file) as z:
        # Preserve upstream, including licensing; do not patch upstream code.
        z.extractall(ROOT / 'third_party')

if __name__ == '__main__':
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        jobs = [pool.submit(sdk_package, 'ndk;27.2.12479018', 'ndk-build.cmd'),
                pool.submit(sdk_package, 'cmake;3.22.1', 'bin/cmake.exe')]
        for job in jobs:
            job.result()
    sources()
