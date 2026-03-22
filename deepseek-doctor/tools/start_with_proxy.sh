#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

PROXY_HOST="${PROXY_HOST:-127.0.0.1}"
PROXY_PORT="${PROXY_PORT:-8888}"

export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Dhttp.proxyHost=${PROXY_HOST} -Dhttp.proxyPort=${PROXY_PORT} -Dhttps.proxyHost=${PROXY_HOST} -Dhttps.proxyPort=${PROXY_PORT}"

echo "HTTP(S) 代理已启用: ${PROXY_HOST}:${PROXY_PORT}"
echo "提示: 若抓不到 HTTPS 明文，请先在系统/JVM 安装抓包工具根证书。"

mvn spring-boot:run
