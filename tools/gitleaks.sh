#!/usr/bin/env bash
# Pinned, checksum-verified gitleaks launcher (secret scanner). No root needed; CI reuses this script.
#
#   tools/gitleaks.sh [scan] [--ref REF] [--tree-only]   scan the history of REF (default HEAD) and the
#                                                        code-only tree of REF (GSD paths left out);
#                                                        non-zero exit on any finding
#   tools/gitleaks.sh version                            print the pinned version
#   tools/gitleaks.sh <anything else>                    passed straight to gitleaks
#
# The release tarball is downloaded to a temp directory and its SHA-256 is verified before it is
# extracted; a mismatch deletes the download and exits 1. Findings are printed redacted, so a real
# secret is never echoed into a log. Rules and allowlists: .gitleaks.toml (no fingerprint ignore file).
set -euo pipefail

# ---- pin (v8.30.1, linux x64). The SHA-256 was taken from the release's checksums file and
# ---- matched against the digest of the asset in the GitHub releases API.
GITLEAKS_VERSION="8.30.1"
GITLEAKS_SHA256="551f6fc83ea457d62a0d98237cbad105af8d557003051f41f3e7ca7b3f2470eb"
GITLEAKS_URL="https://github.com/gitleaks/gitleaks/releases/download/v${GITLEAKS_VERSION}/gitleaks_${GITLEAKS_VERSION}_linux_x64.tar.gz"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
INSTALL_DIR="${GITLEAKS_HOME:-$HOME/.local/share/gitleaks}/${GITLEAKS_VERSION}"
BIN="$INSTALL_DIR/gitleaks"
CONFIG="$REPO_ROOT/.gitleaks.toml"

log() { printf '[gitleaks] %s\n' "$*" >&2; }
die() { printf '[gitleaks] FATAL: %s\n' "$*" >&2; exit 1; }

install_gitleaks() {
  [ -x "$BIN" ] && return 0
  local tmp tarball actual
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' RETURN
  tarball="$tmp/gitleaks.tar.gz"
  log "downloading gitleaks ${GITLEAKS_VERSION}"
  curl -fsSL --retry 3 -o "$tarball" "$GITLEAKS_URL" || die "download failed: $GITLEAKS_URL"
  actual="$(sha256sum "$tarball" | cut -d' ' -f1)"
  if [ "$actual" != "$GITLEAKS_SHA256" ]; then
    rm -f "$tarball"
    die "sha256 mismatch for gitleaks ${GITLEAKS_VERSION}: expected $GITLEAKS_SHA256, got $actual (download deleted)"
  fi
  log "sha256 ok"
  mkdir -p "$INSTALL_DIR"
  tar -xzf "$tarball" -C "$INSTALL_DIR" gitleaks
  chmod +x "$BIN"
}

scan() {
  local ref="HEAD" tree_only=0
  while [ $# -gt 0 ]; do
    case "$1" in
      --ref) ref="${2:?--ref needs a value}"; shift 2 ;;
      --tree-only) tree_only=1; shift ;;
      *) die "unknown scan option: $1" ;;
    esac
  done
  git -C "$REPO_ROOT" rev-parse --verify --quiet "${ref}^{commit}" >/dev/null || die "unknown ref: $ref"
  log "version $("$BIN" version) ref $ref"

  local rc=0
  if [ "$tree_only" -eq 0 ]; then
    log "history of $ref"
    "$BIN" git "$REPO_ROOT" --config "$CONFIG" --log-opts="$ref" --redact --no-banner || rc=1
  fi

  local export_dir
  export_dir="$(mktemp -d)"
  trap 'rm -rf "$export_dir"' RETURN
  git -C "$REPO_ROOT" archive "$ref" -- . ':(exclude).planning' ':(exclude).claude' ':(exclude).gsd' ':(exclude)CLAUDE.md' \
    | tar -x -C "$export_dir"
  log "code-only tree of $ref"
  "$BIN" dir "$export_dir" --config "$CONFIG" --redact --no-banner || rc=1

  [ "$rc" -eq 0 ] && log "no findings" || log "FINDINGS (values redacted above)"
  return "$rc"
}

install_gitleaks
case "${1:-scan}" in
  scan) [ $# -gt 0 ] && shift; scan "$@" ;;
  version) "$BIN" version ;;
  *) exec "$BIN" "$@" ;;
esac
