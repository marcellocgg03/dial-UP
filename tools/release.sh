#!/usr/bin/env bash
# DialUP release procedure: turns a clean checkout into the release files and, on request, a DRAFT release.
#
#   tools/release.sh VERSION --dry-run     build, verify and write dist/ ; nothing is uploaded or tagged
#   tools/release.sh VERSION --publish     the same, then tag vVERSION, push that tag and create a DRAFT release
#   tools/release.sh -h                    this text
#
# VERSION is a semantic version such as 0.9.0-beta.1 (a hyphen makes the release a pre-release).
# Files written to dist/ (ignored by git, never committed):
#   DialUP-VERSION.apk, SHA256SUMS, RELEASE_NOTES-VERSION.md
# Signing material comes only from keystore.properties or the four DIALUP_* variables that Gradle reads itself;
# this script checks that they are present and never reads or prints a value.
# Publish mode only ever creates a draft. Making the release public stays a manual step after the APK from
# the draft has been installed and tried on the phone.
# Exit codes: 0 ok, 1 a check failed, 64 usage error.
set -euo pipefail

PUBLIC_REPO="marcellocgg03/dial-UP"
REQUIRED_CHECKS=(build-test lint gitleaks)

usage() { sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//'; }
log() { printf '[release] %s\n' "$*"; }
fail() { printf '[release] FAILED: %s\n' "$*" >&2; exit 1; }
usage_error() { printf '[release] %s\n' "$*" >&2; printf 'usage: tools/release.sh VERSION (--dry-run | --publish)\n' >&2; exit 64; }

# ---- arguments -----------------------------------------------------------------------------------
VERSION=""
MODE=""
for arg in "$@"; do
  case "$arg" in
    -h|--help) usage; exit 0 ;;
    --dry-run|--publish)
      [ -z "$MODE" ] || usage_error "give exactly one of --dry-run and --publish"
      MODE="${arg#--}" ;;
    -*) usage_error "unknown option: $arg" ;;
    *)
      [ -z "$VERSION" ] || usage_error "more than one version given"
      VERSION="$arg" ;;
  esac
done
[ -n "$VERSION" ] || usage_error "a version is required"
[ -n "$MODE" ] || usage_error "exactly one of --dry-run and --publish is required"
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z]+(\.[0-9A-Za-z]+)*)?$ ]] \
  || usage_error "not a semantic version: $VERSION (expected MAJOR.MINOR.PATCH with an optional -suffix)"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
TAG="v$VERSION"
APK_NAME="DialUP-$VERSION.apk"
NOTES_NAME="RELEASE_NOTES-$VERSION.md"
SIGNED=1

# ---- 1. pre-checks -------------------------------------------------------------------------------
log "checking $VERSION in $MODE mode"

[ -z "$(git status --porcelain)" ] || fail "the working tree is not clean (commit or remove the changes first)"

gradle_version="$(sed -nE 's/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' app/build.gradle.kts | head -n 1)"
[ "$gradle_version" = "$VERSION" ] || fail "versionName in app/build.gradle.kts is '${gradle_version:-none}', not $VERSION"

[ -f CHANGELOG.md ] || fail "CHANGELOG.md is missing"
heading="$(awk -v want="## [$VERSION] - " 'index($0, want) == 1 { print; exit }' CHANGELOG.md)"
[ -n "$heading" ] || fail "CHANGELOG.md has no heading '## [$VERSION] - YYYY-MM-DD'"
heading_date="${heading##* - }"
[[ "$heading_date" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] || fail "the CHANGELOG.md heading for $VERSION has no YYYY-MM-DD date"
[ "$(date -d "$heading_date" +%F 2>/dev/null || true)" = "$heading_date" ] \
  || fail "the CHANGELOG.md date $heading_date is not a real date"
# Section body: from the line after the heading to the next '## ' heading, minus the link reference lines.
changelog_body="$(awk -v want="## [$VERSION] - " '
  f && /^## / { exit }
  f && /^\[[^]]+\]: / { next }
  !f && index($0, want) == 1 { f = 1; next }
  f { print }
' CHANGELOG.md | sed '/./,$!d')"
[ -n "$(printf '%s' "$changelog_body" | tr -d '[:space:]')" ] || fail "the CHANGELOG.md section for $VERSION is empty"

