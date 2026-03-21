#!/usr/bin/env bash
set -euo pipefail

PROJECT_NAME="deepseek-doctor"
REQUIRED_JAVA_MAJOR="21"
FALLBACK_JAVA_HOME="/Users/jzttttt/JZT_WorkSpace/IDEA_Projects/DSSpringAIFamilyDoctor/JDK21/Contents/Home"

echo "[${PROJECT_NAME}] Checking Java version..."

# Prefer local JDK21 if present
if [[ -d "${FALLBACK_JAVA_HOME}/bin" ]]; then
  export JAVA_HOME="${FALLBACK_JAVA_HOME}"
  export PATH="${JAVA_HOME}/bin:${PATH}"
fi

if ! command -v java >/dev/null 2>&1; then
  echo "ERROR: java not found in PATH."
  exit 1
fi

JAVA_VERSION_OUTPUT="$(java -version 2>&1 | head -n 1)"
JAVA_MAJOR="$(echo "${JAVA_VERSION_OUTPUT}" | sed -E 's/.*"([0-9]+).*/\1/')"

if ! [[ "${JAVA_MAJOR}" =~ ^[0-9]+$ ]]; then
  echo "WARN: Unable to parse Java version from: ${JAVA_VERSION_OUTPUT}"
else
  if [[ "${JAVA_MAJOR}" != "${REQUIRED_JAVA_MAJOR}" ]]; then
    echo "WARN: Project expects Java ${REQUIRED_JAVA_MAJOR}, but current Java is ${JAVA_MAJOR}."
    echo "      It may still run, but using Java ${REQUIRED_JAVA_MAJOR} is recommended."
    echo "      To switch (macOS):"
    echo "        export JAVA_HOME=\$(/usr/libexec/java_home -v ${REQUIRED_JAVA_MAJOR})"
    echo "        export PATH=\"\$JAVA_HOME/bin:\$PATH\""
  else
    echo "OK: Java ${JAVA_MAJOR} detected."
  fi
fi

echo "[${PROJECT_NAME}] Checking Maven..."
if ! command -v mvn >/dev/null 2>&1; then
  echo "ERROR: mvn not found in PATH."
  exit 1
fi

echo "[${PROJECT_NAME}] Starting Spring Boot..."
mvn spring-boot:run
