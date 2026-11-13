"""Private D-Bus for tests: a "server" connection served on its own thread (like another process) and a client
connection on the test thread's default main context. Test doubles only."""
import threading

import gi

gi.require_version("Gtk", "4.0")
from gi.repository import Gio, GLib  # noqa: E402


class PrivateBus:
    def __init__(self, setup_server):
        self.bus = Gio.TestDBus.new(Gio.TestDBusFlags.NONE)
        self.bus.up()
        self.address = address = self.bus.get_bus_address()
        flags = Gio.DBusConnectionFlags.AUTHENTICATION_CLIENT | Gio.DBusConnectionFlags.MESSAGE_BUS_CONNECTION
        ready = threading.Event()
        self.server_context = GLib.MainContext.new()
        self.server_loop = GLib.MainLoop.new(self.server_context, False)

        def serve():
            self.server_context.push_thread_default()
            self.server = Gio.DBusConnection.new_for_address_sync(address, flags, None, None)
            self.state = setup_server(self.server, address)
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

    def run_on_server(self, fn):
        source = GLib.idle_source_new()
        source.set_callback(lambda *a: fn() and False)
        source.attach(self.server_context)

    def spin(self, until, seconds=5.0):
        deadline = GLib.get_monotonic_time() + int(seconds * 1e6)
        while GLib.get_monotonic_time() < deadline:
            self.context.iteration(False)
            if until():
                return True
        return False

    def close(self):
        self.client.close_sync(None)
        self.server_loop.quit()
        self.thread.join(5)
        self.server.close_sync(None)
        self.bus.down()


def own(connection, name):
    Gio.bus_own_name_on_connection(connection, name, Gio.BusNameOwnerFlags.NONE, None, None)
