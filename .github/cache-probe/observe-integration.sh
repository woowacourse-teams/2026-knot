#!/usr/bin/env bash
set -u -o pipefail
out="$RUNNER_TEMP/observe"
mkdir -p "$out"
printf 'case=%s\nvariant=%s\nmode=%s\nrun_id=%s\n' "$OBS_CASE" "$OBS_VARIANT" "$OBS_MODE" "$GITHUB_RUN_ID" > "$out/metadata.txt"
uname -a > "$out/host.txt"
getconf _NPROCESSORS_ONLN >> "$out/host.txt"
cat /proc/meminfo | head -5 >> "$out/host.txt"
for f in /sys/fs/cgroup/cpu.max /sys/fs/cgroup/memory.max; do if [ -f "$f" ]; then printf '%s ' "$f" >> "$out/host.txt"; cat "$f" >> "$out/host.txt"; fi; done
export OBSERVE_METRICS="$out/resources.csv"
python3 "$GITHUB_WORKSPACE/.github/cache-probe/runner-sample.py" & sampler=$!
stop_sampler() { kill "$sampler" 2>/dev/null || true; wait "$sampler" 2>/dev/null || true; }
trap stop_sampler EXIT
cd "$RUNNER_TEMP/ab-project/backend" || exit 2
args=(--info --profile)
if [ "$OBS_MODE" = debug ]; then args+=(--init-script "$GITHUB_WORKSPACE/.github/cache-probe/context-observe.init.gradle"); fi
if [ "$OBS_VARIANT" = A ]; then tasks=(integrationTest); else tasks=(recordingProbeIntegration integrationRemainder); args+=(--build-cache); fi
/usr/bin/time -f 'wall_seconds=%e' -o "$out/gradle-time.txt" ./gradlew "${tasks[@]}" "${args[@]}" 2>&1 | tee "$out/gradle.log"
code=${PIPESTATUS[0]}
printf '%s\n' "$code" > "$out/gradle-exit.txt"
if [ "$code" -ne 0 ]; then exit "$code"; fi
if [ "$OBS_VARIANT" = B ]; then
  grep -Eq '^> Task :recordingProbeIntegration[[:space:]]*$' "$out/gradle.log" || exit 1
  if grep -q '^> Task :recordingProbeIntegration FROM-CACHE' "$out/gradle.log"; then exit 1; fi
  grep -Eq '^> Task :integrationRemainder[[:space:]]*$' "$out/gradle.log" || exit 1
fi
if [ "$OBS_MODE" = debug ]; then grep -q 'Spring test ApplicationContext cache statistics' "$out/gradle.log" || { echo 'No Spring context cache statistics observed'; exit 1; }; fi
