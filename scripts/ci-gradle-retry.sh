#!/usr/bin/env bash
# Retry ONLY failures that look like transient Gradle/Maven repository problems.
# Syntax: bash scripts/ci-gradle-retry.sh TASKS... OPTIONS...
# Build, Kotlin, test and AAPT failures fail immediately (never masked).
set -euo pipefail
max_attempts="${GRADLE_RETRY_MAX:-3}"
if [[ "$max_attempts" -lt 1 || "$max_attempts" -gt 5 ]]; then
  echo "Invalid GRADLE_RETRY_MAX=$max_attempts" >&2
  exit 2
fi
for ((attempt=1; attempt<=max_attempts; attempt++)); do
  logfile="${RUNNER_TEMP:-/tmp}/lg-g6-gradle-attempt-${attempt}.log"
  echo "::group::Gradle attempt ${attempt}/${max_attempts}"
  options=("$@")
  if (( attempt > 1 )); then
    options+=(--refresh-dependencies)
  fi
  if ./gradlew "${options[@]}" --console=plain 2>&1 | tee "$logfile"; then
    echo "::endgroup::"
    echo "Gradle succeeded on attempt ${attempt}/${max_attempts}"
    exit 0
  fi
  echo "::endgroup::"
  # Maven/Google repo download errors are common on parallel GitHub runners.
  # Never retry a Kotlin compiler failure or a failing unit test.
  if ! grep -Eqi 'Could not (resolve|find) (all|org\.|com\.|androidx\.|net\.|io\.|.*:)|Temporary failure in name resolution|Connection (reset|timed out)|Read timed out|SSLHandshakeException|502 Bad Gateway|503 Service Unavailable|504 Gateway Timeout|Received status code (429|50[0234])' "$logfile"; then
    echo "Build failed for a non-download reason; not retrying." >&2
    exit 1
  fi
  if (( attempt == max_attempts )); then
    echo "Transient dependency download failed after ${attempt} attempts." >&2
    exit 1
  fi
  sleep "$((10 * attempt))"
done
exit 1
