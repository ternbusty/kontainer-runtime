#!/usr/bin/env bash
set -euo pipefail

REPO="ternbusty/kontainer-runtime"
BIN_DIR="/usr/local/bin"

VERSION="${KONTAINER_VERSION:-}"
if [ "${1:-}" = "--version" ] && [ -n "${2:-}" ]; then
  VERSION="$2"
  shift 2
fi

case "$(uname -m)" in
  x86_64|amd64) ARCH="amd64" ;;
  aarch64|arm64) ARCH="arm64" ;;
  *) echo "Unsupported architecture: $(uname -m)" >&2; exit 1 ;;
esac

if [ -z "$VERSION" ] || [ "$VERSION" = "latest" ]; then
  META_URL="https://api.github.com/repos/$REPO/releases/latest"
else
  META_URL="https://api.github.com/repos/$REPO/releases/tags/$VERSION"
fi
JSON="$(curl -fsSL "$META_URL")"

TAG_NAME="$(echo "$JSON" | grep -oP '"tag_name":\s*"v?\K[^"]+')"
BIN_URL="https://github.com/$REPO/releases/download/v${TAG_NAME}/kontainer-runtime_${TAG_NAME}_linux_${ARCH}"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

curl -fsSL "$BIN_URL" -o "$TMP/kontainer-runtime"
chmod +x "$TMP/kontainer-runtime"
install -m 0755 "$TMP/kontainer-runtime" "$BIN_DIR/kontainer-runtime"

echo "Installed kontainer-runtime v${TAG_NAME} (linux/${ARCH}) to ${BIN_DIR}/kontainer-runtime"
