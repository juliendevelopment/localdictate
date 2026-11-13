"""Status icon against a fake StatusNotifierWatcher/host on a private D-Bus (test double). Checks the D-Bus
contract (registration, properties, menu layout and events), not how Plasma or GNOME draw it."""
import unittest

from gi.repository import Gio, GLib

from tests.dbus_fixture import PrivateBus, own

WATCHER_XML = """<node><interface name="org.kde.StatusNotifierWatcher">
 <method name="RegisterStatusNotifierItem"><arg type="s" direction="in"/></method>
</interface></node>"""


class Watcher:
    def __init__(self, connection):
        self.registered = []
        info = Gio.DBusNodeInfo.new_for_xml(WATCHER_XML).interfaces[0]
        connection.register_object("/StatusNotifierWatcher", info, self.method, None, None)
        own(connection, "org.kde.StatusNotifierWatcher")

    def method(self, _conn, sender, _path, _iface, _method, parameters, invocation):
        self.registered.append(parameters.unpack()[0])
        invocation.return_value(None)


class TrayTest(unittest.TestCase):
    def setUp(self):
        self.private = PrivateBus(lambda connection, address: Watcher(connection))
        self.watcher = self.private.state
        self.actions = []
        self.availability = []
        from localdictate.tray import Tray

        names = ("toggle", "cancel", "show", "settings", "folder", "quit")
        self.tray = Tray(self.private.client, on_availability=self.availability.append,
                         **{name: (lambda name=name: self.actions.append(name)) for name in names})
        self.tray.start()
        self.assertTrue(self.private.spin(lambda: self.availability == [True]))

    def tearDown(self):
        self.tray.stop()
        self.private.close()

    def host_call(self, path, interface, method, parameters):
        """The host side (watcher connection) calling the item, as Plasma or the GNOME extension would."""
        box = {}

        def done(connection, result, _data=None):
            try:
                box["value"] = connection.call_finish(result).unpack()
            except GLib.Error as error:
                box["error"] = error.message

        self.private.server.call(self.tray.bus_name, path, interface, method, parameters, None,
                                 Gio.DBusCallFlags.NONE, 3000, None, done, None)
        self.assertTrue(self.private.spin(lambda: box))
        self.assertNotIn("error", box, box.get("error"))
        return box["value"]

    def prop(self, name):
        return self.host_call("/StatusNotifierItem", "org.freedesktop.DBus.Properties", "Get",
                              GLib.Variant("(ss)", ("org.kde.StatusNotifierItem", name)))[0]

    def layout(self):
        _revision, (_root, _props, children) = self.host_call(
            "/MenuBar", "com.canonical.dbusmenu", "GetLayout", GLib.Variant("(iias)", (0, -1, [])))
        return {child[0]: child[1] for child in children}

    def test_registration_and_idle_state(self):
        self.assertEqual(self.watcher.registered, [self.tray.bus_name])
        self.assertEqual(self.prop("IconName"), "be.arte.LocalDictate-idle")
        self.assertEqual(self.prop("Menu"), "/MenuBar")
        pixmaps = self.prop("IconPixmap")
        self.assertEqual([(w, h) for w, h, _ in pixmaps], [(22, 22), (32, 32), (48, 48)])
        self.assertEqual(len(pixmaps[0][2]), 22 * 22 * 4)
        items = self.layout()
        self.assertEqual(items[2]["label"], "Démarrer la dictée")
        self.assertFalse(items[3]["visible"])
        self.assertEqual(items[4]["type"], "separator")

    def test_listening_state_updates_icon_tooltip_and_menu(self):
        signals = []
        self.private.server.signal_subscribe(None, "org.kde.StatusNotifierItem", None, "/StatusNotifierItem", None,
                                             Gio.DBusSignalFlags.NONE, lambda *a: signals.append(a[4]), None)
        self.private.spin(lambda: False, 0.2)
        self.tray.update("listening", "0:12", True)
        self.assertTrue(self.private.spin(lambda: "NewIcon" in signals))
        self.assertEqual(self.prop("IconName"), "be.arte.LocalDictate-listening")
        self.assertEqual(self.prop("ToolTip")[2], "LocalDictate — Écoute")
        items = self.layout()
        self.assertEqual(items[1]["label"], "Écoute — 0:12")
        self.assertEqual(items[2]["label"], "Arrêter et coller")
        self.assertTrue(items[3]["visible"])
        self.tray.update("processing", "", False)
        self.assertEqual(self.prop("IconName"), "be.arte.LocalDictate-processing")
        self.assertFalse(self.layout()[2]["enabled"])

    def test_menu_events_and_activate(self):
        for item in (6, 7, 2):
            self.host_call("/MenuBar", "com.canonical.dbusmenu", "Event",
                           GLib.Variant("(isvu)", (item, "clicked", GLib.Variant("s", ""), 0)))
        self.host_call("/StatusNotifierItem", "org.kde.StatusNotifierItem", "Activate", GLib.Variant("(ii)", (0, 0)))
        self.assertTrue(self.private.spin(lambda: len(self.actions) == 4))
        self.assertEqual(self.actions, ["settings", "folder", "toggle", "show"])
        # A hidden item (cancel while idle) does nothing.
        self.host_call("/MenuBar", "com.canonical.dbusmenu", "Event",
                       GLib.Variant("(isvu)", (3, "clicked", GLib.Variant("s", ""), 0)))
        self.private.spin(lambda: False, 0.2)
        self.assertEqual(len(self.actions), 4)


if __name__ == "__main__":
    unittest.main()
