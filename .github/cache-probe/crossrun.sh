#!/usr/bin/env bash
set -u -o pipefail
root="$GITHUB_WORKSPACE"
output="$RUNNER_TEMP/crossrun-results"
project="$RUNNER_TEMP/crossrun-project"
mkdir -p "$output" "$project"
phase=$(cat "$root/.github/cache-probe/phase.txt")
case "$CASE" in
 document) head=941dbb4fb80b5a79200fcfa2a7ebeaf1af97d33c; expected=FROM-CACHE;;
 fixture) head=84ce514c993d49a7833fd044e9f7565b908d1289; expected=EXECUTED;;
 application) head=7b0ee2865d0035fc073e1f84804e0acac39952b4; expected=EXECUTED;;
 negative|environment|image) head=941dbb4fb80b5a79200fcfa2a7ebeaf1af97d33c; expected=EXECUTED;;
 *) exit 2;;
esac
base=$(git -C "$root" rev-parse "$head^") || exit 2
revision="$base"
if [ "$phase" = verify ]; then revision="$head"; fi
printf 'case=%s\nphase=%s\nbase=%s\nhead=%s\nrevision=%s\nrun_id=%s\ncache_hit=%s\n' "$CASE" "$phase" "$base" "$head" "$revision" "$GITHUB_RUN_ID" "$CACHE_HIT" | tee "$output/metadata.txt"
if [ "$phase" = seed ] && [ "$CACHE_HIT" = true ]; then echo 'Unexpected pre-existing cache'; exit 1; fi
if [ "$phase" = verify ] && [ "$CACHE_HIT" != true ]; then echo 'Previous run cache was not restored'; exit 1; fi
git -C "$root" archive "$revision" | tar -xf - -C "$project" || exit 2
cat "$root/.github/cache-probe/probe.gradle" >> "$project/backend/build.gradle"
image_a='pgvector/pgvector@sha256:2358fcba361ed2233a5ed81b5fe4ca779ccb304120ce531a3bf51c0ed7e2bc11'
image_b='pgvector/pgvector@sha256:ac08538c6f8b9904c33c8224c5e5706dbe760aca29db1d096972b4052c22a75d'
export CACHE_PROBE_ENV=A CACHE_PROBE_IMAGE="$image_a"
if [ "$CASE" = environment ] || [ "$CASE" = image ]; then
  if [ "$phase" = verify ] && [ "$CASE" = environment ]; then export CACHE_PROBE_ENV=B; fi
  if [ "$phase" = verify ] && [ "$CASE" = image ]; then export CACHE_PROBE_IMAGE="$image_b"; fi
  python3 - "$project/backend/src/test/java/com/knot/backend/testsupport/TestcontainersConfiguration.java" <<'PY'
import pathlib, sys
p=pathlib.Path(sys.argv[1]); s=p.read_text()
a='return new PostgreSQLContainer("pgvector/pgvector:pg18")'
b='System.out.println("CROSSRUN_OBS env=" + System.getenv("CACHE_PROBE_ENV") + " image=" + System.getenv("CACHE_PROBE_IMAGE"));\n        return new PostgreSQLContainer(System.getenv("CACHE_PROBE_IMAGE"))'
if s.count(a) != 1: raise SystemExit('container configuration mismatch')
p.write_text(s.replace(a,b))
PY
  cat >> "$project/backend/build.gradle" <<'GRADLE'
tasks.named('recordingProbeIntegration') {
    inputs.property('experimentEnvironment', providers.environmentVariable('CACHE_PROBE_ENV'))
    inputs.property('experimentImageDigest', providers.environmentVariable('CACHE_PROBE_IMAGE'))
    testLogging.showStandardStreams = true
}
GRADLE
fi
if [ "$CASE" = negative ] && [ "$phase" = verify ]; then
 python3 - "$project/backend/src/test/java/com/knot/backend/testsupport/RecordingHistoryFixture.java" <<'PY'
import pathlib, sys
p=pathlib.Path(sys.argv[1]); s=p.read_text(); a='public void clear() {'
b='public void clear() {\n        if (System.nanoTime() != 0) throw new IllegalStateException("CROSSRUN_FIXTURE_FAILURE");'
if s.count(a) != 1: raise SystemExit('fixture mismatch')
p.write_text(s.replace(a,b))
PY
fi
run_stage() {
  local label="$1" task="$2" options="$3" code
  mkdir -p "$output/$label"
  cd "$project/backend" || return 1
  echo "Starting $label task=$task"
  /usr/bin/time -f 'wall_seconds=%e' -o "$output/$label/time.txt" ./gradlew "$task" $options --info --no-daemon >"$output/$label/gradle.log" 2>&1
  code=$?; printf '%s\n' "$code" > "$output/$label/exit.txt"
  if [ -d "build/test-results/$task" ]; then cp -R "build/test-results/$task" "$output/$label/xml"; fi
  echo "Finished $label exit=$code"; return "$code"
}
filter=''
if [ "$CASE" = environment ] || [ "$CASE" = image ]; then filter="--tests com.knot.backend.recording.application.RecordingDocumentServiceIntegrationTest"; fi
if [ "$phase" = seed ]; then run_stage seed recordingProbeIntegration "--build-cache $filter"; exit $?; fi
if [ "$phase" != verify ]; then exit 2; fi
if [ "$CASE" = negative ]; then
 if run_stage changed recordingProbeIntegration '--build-cache'; then echo 'Expected failure was hidden'; exit 1; fi
 if grep -Eq '^> Task :recordingProbeIntegration FROM-CACHE' "$output/changed/gradle.log"; then echo 'Failure was hidden by cache'; exit 1; fi
 grep -R -q 'CROSSRUN_FIXTURE_FAILURE' "$output/changed/xml" || exit 1
 echo 'Expected fixture failure detected after cache invalidation' | tee "$output/negative.txt"
 exit 0
fi
run_stage changed recordingProbeIntegration "--build-cache $filter" || exit 1
if grep -Eq '^> Task :recordingProbeIntegration FROM-CACHE' "$output/changed/gradle.log"; then actual=FROM-CACHE; else actual=EXECUTED; fi
printf 'actual=%s\nexpected=%s\n' "$actual" "$expected" | tee -a "$output/metadata.txt"
[ "$actual" = "$expected" ] || exit 1
if [ "$CASE" = environment ] || [ "$CASE" = image ]; then
 grep -R -F -q "CROSSRUN_OBS env=$CACHE_PROBE_ENV image=$CACHE_PROBE_IMAGE" "$output/changed/xml" || exit 1
 docker image inspect "$CACHE_PROBE_IMAGE" --format '{{.Id}} {{json .RepoDigests}}' > "$output/actual-image.txt" || exit 1
 exit 0
fi
run_stage fresh recordingProbeIntegration '--no-build-cache --rerun-tasks' || exit 1
run_stage full integrationTest '--no-build-cache --rerun-tasks' || exit 1
python3 "$root/.github/cache-probe/compare.py" "$output/changed/xml" "$output/fresh/xml" "$output/full/xml" | tee "$output/comparison.txt"
exit "${PIPESTATUS[0]}"
