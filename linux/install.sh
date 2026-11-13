#!/bin/sh
# Installs LocalDictate for the current user (no root): a private virtual environment with the pinned,
# hash-checked Python packages, the app code, a `localdictate` command, a menu entry and an icon.
# The speech models are NOT downloaded here: the app asks for it explicitly (« Préparer »).
set -eu

here=$(cd "$(dirname "$0")" && pwd)
prefix="${XDG_DATA_HOME:-$HOME/.local/share}"
app="$prefix/localdictate-app"
bin="$HOME/.local/bin"

fail() { printf 'Erreur : %s\n' "$1" >&2; exit 1; }

command -v python3 >/dev/null || fail "python3 est introuvable."
python3 -c 'import sys; sys.exit(0 if (3, 12) <= sys.version_info[:2] <= (3, 14) else 1)' ||
  fail "Python 3.12 à 3.14 requis (trouvé : $(python3 -V 2>&1))."

missing=$(python3 - <<'PY'
import sys
missing = []
try:
    import gi
except ImportError:
    print("PyGObject (python3-gobject / python3-gi)"); sys.exit()
for namespace, version, package in (("Gtk", "4.0", "GTK 4 (gtk4 / gir1.2-gtk-4.0)"),
                                    ("Adw", "1", "libadwaita (libadwaita / gir1.2-adw-1)"),
                                    ("Gst", "1.0", "GStreamer (gstreamer1 / gir1.2-gstreamer-1.0)")):
    try:
        gi.require_version(namespace, version)
        __import__("gi.repository." + namespace)
    except (ImportError, ValueError):
        missing.append(package)
if not missing:
    from gi.repository import Gst
    Gst.init(None)
    for element, package in (("appsink", "gstreamer1-plugins-base / gstreamer1.0-plugins-base"),
                             ("audioresample", "gstreamer1-plugins-base / gstreamer1.0-plugins-base"),
                             ("playbin", "gstreamer1-plugins-base / gstreamer1.0-plugins-base")):
        if Gst.ElementFactory.find(element) is None:
            missing.append(package)
    if not (Gst.ElementFactory.find("pipewiresrc") or Gst.ElementFactory.find("pulsesrc")):
        missing.append("source micro GStreamer (pipewire-gstreamer / gstreamer1.0-pipewire, ou plugins-good)")
print("\n".join(dict.fromkeys(missing)))
PY
)
if [ -n "$missing" ]; then
  printf 'Paquets système manquants :\n%s\n\n' "$missing" >&2
  printf 'Fedora : sudo dnf install python3-gobject gtk4 libadwaita gstreamer1-plugins-base pipewire-gstreamer\n' >&2
  printf 'Ubuntu/Debian : sudo apt install python3-gi gir1.2-gtk-4.0 gir1.2-adw-1 gir1.2-gstreamer-1.0 gstreamer1.0-plugins-base gstreamer1.0-pipewire\n' >&2
  exit 1
fi

echo "Environnement Python privé : $app/venv"
mkdir -p "$app"
if [ ! -x "$app/venv/bin/python" ]; then
  python3 -m venv --system-site-packages "$app/venv"
fi
"$app/venv/bin/python" -m pip install --quiet --disable-pip-version-check --require-hashes --only-binary=:all: \
  -r "$here/requirements.lock"

echo "Code de l'application : $app/lib"
rm -rf "$app/lib.new"
mkdir -p "$app/lib.new"
cp -R "$here/localdictate" "$app/lib.new/"
find "$app/lib.new" -name '__pycache__' -prune -exec rm -rf {} +
rm -rf "$app/lib"
mv "$app/lib.new" "$app/lib"

mkdir -p "$bin"
cat > "$bin/localdictate" <<SH
#!/bin/sh
PYTHONPATH="$app/lib" exec "$app/venv/bin/python" -m localdictate "\$@"
SH
chmod 755 "$bin/localdictate"

install -D -m 644 "$here/data/be.arte.LocalDictate.desktop" "$prefix/applications/be.arte.LocalDictate.desktop"
install -D -m 644 "$here/data/be.arte.LocalDictate.svg" "$prefix/icons/hicolor/scalable/apps/be.arte.LocalDictate.svg"
for icon in "$here"/data/icons/*.svg; do
  install -D -m 644 "$icon" "$prefix/icons/hicolor/scalable/apps/$(basename "$icon")"
done
command -v gtk-update-icon-cache >/dev/null && gtk-update-icon-cache -q -t "$prefix/icons/hicolor" 2>/dev/null || true
command -v update-desktop-database >/dev/null && update-desktop-database "$prefix/applications" 2>/dev/null || true

echo
echo "Installé. Lancez « LocalDictate » depuis le menu des applications (ou la commande localdictate),"
echo "puis « Préparer » pour télécharger le modèle (environ 670 Mo, une seule fois)."
case ":$PATH:" in *":$bin:"*) ;; *) echo "Note : ajoutez $bin à votre PATH pour la commande localdictate.";; esac
