#!/usr/bin/env bash
set -euo pipefail

out_dir="${1:-}"
duration="${2:-330}"
interval="${3:-5}"
if [[ -z "$out_dir" || ! "$duration" =~ ^[0-9]+$ || ! "$interval" =~ ^[0-9]+$ || "$interval" -eq 0 ]]; then
  echo "Usage: $0 OUTPUT_DIR [DURATION_SECONDS=330] [INTERVAL_SECONDS=5]" >&2
  exit 2
fi
if [[ -e "$out_dir/pod_resources.csv" ]]; then
  echo "Refusing to append to an existing run: $out_dir" >&2
  exit 2
fi

mkdir -p "$out_dir"
export KUBECONFIG="${KUBECONFIG:-$HOME/.kube/config}"
echo 'timestamp_utc,pod,cpu,memory' > "$out_dir/pod_resources.csv"
echo 'timestamp_utc,deployment_desired,deployment_ready,hpa_current,hpa_desired' > "$out_dir/replicas.csv"
echo 'timestamp_utc,pod,phase' > "$out_dir/pods.csv"
echo 'timestamp_utc,error' > "$out_dir/collector_errors.csv"

end=$((SECONDS + duration))
while (( SECONDS < end )); do
  now="$(date -u +'%Y-%m-%dT%H:%M:%S.%3NZ')"

  if resource_lines="$(k3s kubectl -n bank top pods --no-headers 2>&1)"; then
    while read -r pod cpu memory _; do
      [[ -z "${pod:-}" ]] && continue
      printf '%s,%s,%s,%s\n' "$now" "$pod" "$cpu" "$memory" >> "$out_dir/pod_resources.csv"
    done <<< "$resource_lines"
  else
    printf '%s,%q\n' "$now" "$resource_lines" >> "$out_dir/collector_errors.csv"
  fi

  deployment="$(k3s kubectl -n bank get deployment bank-api -o jsonpath='{.spec.replicas},{.status.readyReplicas}' 2>/dev/null || echo ',')"
  hpa="$(k3s kubectl -n bank get hpa bank-api -o jsonpath='{.status.currentReplicas},{.status.desiredReplicas}' 2>/dev/null || echo ',')"
  printf '%s,%s,%s\n' "$now" "$deployment" "$hpa" >> "$out_dir/replicas.csv"

  if pod_lines="$(k3s kubectl -n bank get pods -l app.kubernetes.io/name=bank-api -o custom-columns=NAME:.metadata.name,PHASE:.status.phase --no-headers 2>&1)"; then
    while read -r pod phase _; do
      [[ -z "${pod:-}" ]] && continue
      printf '%s,%s,%s\n' "$now" "$pod" "$phase" >> "$out_dir/pods.csv"
    done <<< "$pod_lines"
  else
    printf '%s,%q\n' "$now" "$pod_lines" >> "$out_dir/collector_errors.csv"
  fi

  sleep "$interval"
done

k3s kubectl -n bank get events --sort-by=.metadata.creationTimestamp -o json > "$out_dir/events.json"
echo "Cluster samples saved in $out_dir"
