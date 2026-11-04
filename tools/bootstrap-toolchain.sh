#!/usr/bin/env bash
# Tube2U[PnP] Android: pinned, checksum-verified, user-space toolchain bootstrap for WSL.
#
#   bash tools/bootstrap-toolchain.sh            install whatever is missing (idempotent)
#   bash tools/bootstrap-toolchain.sh --check    verify only, exit 1 if anything is missing
#
# Installs: Temurin JDK 21 (~/jdks/temurin-21), Android cmdline-tools + SDK packages
# (~/android-sdk), and generates the Gradle 9.8.2 wrapper once. No sudo. Every download
# lands in a fresh directory under ~/.cache/t2u-bootstrap/<timestamp>/ and is checksum
# verified before it is extracted; a mismatch deletes the download and exits 1.
set -euo pipefail

# ---- pins -----------------------------------------------------------------------------
JDK_URL="https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jdk_x64_linux_hotspot_21.0.12.1_1.tar.gz"
JDK_SHA256="ce79869e1307ed8ee1e2baa86a412b1eb5b75d10a01006d788a6f968bcfaee94"
JDK_VERSION="21.0.12.1"

GRADLE_VERSION="9.8.2"
GRADLE_URL="https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"
GRADLE_SHA256="21bd5da2de49b642904077f38c0efdc7778e92b3ed0889392dca61b97e389607"
WRAPPER_JAR_SHA256="3b8a25775a69158b5ad2b1d17a80a88dc7b40a352a73eb27d06c612a7ce68e98"

CMDLINE_URL="https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip"
CMDLINE_SHA1="e025545c62a8e64c7559119566a569fb1dec5f60"

SDK_PACKAGES=("platforms;android-37.0" "build-tools;36.0.0" "platform-tools")

# ---- paths -------------------------------------------------------------------------------
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAVA_HOME_DIR="$HOME/jdks/temurin-21"
SDK_DIR="$HOME/android-sdk"
SDKMANAGER="$SDK_DIR/cmdline-tools/latest/bin/sdkmanager"
CACHE_ROOT="$HOME/.cache/t2u-bootstrap"
GRADLE_HOME_DIR="$CACHE_ROOT/gradle-${GRADLE_VERSION}"

log() { printf '[bootstrap] %s\n' "$*"; }
die() { printf '[bootstrap] FATAL: %s\n' "$*" >&2; exit 1; }

# verify <algo: sha256|sha1> <expected> <file>; deletes the file and exits 1 on mismatch
verify() {
  local algo="$1" expected="$2" file="$3" actual
  actual="$("${algo}sum" "$file" | cut -d' ' -f1)"
  if [ "$actual" != "$expected" ]; then
    rm -f "$file"
    die "$algo mismatch for $(basename "$file"): expected $expected, got $actual (download deleted)"
  fi
  log "$algo ok: $(basename "$file")"
}

fresh_download_dir() {
  local d="$CACHE_ROOT/$(date -u +%Y%m%dT%H%M%SZ)-$$"
  mkdir -p "$d"
  printf '%s' "$d"
}

jdk_ok() {
  [ -x "$JAVA_HOME_DIR/bin/java" ] && "$JAVA_HOME_DIR/bin/java" -version 2>&1 | grep -q "\"$JDK_VERSION\""
}

# sdkmanager --list_installed prints package paths with '/' (platforms/android-37.0) in the
# current cmdline-tools and ';' ids (platforms;android-37.0) in older ones; accept both.
sdk_installed_line() {
  [ -x "$SDKMANAGER" ] || return 1
  local id="$1" path="${1//;//}"
  JAVA_HOME="$JAVA_HOME_DIR" "$SDKMANAGER" --sdk_root="$SDK_DIR" --list_installed 2>/dev/null \
    | grep -E "^[[:space:]]*(${path}|${id})[[:space:]|]" | head -1 | sed 's/^[[:space:]]*//'
}

sdk_pkg_ok() {
  [ -n "$(sdk_installed_line "$1")" ]
}

wrapper_ok() {
  [ -x "$REPO_ROOT/gradlew" ] && [ -f "$REPO_ROOT/gradle/wrapper/gradle-wrapper.jar" ] \
    && [ "$(sha256sum "$REPO_ROOT/gradle/wrapper/gradle-wrapper.jar" | cut -d' ' -f1)" = "$WRAPPER_JAR_SHA256" ]
}

# ---- --check -----------------------------------------------------------------------------
check() {
  local rc=0
  log "check: JDK"
  if jdk_ok; then "$JAVA_HOME_DIR/bin/java" -version 2>&1 | head -1; else log "MISSING: JDK $JDK_VERSION at $JAVA_HOME_DIR"; rc=1; fi

  log "check: Gradle wrapper"
  if wrapper_ok && [ -x "$JAVA_HOME_DIR/bin/java" ]; then
    (cd "$REPO_ROOT" && JAVA_HOME="$JAVA_HOME_DIR" ./gradlew --version 2>&1 | grep -E '^Gradle ') || rc=1
  else
    log "MISSING: gradlew / wrapper jar (sha256 $WRAPPER_JAR_SHA256)"; rc=1
  fi

  log "check: Android SDK packages"
  for p in "${SDK_PACKAGES[@]}"; do
    if sdk_pkg_ok "$p"; then
      sdk_installed_line "$p"
    else
      log "MISSING: SDK package $p"; rc=1
    fi
  done

  log "check: adb"
  if [ -x "$SDK_DIR/platform-tools/adb" ]; then "$SDK_DIR/platform-tools/adb" version | head -2; else log "MISSING: adb"; rc=1; fi

  [ "$rc" -eq 0 ] && log "check: all present" || log "check: FAILED"
  return "$rc"
}

