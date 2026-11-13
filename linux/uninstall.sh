#!/bin/sh
# Removes the program. Keeps your history, recordings, models and settings unless --all is given.
set -eu
prefix="${XDG_DATA_HOME:-$HOME/.local/share}"
config="${XDG_CONFIG_HOME:-$HOME/.config}"
localdictate --quit 2>/dev/null || true
rm -rf "$prefix/localdictate-app"
rm -f "$HOME/.local/bin/localdictate" "$prefix/applications/be.arte.LocalDictate.desktop" \
  "$prefix/icons/hicolor/scalable/apps/be.arte.LocalDictate.svg" \
  "$prefix/icons/hicolor/scalable/apps/be.arte.LocalDictate-idle.svg" \
  "$prefix/icons/hicolor/scalable/apps/be.arte.LocalDictate-listening.svg" \
  "$prefix/icons/hicolor/scalable/apps/be.arte.LocalDictate-processing.svg" "$config/autostart/be.arte.LocalDictate.desktop"
if [ "${1:-}" = "--all" ]; then
  rm -rf "$prefix/localdictate" "$config/localdictate"
  echo "Programme, historique, enregistrements, modèles et réglages supprimés."
else
  echo "Programme supprimé. Données gardées dans $prefix/localdictate (relancez avec --all pour tout effacer)."
fi
