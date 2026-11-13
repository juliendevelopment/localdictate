"""Status icon: StatusNotifierItem + DBusMenu over GDBus, without libappindicator.

Shown by KDE Plasma's system tray and, on GNOME, by the top bar when the "AppIndicator and KStatusNotifierItem
Support" extension is enabled (default on Ubuntu, a package on Fedora). Three states: au repos, écoute,
transcription. The menu opens the window, the settings and the folder holding the recordings and texts.
"""
from __future__ import annotations

import os
from dataclasses import dataclass
from typing import Callable

import numpy as np
from gi.repository import Gio, GLib

WATCHER = "org.kde.StatusNotifierWatcher"
ITEM_PATH = "/StatusNotifierItem"
MENU_PATH = "/MenuBar"

ITEM_XML = """
<node>
 <interface name="org.kde.StatusNotifierItem">
  <method name="Activate"><arg name="x" type="i" direction="in"/><arg name="y" type="i" direction="in"/></method>
  <method name="SecondaryActivate"><arg name="x" type="i" direction="in"/><arg name="y" type="i" direction="in"/></method>
  <method name="ContextMenu"><arg name="x" type="i" direction="in"/><arg name="y" type="i" direction="in"/></method>
  <method name="Scroll"><arg name="delta" type="i" direction="in"/><arg name="orientation" type="s" direction="in"/></method>
  <signal name="NewTitle"/>
  <signal name="NewIcon"/>
  <signal name="NewAttentionIcon"/>
  <signal name="NewOverlayIcon"/>
  <signal name="NewToolTip"/>
  <signal name="NewStatus"><arg name="status" type="s"/></signal>
  <property name="Category" type="s" access="read"/>
  <property name="Id" type="s" access="read"/>
  <property name="Title" type="s" access="read"/>
  <property name="Status" type="s" access="read"/>
  <property name="WindowId" type="u" access="read"/>
  <property name="IconThemePath" type="s" access="read"/>
  <property name="IconName" type="s" access="read"/>
  <property name="IconPixmap" type="a(iiay)" access="read"/>
  <property name="OverlayIconName" type="s" access="read"/>
  <property name="OverlayIconPixmap" type="a(iiay)" access="read"/>
  <property name="AttentionIconName" type="s" access="read"/>
  <property name="AttentionIconPixmap" type="a(iiay)" access="read"/>
  <property name="AttentionMovieName" type="s" access="read"/>
  <property name="ToolTip" type="(sa(iiay)ss)" access="read"/>
  <property name="ItemIsMenu" type="b" access="read"/>
  <property name="Menu" type="o" access="read"/>
 </interface>
</node>
"""

MENU_XML = """
<node>
 <interface name="com.canonical.dbusmenu">
  <method name="GetLayout">
   <arg type="i" name="parentId" direction="in"/><arg type="i" name="recursionDepth" direction="in"/>
   <arg type="as" name="propertyNames" direction="in"/>
   <arg type="u" name="revision" direction="out"/><arg type="(ia{sv}av)" name="layout" direction="out"/>
  </method>
  <method name="GetGroupProperties">
   <arg type="ai" name="ids" direction="in"/><arg type="as" name="propertyNames" direction="in"/>
   <arg type="a(ia{sv})" name="properties" direction="out"/>
  </method>
  <method name="GetProperty">
   <arg type="i" name="id" direction="in"/><arg type="s" name="name" direction="in"/>
   <arg type="v" name="value" direction="out"/>
  </method>
  <method name="Event">
   <arg type="i" name="id" direction="in"/><arg type="s" name="eventId" direction="in"/>
   <arg type="v" name="data" direction="in"/><arg type="u" name="timestamp" direction="in"/>
  </method>
  <method name="EventGroup">
   <arg type="a(isvu)" name="events" direction="in"/><arg type="ai" name="idErrors" direction="out"/>
  </method>
  <method name="AboutToShow"><arg type="i" name="id" direction="in"/><arg type="b" name="needUpdate" direction="out"/></method>
  <method name="AboutToShowGroup">
   <arg type="ai" name="ids" direction="in"/><arg type="ai" name="updatesNeeded" direction="out"/>
   <arg type="ai" name="idErrors" direction="out"/>
  </method>
  <signal name="ItemsPropertiesUpdated"><arg type="a(ia{sv})"/><arg type="a(ias)"/></signal>
  <signal name="LayoutUpdated"><arg type="u"/><arg type="i"/></signal>
  <signal name="ItemActivationRequested"><arg type="i"/><arg type="u"/></signal>
  <property name="Version" type="u" access="read"/>
  <property name="TextDirection" type="s" access="read"/>
  <property name="Status" type="s" access="read"/>
  <property name="IconThemePath" type="as" access="read"/>
 </interface>
</node>
"""

