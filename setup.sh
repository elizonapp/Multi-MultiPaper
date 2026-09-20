#!/usr/bin/env bash
#
# setup.sh - first-start version selector for MultiPaper.
#
# Pick which Purpur version MultiPaper is built against and store the choice in
# gradle.properties (the "config"). mcVersion, the artifact version and the
# pinned upstream commit are then derived by build.gradle.kts.
#
# Usage:
#   ./setup.sh              interactive selection (menu)
#   ./setup.sh <version>    set a specific version non-interactively
#   ./setup.sh --list       list the selectable versions
#   ./setup.sh --show       show the currently selected version
#   ./setup.sh --help       show this help
#
# After selecting a version, refresh the pinned upstream commit with:
#   ./gradlew purpurRefLatest

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

REGISTRY="purpur-versions.properties"
CONFIG="gradle.properties"

AVAILABLE_VERSIONS=$(
  sed -n 's/^purpur\.version\.list=//p' "$REGISTRY" | tr ',' '\n' | tr -d ' \r'
)

fail() {
  echo "error: $*" >&2
  exit 1
}

# version_field <version> <field> - read a <version>.<field> value from the registry
version_field() {
  local version="$1" field="$2"
  sed -n "s/^${version}\\.${field}=//p" "$REGISTRY" | tail -n 1 | tr -d '\r'
}

current_version() {
  grep -E '^[[:space:]]*purpurVersion[[:space:]]*=' "$CONFIG" 2>/dev/null \
    | sed -E 's/^[[:space:]]*purpurVersion[[:space:]]*=[[:space:]]*//' \
    | tail -n 1 || true
}

set_config_version() {
  local version="$1"
  if ! printf '%s\n' "$AVAILABLE_VERSIONS" | grep -Fxq "$version"; then
    fail "unknown version '$version'. Select one of: $(printf '%s, ' $AVAILABLE_VERSIONS | sed 's/, $//')"
  fi
  if grep -qE '^[[:space:]]*purpurVersion[[:space:]]*=' "$CONFIG"; then
    # -i.bak works on both GNU and BSD sed; remove the backup afterwards
    sed -i.bak -E "s/^([[:space:]]*purpurVersion[[:space:]]*=).*/\1 $version/" "$CONFIG"
    rm -f "$CONFIG.bak"
  else
    printf '\n# Selected via ./setup.sh (see purpur-versions.properties)\npurpurVersion = %s\n' "$version" >> "$CONFIG"
  fi
  echo "purpurVersion = $version (saved to $CONFIG)"
}

list_versions() {
  echo "Available Purpur versions:"
  for version in $AVAILABLE_VERSIONS; do
    local channel mcversion
    channel="$(version_field "$version" channel)"
    mcversion="$(version_field "$version" mcVersion)"
    printf '  %-8s  Minecraft %-9s  %s\n' "$version" "$mcversion" "$channel"
  done
}

show_version() {
  local version
  version="$(current_version)"
  if [[ -z "$version" ]]; then
    echo "No version configured yet. Run ./setup.sh to select one."
  else
    echo "purpurVersion = $version (Minecraft $(version_field "$version" mcVersion), branch $(version_field "$version" branch))"
  fi
}

prompt_version() {
  local current="$1"
  printf '\nSelect the Purpur version MultiPaper should be built against:\n'
  local i=0
  for version in $AVAILABLE_VERSIONS; do
    i=$((i + 1))
    local marker=""
    [[ "$version" == "$current" ]] && marker=" (current)"
    printf '  %d) %s  (Minecraft %s, %s)%s\n' \
      "$i" "$version" "$(version_field "$version" mcVersion)" \
      "$(version_field "$version" channel)" "$marker"
  done

  local choice
  while true; do
    printf 'Enter a number (1-%d): ' "$i"
    read -r choice
    if [[ "$choice" =~ ^[0-9]+$ ]] && (( choice >= 1 && choice <= i )); then
      break
    fi
    echo "Invalid choice. Try again." >&2
  done

  local selected
  selected=$(printf '%s\n' "$AVAILABLE_VERSIONS" | sed -n "${choice}p")
  set_config_version "$selected"
}

# --- argument handling -------------------------------------------------------

case "${1:-}" in
  --help|-h)
    grep '^#' "$0" | sed 's/^#\{0,1\}[[:space:]]*//'
    exit 0
    ;;
  --list|-l)
    list_versions
    exit 0
    ;;
  --show)
    show_version
    exit 0
    ;;
  --*)
    fail "unknown option '$1' ('./setup.sh --help' for usage)"
    ;;
  "")
    current="$(current_version)"
    if [[ -n "$current" ]]; then
      echo "Currently configured: purpurVersion = $current"
      printf 'Keep this version? [Y/n]: '
      read -r answer
      if [[ ! "$answer" =~ ^([nN]|[nN][oO])$ ]]; then
        echo "Keeping purpurVersion = $current."
        exit 0
      fi
    fi
    prompt_version "$current"
    ;;
  *)
    set_config_version "$1"
    ;;
esac

echo
echo "Next steps:"
echo "  ./multipaper                   # select/apply patches and launch (runtime entry point)"
echo "  ./multipaper --help            # or: ./multipaper --purpur-version=<version>"
echo "  ./gradlew purpurRefLatest      # update the pinned commit for this version"
echo "  ./gradlew applyPatches         # apply the MultiPaper patches onto Purpur (manual)"