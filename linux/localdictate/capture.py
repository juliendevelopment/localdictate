"""Microphone capture with GStreamer (PipeWire or PulseAudio), converted to mono PCM16 at 16 kHz.

No audio file is written here: blocks go to the pipeline callback. ``stop()`` may be called from any thread
(the state change is posted to the main loop, never done from GStreamer's streaming thread).
"""
from __future__ import annotations

import threading
import time
from dataclasses import dataclass
from typing import Callable

import gi

gi.require_version("Gst", "1.0")
from gi.repository import GLib, Gst  # noqa: E402

from .dsp import RATE, pcm16_to_float  # noqa: E402

Gst.init(None)
CAPS = f"audio/x-raw,format=S16LE,rate={RATE},channels=1,layout=interleaved"


@dataclass(frozen=True)
class InputDevice:
    key: str
    label: str


def _device_key(device: Gst.Device) -> str:
    properties = device.get_properties()
    if properties is not None:
        for name in ("node.name", "device.name", "object.path", "udi"):
            if properties.has_field(name):
                value = properties.get_value(name)
                if value:
                    return f"{name}={value}"
    try:
        internal = device.get_property("internal-name")
        if internal:
            return f"internal-name={internal}"
    except TypeError:
        pass
    return f"label={device.get_display_name()}"


def _monitor_devices() -> list[Gst.Device]:
    monitor = Gst.DeviceMonitor.new()
    monitor.add_filter("Audio/Source", None)
    if not monitor.start():
        return []
    try:
        return list(monitor.get_devices() or [])
    finally:
        monitor.stop()


def list_input_devices() -> list[InputDevice]:
    seen, result = set(), []
    for device in _monitor_devices():
        properties = device.get_properties()
        if properties is not None and properties.has_field("device.class") and properties.get_value("device.class") == "monitor":
            continue  # Speaker monitors are not microphones.
        key = _device_key(device)
        if key not in seen:
            seen.add(key)
            result.append(InputDevice(key, device.get_display_name()))
    return result


def _source_element(device_key: str | None) -> tuple[Gst.Element, bool]:
    """Returns the source element and whether the requested device was found."""
    if device_key:
        for device in _monitor_devices():
            if _device_key(device) == device_key:
                element = device.create_element(None)
                if element is not None:
                    return element, True
    for factory in ("pipewiresrc", "pulsesrc", "autoaudiosrc"):
        element = Gst.ElementFactory.make(factory, None)
        if element is not None:
            return element, device_key is None
    raise RuntimeError("Aucune source audio GStreamer (installez gstreamer1-plugins-good / pipewire-gstreamer)")


class Capture:
    """One microphone session. ``on_block(samples, origin)`` runs on GStreamer's streaming thread."""

    def __init__(self, on_block: Callable, on_error: Callable[[str], None],
                 source_factory: Callable[[], Gst.Element] | None = None):
        self.on_block = on_block
        self.on_error = on_error
        self.source_factory = source_factory
        self.pipeline: Gst.Pipeline | None = None
        self.device_found = True
        self._stopped = threading.Event()
        self._bus_watch = None
        self._worker: threading.Thread | None = None

    def start(self, device_key: str | None) -> None:
        if self.source_factory is not None:
            source, self.device_found = self.source_factory(), True
        else:
            source, self.device_found = _source_element(device_key)
        pipeline = Gst.Pipeline.new("localdictate-capture")
        convert = Gst.ElementFactory.make("audioconvert", None)
        resample = Gst.ElementFactory.make("audioresample", None)
        capsfilter = Gst.ElementFactory.make("capsfilter", None)
        sink = Gst.ElementFactory.make("appsink", None)
        if None in (convert, resample, capsfilter, sink):
            raise RuntimeError("Éléments GStreamer manquants (gstreamer1-plugins-base)")
        capsfilter.set_property("caps", Gst.Caps.from_string(CAPS))
        sink.set_property("emit-signals", True)
        sink.set_property("sync", False)
        sink.set_property("max-buffers", 0)
        sink.set_property("drop", False)
        sink.connect("new-sample", self._on_sample)
        for element in (source, convert, resample, capsfilter, sink):
            pipeline.add(element)
        if not (source.link(convert) and convert.link(resample) and resample.link(capsfilter) and capsfilter.link(sink)):
            raise RuntimeError("Chaîne audio GStreamer impossible à relier")
        bus = pipeline.get_bus()
        bus.add_signal_watch()
        self._bus_watch = bus.connect("message", self._on_message)
        self.pipeline = pipeline
        # State changes run on a dedicated thread: pipewiresrc can block for 30 s when the source is missing,
        # and the main loop (shortcut, window) must stay responsive. The controller's 5 s watchdog reports it.
        self._worker = threading.Thread(target=self._run, args=(pipeline,), name="ld-capture", daemon=True)
        self._worker.start()

    def _run(self, pipeline: Gst.Pipeline) -> None:
        if pipeline.set_state(Gst.State.PLAYING) == Gst.StateChangeReturn.FAILURE and not self._stopped.is_set():
            self.on_error("Le micro n’a pas pu être ouvert.")
        self._stopped.wait()
        pipeline.set_state(Gst.State.NULL)
        GLib.idle_add(self._remove_watch, pipeline)

    def _on_sample(self, sink) -> Gst.FlowReturn:
        if self._stopped.is_set():
            return Gst.FlowReturn.EOS
        sample = sink.emit("pull-sample")
        if sample is None:
            return Gst.FlowReturn.EOS
        buffer = sample.get_buffer()
        ok, info = buffer.map(Gst.MapFlags.READ)
        if not ok:
            return Gst.FlowReturn.ERROR
        try:
            samples = pcm16_to_float(bytes(info.data))
        finally:
            buffer.unmap(info)
        # Time of the first sample of this block, estimated from its duration.
        origin = time.monotonic() - samples.size / RATE
        self.on_block(samples, origin)
        return Gst.FlowReturn.OK

    def _on_message(self, _bus, message) -> None:
        if message.type == Gst.MessageType.ERROR:
            error, _debug = message.parse_error()
            if not self._stopped.is_set():
                self.on_error(f"Micro interrompu : {error.message}")
            self.stop()
        elif message.type == Gst.MessageType.EOS and not self._stopped.is_set():
            self.on_error("Le micro a cessé d’envoyer du son.")
            self.stop()

    def stop(self) -> None:
        """Any thread. The worker sets the pipeline to NULL; the bus watch is removed on the main loop."""
        self._stopped.set()

    def _remove_watch(self, pipeline: Gst.Pipeline) -> bool:
        bus = pipeline.get_bus()
        if self._bus_watch is not None:
            bus.disconnect(self._bus_watch)
            self._bus_watch = None
        bus.remove_signal_watch()
        if self.pipeline is pipeline:
            self.pipeline = None
        return False
