"""Portal clients against a fake portal on a private D-Bus (test double, never shipped). This checks the D-Bus
signatures and the request/response flow; it does not prove what GNOME or KDE actually do."""
import os
import threading
import unittest

try:
    import gi

    gi.require_version("Gtk", "4.0")
    from gi.repository import Gio, GLib
except (ImportError, ValueError):  # pragma: no cover
    Gio = None

XML = """
<node>
 <interface name="org.freedesktop.portal.GlobalShortcuts">
  <method name="CreateSession"><arg type="a{sv}" direction="in"/><arg type="o" direction="out"/></method>
  <method name="BindShortcuts"><arg type="o" direction="in"/><arg type="a(sa{sv})" direction="in"/>
   <arg type="s" direction="in"/><arg type="a{sv}" direction="in"/><arg type="o" direction="out"/></method>
  <property name="version" type="u" access="read"/>
 </interface>
 <interface name="org.freedesktop.portal.RemoteDesktop">
  <method name="CreateSession"><arg type="a{sv}" direction="in"/><arg type="o" direction="out"/></method>
  <method name="SelectDevices"><arg type="o" direction="in"/><arg type="a{sv}" direction="in"/><arg type="o" direction="out"/></method>
  <method name="Start"><arg type="o" direction="in"/><arg type="s" direction="in"/><arg type="a{sv}" direction="in"/><arg type="o" direction="out"/></method>
  <method name="NotifyKeyboardKeysym"><arg type="o" direction="in"/><arg type="a{sv}" direction="in"/><arg type="i" direction="in"/><arg type="u" direction="in"/></method>
  <property name="version" type="u" access="read"/>
 </interface>
 <interface name="org.freedesktop.portal.Clipboard">
  <method name="RequestClipboard"><arg type="o" direction="in"/><arg type="a{sv}" direction="in"/></method>
  <method name="SetSelection"><arg type="o" direction="in"/><arg type="a{sv}" direction="in"/></method>
  <method name="SelectionWrite"><arg type="o" direction="in"/><arg type="u" direction="in"/><arg type="h" direction="out"/></method>
  <method name="SelectionWriteDone"><arg type="o" direction="in"/><arg type="u" direction="in"/><arg type="b" direction="in"/></method>
  <property name="version" type="u" access="read"/>
 </interface>
</node>
"""


class FakePortal:
    def __init__(self, connection):
        self.connection = connection
        self.calls = []
        self.keys = []
        self.received = b""
        self.done = None
        self.restore_tokens = []
        node = Gio.DBusNodeInfo.new_for_xml(XML)
        for interface in node.interfaces:
            connection.register_object("/org/freedesktop/portal/desktop", interface, self.method, self.get_property, None)

    def get_property(self, connection, sender, path, interface, name):
        return GLib.Variant("u", 2)

    def respond(self, sender, token, results):
        path = f"/org/freedesktop/portal/desktop/request/{sender[1:].replace('.', '_')}/{token}"
        source = GLib.idle_source_new()
        source.set_callback(lambda *a: self.connection.emit_signal(
            sender, path, "org.freedesktop.portal.Request", "Response", GLib.Variant("(ua{sv})", (0, results))) and False)
        source.attach(GLib.MainContext.get_thread_default())
        return path

    def method(self, connection, sender, path, interface, method, parameters, invocation):
        args = parameters.unpack()
        self.calls.append((interface.rsplit(".", 1)[1], method))
        if method == "CreateSession":
            session = f"/org/freedesktop/portal/desktop/session/x/{args[0]['session_handle_token']}"
            request = self.respond(sender, args[0]["handle_token"], {"session_handle": GLib.Variant("s", session)})
            invocation.return_value(GLib.Variant("(o)", (request,)))
        elif method == "BindShortcuts":
            self.bound = args[1]
            shortcuts = [(sid, {"trigger_description": GLib.Variant("s", "Ctrl+Alt+D" if sid == "toggle" else "")})
                         for sid, _opts in args[1]]
            request = self.respond(sender, args[3]["handle_token"], {"shortcuts": GLib.Variant("a(sa{sv})", shortcuts)})
            invocation.return_value(GLib.Variant("(o)", (request,)))
            self.session = args[0]
            self.sender = sender
        elif method == "SelectDevices":
            self.restore_tokens.append(args[1].get("restore_token"))
            self.select_options = args[1]
            request = self.respond(sender, args[1]["handle_token"], {})
            invocation.return_value(GLib.Variant("(o)", (request,)))
        elif method == "Start":
            request = self.respond(sender, args[2]["handle_token"], {
                "devices": GLib.Variant("u", 1), "clipboard_enabled": GLib.Variant("b", True),
                "restore_token": GLib.Variant("s", "token-2")})
            invocation.return_value(GLib.Variant("(o)", (request,)))
        elif method == "SetSelection":
            self.mime_types = args[1]["mime_types"]
            self.rd_session, self.rd_sender = args[0], sender
            invocation.return_value(None)
        elif method == "NotifyKeyboardKeysym":
            self.keys.append((args[2], args[3]))
            if len(self.keys) == 4:  # Ctrl+V complete: the focused app asks for the clipboard data.
                connection.emit_signal(sender, "/org/freedesktop/portal/desktop", "org.freedesktop.portal.Clipboard",
                                       "SelectionTransfer", GLib.Variant("(osu)", (self.rd_session, "text/plain;charset=utf-8", 7)))
            invocation.return_value(None)
        elif method == "SelectionWrite":
            read_end, write_end = os.pipe()
            self.read_end = read_end
            fds = Gio.UnixFDList.new()
            fds.append(write_end)
            os.close(write_end)
            invocation.return_value_with_unix_fd_list(GLib.Variant("(h)", (0,)), fds)
        elif method == "SelectionWriteDone":
            self.received = os.read(self.read_end, 65536)
            os.close(self.read_end)
            self.done = (args[1], args[2])
            invocation.return_value(None)
        else:
            invocation.return_value(None)


