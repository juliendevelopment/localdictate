import bz2
import hashlib
import io
import tarfile
import tempfile
import threading
import unittest
from pathlib import Path

from localdictate.models import (MARKER, MEMBERS as PINNED, PARAKEET_URL, VAD, Asset, Cancelled, Downloader,
                                 IntegrityError, ModelStore, prepare)

PREFIX = "model"


def asset(name: str, data: bytes) -> Asset:
    return Asset(name, len(data), hashlib.sha256(data).hexdigest())


A, B = b"encoder-bytes", b"tokens"
MEMBERS = (asset("a.onnx", A), asset("t.txt", B))


class CatalogTest(unittest.TestCase):
    def test_pinned_source_is_an_immutable_revision(self):
        self.assertRegex(PARAKEET_URL, r"^https://huggingface\.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0\.6b-v3-int8/resolve/[0-9a-f]{40}/$")
        # The hashes verified for the Android app (encoder/decoder/joiner/tokens) and Silero.
        self.assertEqual([a.sha256[:8] for a in PINNED], ["acfc2b44", "179e50c4", "3164c13f", "d5854467"])
        self.assertEqual(VAD.sha256[:8], "9e2449e1")
        self.assertTrue(MARKER.startswith("verified-models-v2\n"))


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
    def setUp(self):
        import localdictate.models as models

        self.models = models
        self.original = models.MIN_FREE_BYTES
        models.MIN_FREE_BYTES = 0

    def tearDown(self):
        self.models.MIN_FREE_BYTES = self.original

    def run_prepare(self, directory, files):
        vad = asset("vad.onnx", b"vad")
        store = ModelStore(Path(directory) / "models", MEMBERS, vad, "test-marker", "model")
        cancel = threading.Event()
        requested = []

        def opener(request, timeout):
            requested.append(request.full_url)
            return FakeResponse(files[request.full_url])

        result = prepare(store, cancel, lambda p: None, Downloader(cancel, opener), "https://hf/rev/", "https://gh/")
        return store, result, requested

    def test_full_preparation_and_ready_check(self):
        with tempfile.TemporaryDirectory() as directory:
            files = {"https://hf/rev/a.onnx": A, "https://hf/rev/t.txt": B, "https://gh/vad.onnx": b"vad"}
            store, result, requested = self.run_prepare(directory, files)
            self.assertEqual(requested, list(files))
            self.assertTrue(store.ready())
            self.assertEqual((result.parakeet_dir / "a.onnx").read_bytes(), A)
            self.assertFalse(store.downloads.exists())
            (result.parakeet_dir / "t.txt").write_bytes(b"x")
            self.assertFalse(store.ready())

    def test_changed_upstream_file_is_refused_and_nothing_installed(self):
        with tempfile.TemporaryDirectory() as directory:
            files = {"https://hf/rev/a.onnx": b"X" * len(A), "https://hf/rev/t.txt": B, "https://gh/vad.onnx": b"vad"}
            with self.assertRaises(IntegrityError):
                self.run_prepare(directory, files)
            store = ModelStore(Path(directory) / "models", MEMBERS, asset("vad.onnx", b"vad"), "test-marker", "model")
            self.assertFalse(store.ready())
            self.assertFalse(store.installed.exists())


if __name__ == "__main__":
    unittest.main()
