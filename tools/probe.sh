#!/usr/bin/env bash
# tools/probe.sh -- run tools/probe.ps1 on the Windows host from WSL.
#
# WSL in mirrored networking mode cannot receive SSDP replies (Hyper-V firewall
# default inbound Block), so the prober must run on the Windows host. PowerShell can refuse scripts on
# \\wsl.localhost UNC paths, so the script is copied to the Windows %TEMP% first.
#
# Usage: tools/probe.sh [-PortMin 3100] [-PortMax 3109] [-Target <ip>] [-LoopSeconds 60] [-Minutes 35] [-ST <urn>]
# All arguments are passed through unchanged. Only M-SEARCH and HTTP GET are sent; it cannot launch a cast.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
src="$here/probe.ps1"
[ -f "$src" ] || { echo "probe.sh: missing $src" >&2; exit 1; }

win_temp="$(cmd.exe /c 'echo %TEMP%' 2>/dev/null | tr -d '\r')"
[ -n "$win_temp" ] || { echo "probe.sh: could not resolve the Windows TEMP directory" >&2; exit 1; }
wsl_temp="$(wslpath -u "$win_temp")"

dest="$wsl_temp/t2u-probe.ps1"
cp "$src" "$dest"
win_script="$(wslpath -w "$dest")"

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$win_script" "$@" | tr -d '\r'
