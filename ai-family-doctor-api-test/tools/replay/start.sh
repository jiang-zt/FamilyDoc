#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
if [[ ! -f "$ROOT/.env.replay" ]]; then echo "请先运行 tools/replay/init_db.py"; exit 1; fi
set -a
source "$ROOT/.env.replay"
set +a
export SPRING_PROFILES_ACTIVE=replay
export SERVER_ADDRESS=127.0.0.1
export SPRING_DATASOURCE_USERNAME=family_replay
export SPRING_DATASOURCE_PASSWORD="$REPLAY_DB_PASSWORD"
export SPRING_DATASOURCE_URL="jdbc:mysql://${REPLAY_DB_HOST}:${REPLAY_DB_PORT}/ai_family_doctor_replay?useUnicode=true&characterEncoding=UTF-8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&connectTimeout=3000&socketTimeout=5000"
export CHAT_TRAFFIC_RECORDING_ENABLED=false
export DASHSCOPE_MOCK_ENABLED=true
cd "$ROOT"
mvn -pl ai-family-doctor-server -am package -DskipTests
exec java -jar "$ROOT/ai-family-doctor-server/target/ai-family-doctor-server.jar" --spring.profiles.active=replay
