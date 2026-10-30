"""Fetch a pinned native speech runtime (never speech-model weights)."""
from pathlib import Path
from urllib.request import urlopen
import hashlib
import sys
import time

NAME = "sherpa-onnx-static-link-onnxruntime-2.0.1.aar"
SHA256 = "07e042d97f3a6bd5b7fe74d4eef0513dc01a714b16c15a903250095aba43a575"
SIZE = 38577050
URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/" + NAME


def valid(path):
    if not path.is_file() or path.stat().st_size != SIZE:
        return False
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while block := source.read(1024 * 1024):
            digest.update(block)
    return digest.hexdigest() == SHA256


def fetch(destination):
    if valid(destination):
        print("Pinned sherpa-onnx AAR verified")
        return
    destination.parent.mkdir(parents=True, exist_ok=True)
    part = destination.with_suffix(".part")
    for attempt in range(3):
        try:
            with urlopen(URL, timeout=60) as response, part.open("wb") as output:
                written = 0
                while block := response.read(1024 * 1024):
                    written += len(block)
                    if written > SIZE:
                        raise ValueError("sherpa-onnx AAR exceeds pinned size")
                    output.write(block)
            if not valid(part):
                raise ValueError("sherpa-onnx AAR size or SHA-256 mismatch")
            part.replace(destination)
            print("Pinned sherpa-onnx AAR downloaded and verified")
            return
        except Exception:
            part.unlink(missing_ok=True)
            if attempt == 2:
                raise
            time.sleep(2 ** attempt)


if __name__ == "__main__":
    default = Path(__file__).resolve().parents[1] / "app/build/dependencies" / NAME
    fetch(Path(sys.argv[1]) if len(sys.argv) > 1 else default)
