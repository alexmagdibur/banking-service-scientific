#!/usr/bin/env bash
set -euo pipefail

variant="${1:-}"
case "$variant" in
  v0|v1|v2|v3) ;;
  *) echo "Usage: $0 v0|v1|v2|v3" >&2; exit 2 ;;
esac

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export KUBECONFIG="${KUBECONFIG:-$HOME/.kube/config}"

# A previous V3 HPA otherwise remains active when applying V0, V1 or V2.
k3s kubectl -n bank delete hpa bank-api --ignore-not-found --wait=true
k3s kubectl apply -k "$project_root/k8s/variants/$variant"
k3s kubectl -n bank rollout status deployment/bank-api --timeout=180s
k3s kubectl -n bank get deployment bank-api
k3s kubectl -n bank get pods -l app.kubernetes.io/name=bank-api -o wide
if [[ "$variant" == v3 ]]; then
  k3s kubectl -n bank get hpa bank-api
fi
echo "Active variant: $variant. Verify probes, replica count and client readiness before recording a run."