# Signing material present? Gradle reads each value from keystore.properties first and the variable second.
signing_present() {
  local pair prop envvar
  for pair in storeFile:DIALUP_KEYSTORE_FILE storePassword:DIALUP_KEYSTORE_PASSWORD keyAlias:DIALUP_KEY_ALIAS keyPassword:DIALUP_KEY_PASSWORD; do
    prop="${pair%%:*}"; envvar="${pair##*:}"
    if [ -f keystore.properties ] && grep -Eq "^[[:space:]]*${prop}[[:space:]]*=[[:space:]]*[^[:space:]]" keystore.properties; then continue; fi
    [ -n "${!envvar:-}" ] || return 1
  done
  return 0
}
if signing_present; then
  log "signing material found (values are not read here)"
elif [ "$MODE" = "publish" ]; then
  fail "no signing material: provide keystore.properties or the four DIALUP_* variables"
else
  SIGNED=0
  log "WARNING: no signing material, the dry run result will be unsigned"
fi

if [ "$MODE" = "publish" ]; then
  branch="$(git rev-parse --abbrev-ref HEAD)"
  [ "$branch" = "main" ] || fail "publish runs from the main branch, this is '$branch'"
  origin_url="$(git remote get-url origin 2>/dev/null || true)"
  case "${origin_url%.git}" in
    "https://github.com/$PUBLIC_REPO"|"git@github.com:$PUBLIC_REPO") ;;
    *) fail "origin is not the public repository $PUBLIC_REPO" ;;
  esac
  command -v gh >/dev/null 2>&1 || fail "gh is not installed"
  gh auth status >/dev/null 2>&1 || fail "gh is not authenticated (gh auth status fails)"
  git fetch --quiet origin main || fail "git fetch origin main failed"
  head_sha="$(git rev-parse HEAD)"
  [ "$head_sha" = "$(git rev-parse origin/main)" ] || fail "HEAD is not the commit at the head of origin/main"
  if git rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
    [ "$(git rev-parse "$TAG^{commit}")" = "$head_sha" ] || fail "tag $TAG exists and does not point at HEAD"
  fi
  remote_tag="$(git ls-remote origin "refs/tags/$TAG^{}" "refs/tags/$TAG" | awk '{print $1}' | tail -n 1)"
  if [ -n "$remote_tag" ] && [ "$remote_tag" != "$head_sha" ]; then
    fail "tag $TAG already exists on origin and does not point at HEAD"
  fi
  passed="$(gh api "repos/$PUBLIC_REPO/commits/$head_sha/check-runs" --paginate \
    --jq '[.check_runs[] | select(.conclusion == "success") | .name] | .[]')" || fail "could not read the check runs of $head_sha"
  for check in "${REQUIRED_CHECKS[@]}"; do
    grep -Fxq "$check" <<<"$passed" || fail "CI check '$check' has not succeeded on $head_sha"
  done
  log "origin, branch, tag and CI checks are in order"
fi

# ---- 2. build ------------------------------------------------------------------------------------
# shellcheck source=tools/env.sh
. "$ROOT/tools/env.sh"
BUILD_TOOLS="$ANDROID_HOME/build-tools/36.0.0"
export PATH="$BUILD_TOOLS:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
for tool in apksigner aapt2 apkanalyzer unzip sha256sum; do
  command -v "$tool" >/dev/null 2>&1 || fail "required tool not found: $tool"
done

log "building :app:assembleRelease"
rm -rf app/build/outputs/apk/release
./gradlew --console=plain :app:assembleRelease

if [ "$SIGNED" -eq 1 ]; then
  built="app/build/outputs/apk/release/app-release.apk"
else
  built="app/build/outputs/apk/release/app-release-unsigned.apk"
fi
[ -f "$built" ] || fail "expected build output $built was not produced"
if [ "$SIGNED" -eq 1 ] && [ -f app/build/outputs/apk/release/app-release-unsigned.apk ]; then
  fail "signing material was provided but Gradle produced an unsigned APK"
fi

rm -rf dist
mkdir dist
cp "$built" "dist/$APK_NAME"
APK="dist/$APK_NAME"

# ---- 3. verify the APK ---------------------------------------------------------------------------
CERT_DIGEST=""
if signatures="$(apksigner verify --print-certs "$APK" 2>&1)"; then
  CERT_DIGEST="$(printf '%s\n' "$signatures" | sed -nE 's/^Signer #1 certificate SHA-256 digest: *([0-9a-fA-F]+).*/\1/p' | head -n 1)"
  [ -n "$CERT_DIGEST" ] || fail "apksigner verified the APK but printed no certificate SHA-256 digest"
  log "signature verifies, certificate SHA-256 $CERT_DIGEST"
