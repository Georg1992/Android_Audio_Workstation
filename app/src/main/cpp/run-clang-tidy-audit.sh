#!/usr/bin/env bash
# Audit-only clang-tidy for the audioworkstation native engine (non-failing).
# Prerequisite: ./gradlew :app:assembleDebug
set -euo pipefail

ABI="${ABI:-arm64-v8a}"
INCLUDE_TESTS="${INCLUDE_TESTS:-0}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../.." && pwd)"
CPP_ROOT="$SCRIPT_DIR"
APP_DIR="$REPO_ROOT/app"
REPORT_DIR="$REPO_ROOT/build/reports/clang-tidy"
REPORT_FILE="$REPORT_DIR/engine-audit.txt"

ndk_version="$(sed -n 's/.*ndkVersion = "\([^"]*\)".*/\1/p' "$APP_DIR/build.gradle" | head -1)"
sdk_dir="$(sed -n 's/^sdk.dir=//p' "$REPO_ROOT/local.properties" | tr '\\' '/')"
compile_db_dir="$APP_DIR/.cxx/tools/debug/$ABI"
compile_db="$compile_db_dir/compile_commands.json"

if [[ -z "$ndk_version" || -z "$sdk_dir" ]]; then
  echo "Could not resolve NDK version or sdk.dir" >&2
  exit 1
fi

host_tag="$(uname -s | tr '[:upper:]' '[:lower:]')-$(uname -m)"
case "$host_tag" in
  linux-x86_64) prebuilt=linux-x86_64 ;;
  darwin-*) prebuilt=darwin-x86_64 ;;
  *) echo "Unsupported host for NDK prebuilt: $host_tag" >&2; exit 1 ;;
esac

clang_tidy="$sdk_dir/ndk/$ndk_version/toolchains/llvm/prebuilt/$prebuilt/bin/clang-tidy"
config_file="$CPP_ROOT/.clang-tidy"

if [[ ! -x "$clang_tidy" ]]; then
  echo "clang-tidy not found: $clang_tidy" >&2
  exit 1
fi
if [[ ! -f "$compile_db" ]]; then
  echo "Missing $compile_db — run: ./gradlew :app:assembleDebug" >&2
  exit 1
fi

sources=(
  "$CPP_ROOT/engine/AudioEngine.cpp"
  "$CPP_ROOT/engine/JNI_Bridge.cpp"
  "$CPP_ROOT/engine/LocalWavSource.cpp"
  "$CPP_ROOT/engine/OboeOutput.cpp"
)
if [[ "$INCLUDE_TESTS" == "1" ]]; then
  sources+=(
    "$CPP_ROOT/tests/RingBuffer_test.cpp"
    "$CPP_ROOT/tests/AudioEngine_MasterPlayback_test.cpp"
    "$CPP_ROOT/tests/AudioEngine_TransportRecording_test.cpp"
  )
fi

mkdir -p "$REPORT_DIR"
{
  echo "clang-tidy engine audit"
  echo "NDK: $ndk_version"
  echo "Compile DB: $compile_db"
} >"$REPORT_FILE"

issues=0
for src in "${sources[@]}"; do
  echo "" >>"$REPORT_FILE"
  echo "=== $src ===" >>"$REPORT_FILE"
  if out="$("$clang_tidy" "$src" -p "$compile_db_dir" --config-file="$config_file" --quiet 2>&1)"; then
    if [[ -n "$out" ]]; then
      issues=1
      echo "$out" | tee -a "$REPORT_FILE"
    else
      echo "(no issues)" >>"$REPORT_FILE"
    fi
  else
    issues=1
    echo "$out" >>"$REPORT_FILE"
    echo "$out"
  fi
done

echo "" >>"$REPORT_FILE"
echo "Report: $REPORT_FILE" >>"$REPORT_FILE"
echo "Audit complete (exit 0). Issues flagged: $issues"
exit 0
