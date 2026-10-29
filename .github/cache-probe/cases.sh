#!/usr/bin/env bash
set -u -o pipefail
root="$GITHUB_WORKSPACE"
output="$RUNNER_TEMP/cache-probe-results"
project="$RUNNER_TEMP/cache-probe-project"
mkdir -p "$output"
case "$CASE" in
 document) head=941dbb4fb80b5a79200fcfa2a7ebeaf1af97d33c ; expected=FROM-CACHE ;;
 fixture) head=84ce514c993d49a7833fd044e9f7565b908d1289 ; expected=EXECUTED ;;
 application) head=7b0ee2865d0035fc073e1f84804e0acac39952b4 ; expected=EXECUTED ;;
 *) exit 2 ;;
esac
base=$(git -C "$root" rev-parse "$head^") || exit 2
printf 'case=%s\nbase=%s\nhead=%s\nexpected=%s\n' "$CASE" "$base" "$head" "$expected" | tee "$output/metadata.txt"
restore() {
  rm -rf "$project"
  mkdir -p "$project"
  git -C "$root" archive "$1" | tar -xf - -C "$project" || return 1
  cat "$root/.github/cache-probe/probe.gradle" >> "$project/backend/build.gradle"
}
run_stage() {
  local label="$1" task="$2" options="$3" code
  mkdir -p "$output/$label"
  cd "$project/backend" || return 1
  /usr/bin/time -f 'wall_seconds=%e' -o "$output/$label/time.txt" ./gradlew "$task" $options --info --no-daemon >"$output/$label/gradle.log" 2>&1
  code=$?
  printf '%s\n' "$code" > "$output/$label/exit.txt"
  if [ -d "build/test-results/$task" ]; then cp -R "build/test-results/$task" "$output/$label/xml"; fi
  return "$code"
}
restore "$base" || exit 2
run_stage seed recordingProbeIntegration '--build-cache' || { echo 'Base seed failed' | tee "$output/invalid.txt"; exit 1; }
restore "$head" || exit 2
run_stage changed recordingProbeIntegration '--build-cache' || { echo 'Changed run failed' | tee "$output/invalid.txt"; exit 1; }
if grep -Eq '^> Task :recordingProbeIntegration FROM-CACHE' "$output/changed/gradle.log"; then actual=FROM-CACHE; else actual=EXECUTED; fi
printf 'actual=%s\n' "$actual" | tee -a "$output/metadata.txt"
run_stage fresh recordingProbeIntegration '--no-build-cache --rerun-tasks' || { echo 'Fresh group failed' | tee "$output/invalid.txt"; exit 1; }
run_stage full integrationTest '--no-build-cache --rerun-tasks' || { echo 'Full suite failed' | tee "$output/invalid.txt"; exit 1; }
python3 "$root/.github/cache-probe/compare.py" "$output/changed/xml" "$output/fresh/xml" "$output/full/xml" | tee "$output/comparison.txt"
compare_status=${PIPESTATUS[0]}
[ "$actual" = "$expected" ] && [ "$compare_status" = 0 ]
