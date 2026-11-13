import unittest

from gi.repository import Gio, GLib

from tests.dbus_fixture import PrivateBus

PLAYER_XML = """<node><interface name="org.mpris.MediaPlayer2.Player">
 <method name="Pause"/><method name="Play"/>
 <property name="PlaybackStatus" type="s" access="read"/>
</interface></node>"""


class Players:
    """Three fake MPRIS players, each on its own connection (they are separate apps)."""

    def __init__(self, address, statuses):
        self.status = dict(statuses)
        self.calls = []
        self.connections = []
        info = Gio.DBusNodeInfo.new_for_xml(PLAYER_XML).interfaces[0]
        flags = Gio.DBusConnectionFlags.AUTHENTICATION_CLIENT | Gio.DBusConnectionFlags.MESSAGE_BUS_CONNECTION
        for name in statuses:
            connection = Gio.DBusConnection.new_for_address_sync(address, flags, None, None)
            connection.register_object("/org/mpris/MediaPlayer2", info,
                                       lambda *args, name=name: self.method(name, *args),
                                       lambda *args, name=name: GLib.Variant("s", self.status[name]), None)
            connection.call_sync("org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus",
                                 "RequestName", GLib.Variant("(su)", ("org.mpris.MediaPlayer2." + name, 0)),
                                 None, Gio.DBusCallFlags.NONE, -1, None)
            self.connections.append(connection)

    def method(self, name, _conn, _sender, _path, _iface, method, _params, invocation):
        self.calls.append((name, method))
        self.status[name] = "Paused" if method == "Pause" else "Playing"
        invocation.return_value(None)


class MediaPauserTest(unittest.TestCase):
    def setUp(self):
        # The players live on the server thread, on their own connections to the same private bus.
        self.private = PrivateBus(lambda connection, address: Players(
            address, {"music": "Playing", "podcast": "Paused", "video": "Stopped"}))
        self.players = self.private.state

    def tearDown(self):
        for connection in self.players.connections:
            connection.close_sync(None)
        self.private.close()

    def test_pause_only_playing_and_resume_them(self):
        from localdictate.media import MediaPauser

        pauser = MediaPauser(self.private.client)
        pauser.pause()
        self.assertTrue(self.private.spin(lambda: pauser.paused == {"org.mpris.MediaPlayer2.music"}))
        self.assertEqual(self.players.calls, [("music", "Pause")])
        pauser.resume()
        self.assertTrue(self.private.spin(lambda: len(self.players.calls) == 2))
        self.assertEqual(self.players.calls[-1], ("music", "Play"))

    def test_player_changed_by_user_is_not_restarted(self):
        from localdictate.media import MediaPauser

        pauser = MediaPauser(self.private.client)
        pauser.pause()
        self.assertTrue(self.private.spin(lambda: pauser.paused))
        self.players.status["music"] = "Stopped"  # The user stopped it during the dictation.
        pauser.resume()
        self.private.spin(lambda: False, 0.5)
        self.assertEqual(self.players.calls, [("music", "Pause")])

    def test_short_dictation_resumes_late_pause(self):
        from localdictate.media import MediaPauser

        pauser = MediaPauser(self.private.client)
        pauser.pause()
        pauser.resume()  # Before the asynchronous pause completed.
        self.private.spin(lambda: len(self.players.calls) >= 2, 2)
        self.assertNotEqual(self.players.status["music"], "Paused")


if __name__ == "__main__":
    unittest.main()
