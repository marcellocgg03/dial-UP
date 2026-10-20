# Source this file (". tools/env.sh"); do not execute it.
# Sets JAVA_HOME, ANDROID_HOME, PATH and, when .t2u/device exists, T2U_SERIAL / ANDROID_SERIAL.
export JAVA_HOME="$HOME/jdks/temurin-21"
export ANDROID_HOME="$HOME/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

# Repo root = parent of this file's directory (works from any cwd, bash or zsh).
_t2u_env_dir="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
_t2u_device_file="$_t2u_env_dir/../.t2u/device"
if [ -f "$_t2u_device_file" ]; then
  T2U_SERIAL="$(head -n 1 "$_t2u_device_file" | tr -d '[:space:]')"
  export T2U_SERIAL
  export ANDROID_SERIAL="$T2U_SERIAL"
fi
unset _t2u_env_dir _t2u_device_file
