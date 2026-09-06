#!/usr/bin/env bash
set -euo pipefail

is_java_21() {
  local candidate="$1"
  [[ -x "$candidate/bin/javac" ]] && [[ "$("$candidate/bin/javac" -version 2>&1)" == javac\ 21* ]]
}

if [[ -n "${HAOEMR_JAVA_HOME:-}" ]] && is_java_21 "$HAOEMR_JAVA_HOME"; then
  selected_java_home="$HAOEMR_JAVA_HOME"
elif java_21_home=$(/usr/libexec/java_home -v 21 2>/dev/null) && is_java_21 "$java_21_home"; then
  selected_java_home="$java_21_home"
else
  selected_java_home=""
  for candidate in \
    /private/tmp/haoemr-toolchains/jdk21-microsoft/*/Contents/Home \
    /private/tmp/haoemr-toolchains/jdk21/*.jdk/Contents/Home \
    /private/tmp/haoemr-toolchains/corretto21/*.jdk/Contents/Home \
    /Library/Java/JavaVirtualMachines/*21*.jdk/Contents/Home; do
    if is_java_21 "$candidate"; then
      selected_java_home="$candidate"
      break
    fi
  done
  if [[ -z "$selected_java_home" ]]; then
    echo "JDK 21 is required. Set HAOEMR_JAVA_HOME to a JDK 21 home." >&2
    exit 1
  fi
fi

export JAVA_HOME="$selected_java_home"
export PATH="$JAVA_HOME/bin:$PATH"
exec "$@"