if [ "${1:-}" = "--check" ]; then
  check
  exit $?
fi
[ $# -eq 0 ] || die "usage: $0 [--check]"

# ---- install -----------------------------------------------------------------------------
command -v curl >/dev/null || die "curl is required"
command -v unzip >/dev/null || die "unzip is required"
mkdir -p "$CACHE_ROOT"

# 1. JDK
if jdk_ok; then
  log "JDK $JDK_VERSION already installed"
else
  d="$(fresh_download_dir)"
  log "downloading JDK $JDK_VERSION"
  curl -fsSL -o "$d/jdk.tar.gz" "$JDK_URL"
  verify sha256 "$JDK_SHA256" "$d/jdk.tar.gz"
  rm -rf "$JAVA_HOME_DIR"
  mkdir -p "$JAVA_HOME_DIR"
  tar xzf "$d/jdk.tar.gz" -C "$JAVA_HOME_DIR" --strip-components=1
  jdk_ok || die "JDK extracted but java -version does not report $JDK_VERSION"
fi

# 2. Android cmdline-tools
if [ -x "$SDKMANAGER" ]; then
  log "cmdline-tools already installed"
else
  d="$(fresh_download_dir)"
  log "downloading Android commandlinetools"
  curl -fsSL -o "$d/cmdline.zip" "$CMDLINE_URL"
  verify sha1 "$CMDLINE_SHA1" "$d/cmdline.zip"
  mkdir -p "$d/x" "$SDK_DIR/cmdline-tools"
  unzip -q "$d/cmdline.zip" -d "$d/x"
  rm -rf "$SDK_DIR/cmdline-tools/latest"
  mv "$d/x/cmdline-tools" "$SDK_DIR/cmdline-tools/latest"
  [ -x "$SDKMANAGER" ] || die "sdkmanager missing after unzip"
fi

# 3. SDK packages (licences first, then only the missing ones)
missing=()
for p in "${SDK_PACKAGES[@]}"; do
  sdk_pkg_ok "$p" && log "SDK package present: $p" || missing+=("$p")
done
if [ "${#missing[@]}" -gt 0 ]; then
  log "accepting Android SDK licences"
  # `yes` gets SIGPIPE when sdkmanager stops reading; that is expected, so tolerate it.
  set +o pipefail
  yes | JAVA_HOME="$JAVA_HOME_DIR" "$SDKMANAGER" --sdk_root="$SDK_DIR" --licenses >/dev/null 2>&1
  set -o pipefail
  log "installing: ${missing[*]}"
  JAVA_HOME="$JAVA_HOME_DIR" "$SDKMANAGER" --sdk_root="$SDK_DIR" "${missing[@]}"
fi

# 4. Gradle wrapper (generated once with the downloaded distribution, then committed)
if wrapper_ok; then
  log "Gradle wrapper already present and verified"
else
  if [ ! -x "$GRADLE_HOME_DIR/bin/gradle" ]; then
    d="$(fresh_download_dir)"
    log "downloading Gradle $GRADLE_VERSION"
    curl -fsSL -o "$d/gradle.zip" "$GRADLE_URL"
    verify sha256 "$GRADLE_SHA256" "$d/gradle.zip"
    mkdir -p "$d/x"
    unzip -q "$d/gradle.zip" -d "$d/x"
    rm -rf "$GRADLE_HOME_DIR"
    mv "$d/x/gradle-${GRADLE_VERSION}" "$GRADLE_HOME_DIR"
  fi
  log "generating Gradle wrapper"
  # Gradle 9 refuses to run any task without a settings file, so generate the wrapper in a
  # scratch project and copy the result in (independent of the state of the real build files).
  scratch="$(fresh_download_dir)/wrapper-project"
  mkdir -p "$scratch"
  : > "$scratch/settings.gradle.kts"
  (
    cd "$scratch"
    JAVA_HOME="$JAVA_HOME_DIR" "$GRADLE_HOME_DIR/bin/gradle" wrapper \
      --gradle-version "$GRADLE_VERSION" \
      --gradle-distribution-sha256-sum "$GRADLE_SHA256"
  )
  mkdir -p "$REPO_ROOT/gradle/wrapper"
  cp "$scratch/gradlew" "$REPO_ROOT/gradlew"
  cp "$scratch/gradle/wrapper/gradle-wrapper.jar" "$scratch/gradle/wrapper/gradle-wrapper.properties" "$REPO_ROOT/gradle/wrapper/"
  chmod +x "$REPO_ROOT/gradlew"
  # gradlew.bat is deliberately not copied: development is WSL-only; the wrapper
  # task regenerates it if ever needed.
  actual="$(sha256sum "$REPO_ROOT/gradle/wrapper/gradle-wrapper.jar" | cut -d' ' -f1)"
  [ "$actual" = "$WRAPPER_JAR_SHA256" ] || die "gradle-wrapper.jar sha256 mismatch: expected $WRAPPER_JAR_SHA256, got $actual"
  log "gradle-wrapper.jar sha256 ok"
fi

log "install complete; now run: bash tools/bootstrap-toolchain.sh --check"