else
  [ "$MODE" = "dry-run" ] && [ "$SIGNED" -eq 0 ] || fail "apksigner verify failed: $(printf '%s' "$signatures" | head -n 1)"
  log "WARNING: the APK is unsigned (allowed in a dry run only)"
fi

badging="$(aapt2 dump badging "$APK")"
pkg_line="$(grep -m1 '^package:' <<<"$badging" || true)"
[[ "$pkg_line" == *"name='dev.dialup'"* ]] || fail "package is not dev.dialup: $pkg_line"
[[ "$pkg_line" == *"versionName='$VERSION'"* ]] || fail "the APK versionName is not $VERSION: $pkg_line"
version_code="$(printf '%s' "$pkg_line" | sed -nE "s/.*versionCode='([0-9]+)'.*/\1/p")"
{ [ -n "$version_code" ] && [ "$version_code" -ge 1 ]; } || fail "the APK versionCode is missing or below 1: $pkg_line"
grep -qx "sdkVersion:'26'" <<<"$badging" || fail "the APK minSdk is not 26"
if grep -q '^application-debuggable' <<<"$badging"; then fail "the APK is debuggable"; fi
manifest="$(apkanalyzer manifest print "$APK")"
if grep -q 'CommandReceiver' <<<"$manifest"; then fail "the APK manifest contains the debug CommandReceiver"; fi
abis="$(unzip -Z1 "$APK" | sed -nE 's#^lib/([^/]+)/.*#\1#p' | sort -u | tr '\n' ' ')"
[ "$abis" = "arm64-v8a " ] || fail "native libraries must be arm64-v8a only, found: ${abis:-none}"
log "APK checks passed: dev.dialup $VERSION (code $version_code), minSdk 26, not debuggable, no CommandReceiver, arm64-v8a only"

# ---- 4. checksums and release notes --------------------------------------------------------------
(cd dist && sha256sum "$APK_NAME" > SHA256SUMS && sha256sum -c --quiet SHA256SUMS) || fail "SHA256SUMS does not verify"

source_offer="$(awk '/^### Source offer/ { f = 1; next } f && /^#/ { exit } f' README.md | sed '/./,$!d')"
[ -n "$(printf '%s' "$source_offer" | tr -d '[:space:]')" ] || fail "README.md has no 'Source offer' section to copy into the notes"

{
  printf '%s\n\n' "$changelog_body"
  printf '## Install and verify\n\n'
  printf '1. Download `%s` and `SHA256SUMS` from this release into one folder.\n' "$APK_NAME"
  printf '2. Run `sha256sum -c SHA256SUMS`; it must print `OK` for the APK.\n'
  printf '3. Run `apksigner verify --print-certs %s` (`apksigner` is part of the Android SDK build-tools).\n' "$APK_NAME"
  if [ -n "$CERT_DIGEST" ]; then
    printf '   The certificate SHA-256 digest must be:\n\n'
    printf '   `%s`\n\n' "$CERT_DIGEST"
    printf '   Every later release is signed with the same key and shows the same digest.\n'
  else
    printf '   THIS IS AN UNSIGNED DRY RUN BUILD, do not publish it.\n'
  fi
  printf '4. Install the APK on an Android 8.0 (API 26) or newer arm64 phone.\n\n'
  printf '## Source\n\n%s\n' "$source_offer"
} > "dist/$NOTES_NAME"

# ---- 5. dry run stops here; publish creates a draft ----------------------------------------------
if [ "$MODE" = "dry-run" ]; then
  log "files in dist/:"
  (cd dist && ls -l --time-style=+ | awk 'NR > 1 { printf "  %10d  %s\n", $5, $NF }')
  log "certificate SHA-256: ${CERT_DIGEST:-none (unsigned)}"
  log "dry run, nothing uploaded"
  exit 0
fi

head_sha="$(git rev-parse HEAD)"
if ! git rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
  git tag -a "$TAG" -m "DialUP $VERSION" "$head_sha"
fi
git push origin "refs/tags/$TAG"
prerelease_flag=()
case "$VERSION" in *-*) prerelease_flag=(--prerelease) ;; esac
gh release create "$TAG" "dist/$APK_NAME" "dist/SHA256SUMS" \
  --repo "$PUBLIC_REPO" --draft --verify-tag \
  --title "DialUP $VERSION" --notes-file "dist/$NOTES_NAME" "${prerelease_flag[@]}"
log "the release is a draft; install the asset on the phone before publishing it"