# State → (icon name, tray label, colour of the generated pixmap).
STATES = {
    "idle": ("be.arte.LocalDictate-idle", "Au repos", (0x1C, 0x71, 0xD8)),
    "listening": ("be.arte.LocalDictate-listening", "Écoute", (0xE0, 0x1B, 0x24)),
    "processing": ("be.arte.LocalDictate-processing", "Transcription", (0xE6, 0x61, 0x00)),
    "unavailable": ("be.arte.LocalDictate-idle", "Indisponible", (0x77, 0x76, 0x7B)),
}


def pixmap(colour: tuple[int, int, int], size: int) -> tuple[int, int, bytes]:
    """A rounded square with five white bars (the app's waveform), as ARGB32 in network byte order."""
    y, x = np.mgrid[0:size, 0:size].astype(np.float32) + 0.5
    radius = size * 0.22
    inner = np.clip(np.stack([radius - x, x - (size - radius), np.zeros_like(x)]).max(0), 0, None)
    inner_y = np.clip(np.stack([radius - y, y - (size - radius), np.zeros_like(y)]).max(0), 0, None)
    alpha = np.clip(radius + 0.5 - np.hypot(inner, inner_y), 0, 1)
    rgb = np.empty((size, size, 3), dtype=np.float32)
    rgb[:] = colour
    half_width = max(0.75, size * 0.036)  # Bars about 7 % wide, 11.5 % apart.
    for index, height in enumerate((0.25, 0.55, 0.75, 0.5, 0.25)):
        centre = size * (0.27 + 0.115 * index)
        half = size * height / 2
        mask = (np.abs(x - centre) <= half_width) & (np.abs(y - size / 2) <= half)
        rgb[mask] = 255
    argb = np.empty((size, size, 4), dtype=np.uint8)
    argb[..., 0] = (alpha * 255).round()
    argb[..., 1:] = (rgb * alpha[..., None]).round().astype(np.uint8)  # Premultiplied, like the hosts expect.
    return size, size, argb.tobytes()


@dataclass
class MenuItem:
    label: str = ""
    enabled: bool = True
    visible: bool = True
    separator: bool = False
    action: Callable[[], None] | None = None

    def properties(self) -> dict:
        if self.separator:
            return {"type": GLib.Variant("s", "separator"), "visible": GLib.Variant("b", self.visible)}
        return {"label": GLib.Variant("s", self.label.replace("_", "__")), "enabled": GLib.Variant("b", self.enabled),
                "visible": GLib.Variant("b", self.visible)}


