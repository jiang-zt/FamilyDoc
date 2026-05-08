#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${1:-http://127.0.0.1:8080}"
USERNAME="${2:-1}"
PASSWORD="${3:-123}"
MESSAGE="${4:-你好，请用一句话介绍你自己。}"

json_escape() {
  printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'
}

post_json() {
  local url="$1"
  local body="$2"
  local auth_header="${3:-}"
  if [[ -n "${auth_header}" ]]; then
    curl -sS -X POST "${url}" \
      -H "Content-Type: application/json" \
      -H "Authorization: Bearer ${auth_header}" \
      -d "${body}"
  else
    curl -sS -X POST "${url}" \
      -H "Content-Type: application/json" \
      -d "${body}"
  fi
}

echo "[1/5] 登录（若用户不存在可先手动注册）..."
LOGIN_BODY=$(printf '{"username":"%s","password":"%s"}' "$(json_escape "${USERNAME}")" "$(json_escape "${PASSWORD}")")
LOGIN_RESP="$(post_json "${BASE_URL}/auth/login" "${LOGIN_BODY}")"
TOKEN="$(printf '%s' "${LOGIN_RESP}" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')"

if [[ -z "${TOKEN}" ]]; then
  echo "登录失败，返回如下："
  echo "${LOGIN_RESP}"
  exit 1
fi

echo "[2/5] 同步接口 /chat 冒烟..."
CHAT_BODY=$(printf '{"message":"%s"}' "$(json_escape "${MESSAGE}")")
CHAT_RESP="$(post_json "${BASE_URL}/chat" "${CHAT_BODY}" "${TOKEN}")"
echo "同步返回：${CHAT_RESP}"

echo "[3/5] 建立 SSE 连接..."
SSE_TMP="$(mktemp)"
cleanup() {
  if [[ -n "${SSE_PID:-}" ]]; then
    kill "${SSE_PID}" >/dev/null 2>&1 || true
  fi
  rm -f "${SSE_TMP}"
}
trap cleanup EXIT

curl -sS -N "${BASE_URL}/sse/connect?userId=${USERNAME}&token=${TOKEN}" > "${SSE_TMP}" &
SSE_PID=$!
sleep 1

echo "[4/5] 触发流式接口 /chat/stream ..."
post_json "${BASE_URL}/chat/stream" "${CHAT_BODY}" "${TOKEN}" >/dev/null
sleep 3

echo "[5/5] 最近 SSE 事件："
tail -n 40 "${SSE_TMP}" || true
