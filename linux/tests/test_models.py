import bz2
import hashlib
import io
import tarfile
import tempfile
import threading
import unittest
from pathlib import Path

from localdictate.models import (Asset, Cancelled, Downloader, IntegrityError, ModelStore, extract_archive, prepare,
                                 safe_member_path)

PREFIX = "model"


def asset(name: str, data: bytes) -> Asset:
    return Asset(name, len(data), hashlib.sha256(data).hexdigest())


def archive_bytes(entries) -> bytes:
    raw = io.BytesIO()
    with tarfile.open(fileobj=raw, mode="w") as tar:
        for name, data, kind in entries:
            info = tarfile.TarInfo(name)
            if kind == "dir":
                info.type = tarfile.DIRTYPE
                tar.addfile(info)
            elif kind == "symlink":
                info.type = tarfile.SYMTYPE
                info.linkname = "/etc/passwd"
                tar.addfile(info)
            else:
                info.size = len(data)
                tar.addfile(info, io.BytesIO(data))
    return bz2.compress(raw.getvalue())


A, B = b"encoder-bytes", b"tokens"
MEMBERS = (asset("a.onnx", A), asset("t.txt", B))


class ExtractTest(unittest.TestCase):
    def run_extract(self, entries):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        path = Path(directory.name) / "x.tar.bz2"
        path.write_bytes(archive_bytes(entries))
        destination = Path(directory.name) / "out"
        extract_archive(path, destination, PREFIX, MEMBERS)
        return destination

    def test_valid_archive_skips_unlisted_files(self):
        out = self.run_extract([(PREFIX, b"", "dir"), (f"{PREFIX}/a.onnx", A, "file"),
                                (f"{PREFIX}/test_wavs/fr.wav", b"wav", "file"), (f"{PREFIX}/t.txt", B, "file")])
        self.assertEqual(sorted(p.name for p in out.iterdir()), ["a.onnx", "t.txt"])

    def test_malicious_archives_rejected_and_cleaned(self):
        cases = [
            [(f"{PREFIX}/../evil", A, "file")],
            [("/abs/a.onnx", A, "file")],
            [(f"{PREFIX}/a.onnx", A, "symlink")],
            [(f"{PREFIX}/a.onnx", A, "file"), (f"{PREFIX}/a.onnx", A, "file")],
            [(f"{PREFIX}/a.onnx", A + b"x", "file"), (f"{PREFIX}/t.txt", B, "file")],
            [(f"{PREFIX}/a.onnx", b"X" * len(A), "file"), (f"{PREFIX}/t.txt", B, "file")],
            [(f"{PREFIX}/a.onnx", A, "file")],
            [("other/a.onnx", A, "file")],
        ]
        for entries in cases:
            with self.subTest(entries=[e[0] for e in entries]):
                directory = tempfile.TemporaryDirectory()
                self.addCleanup(directory.cleanup)
                path = Path(directory.name) / "x.tar.bz2"
                path.write_bytes(archive_bytes(entries))
                destination = Path(directory.name) / "out"
                with self.assertRaises(IntegrityError):
                    extract_archive(path, destination, PREFIX, MEMBERS)
                self.assertFalse(destination.exists())

    def test_safe_member_path(self):
        self.assertEqual(safe_member_path("model/", "model"), "model")
        for bad in ("model/./a", "model//a", "model\\a", "c:model/a", "modelx/a"):
            with self.assertRaises(IntegrityError):
                safe_member_path(bad, "model")


class FakeResponse:
    def __init__(self, data: bytes, status=200, headers=None, url="https://example.org/x"):
        self.stream = io.BytesIO(data)
        self.status = status
        self.headers = headers or {}
        self.url = url

    def geturl(self):
        return self.url

    def read(self, size=-1):
        return self.stream.read(size)

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


class DownloaderTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        self.data = bytes(range(256)) * 10
        self.asset = asset("file.bin", self.data)

    def tearDown(self):
        self.directory.cleanup()

    def test_resume_with_exact_content_range(self):
        target = self.root / "file.bin"
        (self.root / "file.bin.part").write_bytes(self.data[:1000])
        seen = {}

        def opener(request, timeout):
            seen["range"] = request.get_header("Range")
            return FakeResponse(self.data[1000:], 206, {"Content-Range": f"bytes 1000-{len(self.data) - 1}/{len(self.data)}",
                                                        "Content-Length": str(len(self.data) - 1000)})

        Downloader(threading.Event(), opener).fetch("https://example.org/file.bin", self.asset, target, lambda n: None)
        self.assertEqual(seen["range"], "bytes=1000-")
        self.assertEqual(target.read_bytes(), self.data)

    def test_rejects_bad_responses(self):
        target = self.root / "file.bin"
        bad = [
            FakeResponse(self.data, 200, {"Content-Encoding": "gzip"}),
            FakeResponse(self.data, 200, {}, url="http://example.org/x"),
            FakeResponse(self.data + b"x", 200, {}),
            FakeResponse(b"\0" * len(self.data), 200, {}),
            FakeResponse(self.data, 200, {"Content-Length": "5"}),
        ]
        for response in bad:
            with self.subTest(response=response.headers):
                with self.assertRaises(IntegrityError):
                    Downloader(threading.Event(), lambda r, timeout, resp=response: resp).fetch(
                        "https://example.org/file.bin", self.asset, target, lambda n: None)
                self.assertFalse(target.exists())
        with self.assertRaises(IntegrityError):
            Downloader(threading.Event()).fetch("http://example.org/f", self.asset, target, lambda n: None)

    def test_cancel(self):
        cancel = threading.Event()
        cancel.set()
        with self.assertRaises(Cancelled):
            Downloader(cancel, lambda r, timeout: FakeResponse(self.data)).fetch(
                "https://example.org/f", self.asset, self.root / "f", lambda n: None)


class PrepareTest(unittest.TestCase):
    def test_full_preparation_and_ready_check(self):
        with tempfile.TemporaryDirectory() as directory:
            archive_data = archive_bytes([(f"{PREFIX}/a.onnx", A, "file"), (f"{PREFIX}/t.txt", B, "file")])
            vad_data = b"vad"
            archive, vad = asset("m.tar.bz2", archive_data), asset("vad.onnx", vad_data)
            files = {"https://h/m.tar.bz2": archive_data, "https://h/vad.onnx": vad_data}
            marker = "test-marker"
            store = ModelStore(Path(directory) / "models", MEMBERS, vad, marker, PREFIX)
            cancel = threading.Event()
            downloader = Downloader(cancel, lambda request, timeout: FakeResponse(files[request.full_url]))
            import localdictate.models as models
            original = models.MIN_FREE_BYTES
            models.MIN_FREE_BYTES = 0
            try:
                result = prepare(store, cancel, lambda p: None, downloader, "https://h/", archive, vad)
            finally:
                models.MIN_FREE_BYTES = original
            self.assertTrue(store.ready())
            self.assertEqual((result.parakeet_dir / "a.onnx").read_bytes(), A)
            (result.parakeet_dir / "t.txt").write_bytes(b"x")
            self.assertFalse(store.ready())


if __name__ == "__main__":
    unittest.main()
