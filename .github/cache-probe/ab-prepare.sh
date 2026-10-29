#!/usr/bin/env bash
set -euo pipefail
project="$RUNNER_TEMP/ab-project"
mkdir -p "$project"
git -C "$GITHUB_WORKSPACE" archive 941dbb4fb80b5a79200fcfa2a7ebeaf1af97d33c | tar -xf - -C "$project"
if [ "${AB_VARIANT:-A}" = B ] && [ "${AB_JOB:-}" = integration ]; then
  cat "$GITHUB_WORKSPACE/.github/cache-probe/probe.gradle" >> "$project/backend/build.gradle"
  cat >> "$project/backend/build.gradle" <<'GRADLE'
tasks.register('integrationRemainder', Test) {
    testClassesDirs = sourceSets.test.output.classesDirs
    classpath = sourceSets.test.runtimeClasspath
    useJUnitPlatform { includeTags 'integration' }
    filter { excludeTestsMatching 'com.knot.backend.recording.*' }
}
GRADLE
fi
chmod +x "$project/backend/gradlew"
