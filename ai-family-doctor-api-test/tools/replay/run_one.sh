#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
if [[ ! -f "$ROOT/.env.replay" ]]; then echo "请先初始化回放库"; exit 1; fi
INPUT="${1:-$ROOT/ai-family-doctor-api-test/src/test/resources/replay/single.jsonl}"
INPUT="$(python3 -c 'import pathlib,sys; print(pathlib.Path(sys.argv[1]).resolve())' "$INPUT")"
set -a
source "$ROOT/.env.replay"
set +a
cd "$ROOT"
ARGS=(-Dreplay.enabled=true "-Dreplay.input=$INPUT" "-Dreplay.baseUrl=http://127.0.0.1:${REPLAY_PORT}" "-Dreplay.output=$ROOT/ai-family-doctor-api-test/target/replay")
if [[ -n "${2:-}" ]]; then ARGS+=("-Dreplay.sampleId=$2"); fi
mvn -pl ai-family-doctor-api-test -am test -Dtest=ReplayRunTest -Dsurefire.failIfNoSpecifiedTests=false "${ARGS[@]}"