@unittest.skipIf(Gio is None, "PyGObject not available")
class PortalTest(unittest.TestCase):
    def setUp(self):
        self.bus = Gio.TestDBus.new(Gio.TestDBusFlags.NONE)
        self.bus.up()
        address = self.bus.get_bus_address()
        flags = Gio.DBusConnectionFlags.AUTHENTICATION_CLIENT | Gio.DBusConnectionFlags.MESSAGE_BUS_CONNECTION
        ready = threading.Event()
        self.server_context = GLib.MainContext.new()
        self.server_loop = GLib.MainLoop.new(self.server_context, False)

        def serve():
            # The fake portal runs on its own thread, like a separate process: the client's synchronous
            # property reads must not deadlock it.
            self.server_context.push_thread_default()
            self.server = Gio.DBusConnection.new_for_address_sync(address, flags, None, None)
            self.fake = FakePortal(self.server)
            Gio.bus_own_name_on_connection(self.server, "org.freedesktop.portal.Desktop",
                                           Gio.BusNameOwnerFlags.NONE, None, None)
            idle = GLib.idle_source_new()
            idle.set_callback(lambda *a: ready.set() or False)
            idle.attach(self.server_context)
            self.server_loop.run()
            self.server_context.pop_thread_default()

        self.thread = threading.Thread(target=serve, daemon=True)
        self.thread.start()
        ready.wait(5)
        self.client = Gio.DBusConnection.new_for_address_sync(address, flags, None, None)
        self.context = GLib.MainContext.default()
        self.spin(lambda: False, 0.3)

    def tearDown(self):
        self.client.close_sync(None)
        self.server_loop.quit()
        self.thread.join(5)
        self.server.close_sync(None)
        self.bus.down()

    def spin(self, until, seconds=5.0):
        deadline = GLib.get_monotonic_time() + int(seconds * 1e6)
        while GLib.get_monotonic_time() < deadline:
            self.context.iteration(False)
            if until():
                return True
        return False

    def test_shortcut_bind_and_activation(self):
        from localdictate.portals import GlobalShortcuts, Portal

        activated, statuses = [], []
        shortcuts = GlobalShortcuts(Portal(self.client), activated.append, lambda m, t: statuses.append((m, dict(t))))
        shortcuts.start([("toggle", "Dicter", "CTRL+ALT+d"), ("cancel", "Annuler", None)])
        self.assertTrue(self.spin(lambda: statuses))
        self.assertEqual(statuses[-1], ("Raccourci actif.", {"toggle": "Ctrl+Alt+D", "cancel": ""}))
        self.assertEqual(self.fake.bound[0][1]["preferred_trigger"], "CTRL+ALT+d")
        self.assertNotIn("preferred_trigger", self.fake.bound[1][1])
        self.server.emit_signal(self.fake.sender, "/org/freedesktop/portal/desktop",
                                "org.freedesktop.portal.GlobalShortcuts", "Activated",
                                GLib.Variant("(osta{sv})", (self.fake.session, "toggle", 1, {})))
        self.assertTrue(self.spin(lambda: activated))
        self.assertEqual(activated, ["toggle"])

    def test_paste_session_clipboard_and_keys(self):
        from localdictate.paste import Paster
        from localdictate.portals import PasteSession, Portal

        tokens = {"value": "token-1"}
        session = PasteSession(Portal(self.client), lambda: tokens["value"], lambda t: tokens.update(value=t))
        results = []
        Paster(session).deliver("Bonjour, ça va ?", True, "ctrl-v", lambda i, m: results.append(i), key_delay_ms=1)
        self.assertTrue(self.spin(lambda: self.fake.done is not None))
        self.assertEqual(results, ["pasted"])
        self.assertEqual(self.fake.received.decode("utf-8"), "Bonjour, ça va ?")
        self.assertEqual(self.fake.done, (7, True))
        self.assertEqual(self.fake.keys, [(0xFFE3, 1), (0x76, 1), (0x76, 0), (0xFFE3, 0)])
        self.assertEqual(self.fake.restore_tokens, ["token-1"])
        self.assertEqual(self.fake.select_options["persist_mode"], 2)
        self.assertEqual(self.fake.select_options["types"], 1)
        self.assertEqual(tokens["value"], "token-2")
        self.assertIn(("Clipboard", "RequestClipboard"), self.fake.calls)
        self.assertLess(self.fake.calls.index(("Clipboard", "RequestClipboard")),
                        self.fake.calls.index(("RemoteDesktop", "Start")))
        # A second dictation reuses the open session: no new consent.
        Paster(session).deliver("Deux", False, "ctrl-v", lambda i, m: results.append(i))
        self.assertTrue(self.spin(lambda: len(results) == 2))
        self.assertEqual(results[-1], "copied")
        self.assertEqual(self.fake.calls.count(("RemoteDesktop", "CreateSession")), 1)


if __name__ == "__main__":
    unittest.main()
