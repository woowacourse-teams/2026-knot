#!/usr/bin/env bash
set -u -o pipefail
root="$GITHUB_WORKSPACE"
output="$RUNNER_TEMP/environment-probe-results"
project="$RUNNER_TEMP/environment-probe-project"
mkdir -p "$output" "$project"
git -C "$root" archive b8ea330decc3cfc3b4a5f38b7368a45edc935693 | tar -xf - -C "$project" || exit 2
cat "$root/.github/cache-probe/probe.gradle" >> "$project/backend/build.gradle"
python3 - "$project/backend/src/test/java/com/knot/backend/testsupport/TestcontainersConfiguration.java" <<'PY'
import pathlib, sys
p=pathlib.Path(sys.argv[1]); s=p.read_text()
a='return new PostgreSQLContainer("pgvector/pgvector:pg18")'
b='System.out.println("CACHE_PROBE_OBS env=" + System.getenv("CACHE_PROBE_ENV") + " image=" + System.getenv("CACHE_PROBE_IMAGE"));\n        return new PostgreSQLContainer(System.getenv("CACHE_PROBE_IMAGE"))'
if s.count(a) != 1: raise SystemExit('container configuration mismatch')
p.write_text(s.replace(a,b))
PY
cat >> "$project/backend/build.gradle" <<'GRADLE'
tasks.named('recordingProbeIntegration') {
    if (providers.gradleProperty('declareProbe').orNull == 'true') {
        inputs.property('experimentEnvironment', providers.environmentVariable('CACHE_PROBE_ENV'))
        inputs.property('experimentImageDigest', providers.environmentVariable('CACHE_PROBE_IMAGE'))
    }
    testLogging.showStandardStreams = true
}
GRADLE
for tag in pg18 pg17; do
  docker pull "pgvector/pgvector:$tag" > "$output/pull-$tag.log" 2>&1 || exit 2
  docker image inspect "pgvector/pgvector:$tag" --format '{{index .RepoDigests 0}}' > "$output/digest-$tag.txt" || exit 2
done
image_a=$(cat "$output/digest-pg18.txt")
image_b=$(cat "$output/digest-pg17.txt")
[ "$image_a" != "$image_b" ] || exit 2
run_one() {
  local label="$1" mode="$2" envvalue="$3" image="$4" code
  mkdir -p "$output/$label"
  cd "$project/backend" || return 1
  CACHE_PROBE_ENV="$envvalue" CACHE_PROBE_IMAGE="$image" /usr/bin/time -f 'wall_seconds=%e' -o "$output/$label/time.txt" ./gradlew clean recordingProbeIntegration --tests 'com.knot.backend.recording.application.RecordingDocumentServiceIntegrationTest' --build-cache --no-daemon --info -PdeclareProbe="$mode" > "$output/$label/gradle.log" 2>&1
  code=$?
  printf '%s\n' "$code" > "$output/$label/exit.txt"
  if [ -d build/test-results/recordingProbeIntegration ]; then cp -R build/test-results/recordingProbeIntegration "$output/$label/xml"; fi
  if grep -Eq '^> Task :recordingProbeIntegration FROM-CACHE' "$output/$label/gradle.log"; then echo FROM-CACHE > "$output/$label/result.txt"; else echo EXECUTED > "$output/$label/result.txt"; fi
  return "$code"
}
failed=0
run_one untracked-seed false A "$image_a" || failed=1
run_one untracked-env false B "$image_a" || failed=1
run_one untracked-image false B "$image_b" || failed=1
run_one declared-seed true A "$image_a" || failed=1
run_one declared-env true B "$image_a" || failed=1
run_one declared-image true B "$image_b" || failed=1
for label in declared-seed declared-env declared-image; do
  [ "$(cat "$output/$label/result.txt")" = EXECUTED ] || failed=1
done
grep -R -F -q "CACHE_PROBE_OBS env=A image=$image_a" "$output/declared-seed/xml" || failed=1
grep -R -F -q "CACHE_PROBE_OBS env=B image=$image_a" "$output/declared-env/xml" || failed=1
grep -R -F -q "CACHE_PROBE_OBS env=B image=$image_b" "$output/declared-image/xml" || failed=1
exit "$failed"