class Tray:
    def __init__(self, connection: Gio.DBusConnection, *, toggle: Callable, cancel: Callable, show: Callable,
                 settings: Callable, folder: Callable, quit: Callable,
                 on_availability: Callable[[bool], None] = lambda available: None,
                 icon_theme_path: str = ""):
        self.connection = connection
        self.on_availability = on_availability
        self.icon_theme_path = icon_theme_path
        self.state = "idle"
        self.detail = "Prêt"
        self.title = "LocalDictate"
        self.revision = 1
        self.available = False
        self._show = show
        self.items: dict[int, MenuItem] = {
            1: MenuItem("LocalDictate — Au repos", enabled=False),
            2: MenuItem("Démarrer la dictée", action=toggle),
            3: MenuItem("Annuler la dictée (garder le texte)", visible=False, action=cancel),
            4: MenuItem(separator=True),
            5: MenuItem("Ouvrir LocalDictate", action=show),
            6: MenuItem("Réglages…", action=settings),
            7: MenuItem("Dossier des dictées (son et texte)", action=folder),
            8: MenuItem(separator=True),
            9: MenuItem("Quitter LocalDictate", action=quit),
        }
        self._pixmaps = {state: [pixmap(colour, size) for size in (22, 32, 48)]
                         for state, (_name, _label, colour) in STATES.items()}
        self._registrations: list[int] = []
        self._watch = 0
        self._owner = 0
        self.bus_name = f"org.kde.StatusNotifierItem-{os.getpid()}-1"

    # Lifecycle -------------------------------------------------------------------------------------------------
    def start(self) -> None:
        item = Gio.DBusNodeInfo.new_for_xml(ITEM_XML).interfaces[0]
        menu = Gio.DBusNodeInfo.new_for_xml(MENU_XML).interfaces[0]
        self._registrations.append(self.connection.register_object(
            ITEM_PATH, item, self._item_call, self._item_property, None))
        self._registrations.append(self.connection.register_object(
            MENU_PATH, menu, self._menu_call, self._menu_property, None))
        self._owner = Gio.bus_own_name_on_connection(self.connection, self.bus_name, Gio.BusNameOwnerFlags.NONE,
                                                     None, None)
        self._watch = Gio.bus_watch_name_on_connection(self.connection, WATCHER, Gio.BusNameWatcherFlags.NONE,
                                                       self._watcher_appeared, self._watcher_vanished)

    def stop(self) -> None:
        if self._watch:
            Gio.bus_unwatch_name(self._watch)
            self._watch = 0
        for registration in self._registrations:
            self.connection.unregister_object(registration)
        self._registrations.clear()
        if self._owner:
            Gio.bus_unown_name(self._owner)
            self._owner = 0
        self._set_available(False)

    def _watcher_appeared(self, connection, _name, _owner) -> None:
        def registered(conn, result, _data=None):
            try:
                conn.call_finish(result)
                self._set_available(True)
            except GLib.Error:
                self._set_available(False)

        connection.call(WATCHER, "/StatusNotifierWatcher", WATCHER, "RegisterStatusNotifierItem",
                        GLib.Variant("(s)", (self.bus_name,)), None, Gio.DBusCallFlags.NONE, 5000, None,
                        registered, None)

    def _watcher_vanished(self, _connection, _name) -> None:
        self._set_available(False)

    def _set_available(self, available: bool) -> None:
        if available != self.available:
            self.available = available
            self.on_availability(available)

    # State -----------------------------------------------------------------------------------------------------
    def update(self, state: str, detail: str, can_toggle: bool) -> None:
        if state not in STATES:
            state = "idle"
        label = STATES[state][1]
        changed_icon = state != self.state
        self.state, self.detail = state, detail
        self.items[1].label = f"{label} — {detail}" if detail else label
        recording = state == "listening"
        self.items[2].label = "Arrêter et coller" if recording else "Démarrer la dictée"
        self.items[2].enabled = can_toggle
        self.items[3].visible = recording
        self.revision += 1
        self._emit_menu("LayoutUpdated", GLib.Variant("(ui)", (self.revision, 0)))
        if changed_icon:
            self._emit_item("NewIcon", None)
            self._emit_item("NewStatus", GLib.Variant("(s)", (self._status(),)))
        self._emit_item("NewToolTip", None)

    def _status(self) -> str:
        # Always "Active": a "Passive" item may be hidden in the overflow, and the state must stay visible.
        return "Active"

    def _emit_item(self, signal: str, parameters) -> None:
        if self._registrations:
            self.connection.emit_signal(None, ITEM_PATH, "org.kde.StatusNotifierItem", signal, parameters)

    def _emit_menu(self, signal: str, parameters) -> None:
        if self._registrations:
            self.connection.emit_signal(None, MENU_PATH, "com.canonical.dbusmenu", signal, parameters)

    # StatusNotifierItem ----------------------------------------------------------------------------------------
    def _item_property(self, _connection, _sender, _path, _interface, name):
        icon_name, label, _colour = STATES[self.state]
        pixmaps = GLib.Variant("a(iiay)", self._pixmaps[self.state])
        values = {
            "Category": GLib.Variant("s", "ApplicationStatus"),
            "Id": GLib.Variant("s", "localdictate"),
            "Title": GLib.Variant("s", self.title),
            "Status": GLib.Variant("s", self._status()),
            "WindowId": GLib.Variant("u", 0),
            "IconThemePath": GLib.Variant("s", self.icon_theme_path),
            "IconName": GLib.Variant("s", icon_name),
            "IconPixmap": pixmaps,
            "OverlayIconName": GLib.Variant("s", ""),
            "OverlayIconPixmap": GLib.Variant("a(iiay)", []),
            "AttentionIconName": GLib.Variant("s", ""),
            "AttentionIconPixmap": GLib.Variant("a(iiay)", []),
            "AttentionMovieName": GLib.Variant("s", ""),
            "ToolTip": GLib.Variant("(sa(iiay)ss)", (icon_name, self._pixmaps[self.state],
                                                      f"LocalDictate — {label}", self.detail)),
            "ItemIsMenu": GLib.Variant("b", False),
            "Menu": GLib.Variant("o", MENU_PATH),
        }
        return values.get(name)

    def _item_call(self, _connection, _sender, _path, _interface, method, _parameters, invocation) -> None:
        if method in ("Activate", "SecondaryActivate"):
            GLib.idle_add(lambda: (self._show(), False)[1])
        invocation.return_value(None)

    # DBusMenu --------------------------------------------------------------------------------------------------
    def _layout(self) -> GLib.Variant:
        children = [GLib.Variant("(ia{sv}av)", (item_id, item.properties(), []))
                    for item_id, item in self.items.items()]
        return (0, {"children-display": GLib.Variant("s", "submenu")}, children)

    def _menu_property(self, _connection, _sender, _path, _interface, name):
        return {"Version": GLib.Variant("u", 3), "TextDirection": GLib.Variant("s", "ltr"),
                "Status": GLib.Variant("s", "normal"), "IconThemePath": GLib.Variant("as", [])}.get(name)

    def _menu_call(self, _connection, _sender, _path, _interface, method, parameters, invocation) -> None:
        args = parameters.unpack()
        if method == "GetLayout":
            parent = args[0]
            if parent == 0:
                invocation.return_value(GLib.Variant("(u(ia{sv}av))", (self.revision, self._layout())))
            elif parent in self.items:
                invocation.return_value(GLib.Variant("(u(ia{sv}av))",
                                                     (self.revision, (parent, self.items[parent].properties(), []))))
            else:
                invocation.return_dbus_error("com.canonical.dbusmenu.Error.UnknownId", "unknown id")
        elif method == "GetGroupProperties":
            ids = args[0] or list(self.items)
            result = [(i, self.items[i].properties()) for i in ids if i in self.items]
            invocation.return_value(GLib.Variant("(a(ia{sv}))", (result,)))
        elif method == "GetProperty":
            item = self.items.get(args[0])
            value = item.properties().get(args[1]) if item else None
            if value is None:
                invocation.return_dbus_error("com.canonical.dbusmenu.Error.UnknownProperty", "unknown property")
            else:
                invocation.return_value(GLib.Variant("(v)", (value,)))
        elif method == "Event":
            self._event(args[0], args[1])
            invocation.return_value(None)
        elif method == "EventGroup":
            errors = [event[0] for event in args[0] if event[0] not in self.items and event[0] != 0]
            for event in args[0]:
                self._event(event[0], event[1])
            invocation.return_value(GLib.Variant("(ai)", (errors,)))
        elif method == "AboutToShow":
            invocation.return_value(GLib.Variant("(b)", (False,)))
        elif method == "AboutToShowGroup":
            invocation.return_value(GLib.Variant("(aiai)", ([], [])))
        else:
            invocation.return_value(None)

    def _event(self, item_id: int, event: str) -> None:
        item = self.items.get(item_id)
        if event == "clicked" and item is not None and item.action is not None and item.enabled and item.visible:
            GLib.idle_add(lambda: (item.action(), False)[1])
