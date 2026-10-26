#!/usr/bin/env bash
set -euo pipefail

pod="${1:?Pod name required}"
duration="${2:-60}"
if [[ ! "$pod" =~ ^bank-api-[a-z0-9-]+$ || ! "$duration" =~ ^[0-9]+$ ]]; then
  echo 'Invalid Pod name or duration' >&2
  exit 2
fi
export KUBECONFIG="${KUBECONFIG:-$HOME/.kube/config}"
log="/tmp/bank-fault-port-forward-${pod}-$$.log"
k3s kubectl -n bank port-forward --address 127.0.0.1 "pod/$pod" 18081:8081 >"$log" 2>&1 &
pf=$!
cleanup() { kill "$pf" 2>/dev/null || true; wait "$pf" 2>/dev/null || true; }
trap cleanup EXIT

reachable=false
for _ in {1..40}; do
  code="$(curl -sS --max-time 1 -o /dev/null -w '%{http_code}' 'http://127.0.0.1:18081/actuator/researchfault' 2>/dev/null || true)"
  if [[ "$code" =~ ^[1-5][0-9][0-9]$ ]]; then reachable=true; break; fi
  sleep 0.2
done
if [[ "$reachable" != true ]]; then
  echo 'Management port-forward did not become reachable' >&2
  cat "$log" >&2
  exit 1
fi

echo "pod=$pod"
echo "duration_seconds=$duration"
echo "vm_before_post_utc=$(date -u +'%Y-%m-%dT%H:%M:%S.%NZ')"
curl --fail-with-body --silent --show-error --max-time 10 \
  -X POST -H 'Content-Type: application/json' -d '{}' \
  "http://127.0.0.1:18081/actuator/researchfault?mode=UNRESPONSIVE&durationSeconds=$duration"
echo
echo "vm_after_post_utc=$(date -u +'%Y-%m-%dT%H:%M:%S.%NZ')"
