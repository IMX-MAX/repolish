#!/usr/bin/env bash
set -euo pipefail

if [[ ! -f ./gradlew ]]; then
  echo "No Gradle wrapper in this checkout; skipping dependency install."
  exit 0
fi

if [[ -d /usr/lib/jvm/temurin-25-jdk ]]; then
  export JAVA_HOME=/usr/lib/jvm/temurin-25-jdk
  export PATH="$JAVA_HOME/bin:$PATH"
fi

chmod +x ./gradlew
./gradlew --no-daemon compileJava
