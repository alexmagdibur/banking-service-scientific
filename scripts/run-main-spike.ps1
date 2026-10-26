param(
    [ValidateSet('v0','v1','v2','v3')][string]$Variant = 'v0',
    [ValidateRange(1,5)][int]$Repeat = 1
)
$ErrorActionPreference = 'Stop'
$project = 'E:\techprog\bankig-service-scientific'
$vm = 'amagdibur@192.168.92.129'
$key = Join-Path $env:TEMP 'codex-bank-vm-20261024-ed25519-user'
$sshOptions = @('-i', $key, '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=7', '-o', 'UserKnownHostsFile=NUL', '-o', 'StrictHostKeyChecking=no', '-o', 'LogLevel=ERROR')
$runId = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ') + "_spike_${Variant}_r" + $Repeat.ToString('00')
$out = Join-Path $project "results\$runId"
New-Item -ItemType Directory -Path $out -Force | Out-Null

function Remote([string]$command) {
    $output = & ssh @sshOptions $vm $command 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Remote command failed: $output" }
    return ($output | Out-String).Trim()
}
function ClockCheck([string]$path) {
    'windows_before_utc,vm_utc,windows_after_utc,vm_minus_windows_seconds,roundtrip_seconds' | Set-Content -LiteralPath $path
    1..3 | ForEach-Object {
        $a = [DateTimeOffset]::UtcNow
        $x = Remote 'date -u +%Y-%m-%dT%H:%M:%S.%NZ'
        $b = [DateTimeOffset]::UtcNow
        $mid = $a.AddTicks([long](($b-$a).Ticks/2))
        $t = [DateTimeOffset]::Parse($x,[Globalization.CultureInfo]::InvariantCulture)
        $offset = ($t-$mid).TotalSeconds
        $rtt = ($b-$a).TotalSeconds
        @($a.ToString('o'),$x,$b.ToString('o'),$offset.ToString('F6',[Globalization.CultureInfo]::InvariantCulture),$rtt.ToString('F6',[Globalization.CultureInfo]::InvariantCulture)) -join ',' | Add-Content -LiteralPath $path
    }
}

$runDetails = "RUN_ID=$runId`nSCENARIO=15ps_120s__40ps_60s__15ps_120s`nVARIANT=$Variant`nREPEAT=$Repeat`nVM=$vm`nPREALLOCATED_VUS=160_per_phase`nMAX_VUS=240_per_phase`n"
$runDetails | Set-Content -LiteralPath (Join-Path $out 'run-info.txt')
$switch = Remote ('cd $HOME/bank-research-20261024 && bash scripts/switch-variant.sh ' + $Variant)
$switch | Set-Content -LiteralPath (Join-Path $out 'variant-switch.txt')
$reset = Remote 'KUBECONFIG=$HOME/.kube/config k3s kubectl -n bank exec postgres-0 -- psql -U bank -d bank_test -v ON_ERROR_STOP=1 -f /tmp/reset-test-data.sql'
$reset | Set-Content -LiteralPath (Join-Path $out 'reset-before.txt')

$expected = if ($Variant -eq 'v0') { 1 } else { 3 }
$stableChecks = 0
for ($i=0; $i -lt 360; $i++) {
    $ready = Remote 'KUBECONFIG=$HOME/.kube/config k3s kubectl -n bank get deployment bank-api -o jsonpath="{.status.readyReplicas}"'
    try {
        $response = Remote 'curl -fsS --max-time 5 http://localhost:30080/api/accounts'
        $accounts = $response | ConvertFrom-Json
        if ($ready -eq [string]$expected -and $accounts.Count -eq 3) {
            $stableChecks++
            if ($stableChecks -ge 10) { break }
        } else { $stableChecks = 0 }
    } catch { $stableChecks = 0 }
    Start-Sleep -Seconds 2
}
if ($stableChecks -lt 10) { throw "API did not pass 10 consecutive checks with $expected Ready Pods" }
if (@($accounts | Where-Object { $_.balanceMinor -ne 10000000 }).Count -ne 0) { throw 'Balances not reset' }
$transfers = Remote 'KUBECONFIG=$HOME/.kube/config k3s kubectl -n bank exec postgres-0 -- psql -U bank -d bank_test -Atc "SELECT count(*) FROM transfers"'
if ($transfers -ne '0') { throw "Transfer table not empty: $transfers" }

$metricsReady = $false
for ($i=0; $i -lt 30; $i++) {
    try {
        $top = Remote 'KUBECONFIG=$HOME/.kube/config k3s kubectl -n bank top pods --no-headers'
        $apiSamples = @($top -split "`r?`n" | Where-Object { $_ -match '^bank-api-' }).Count
        if ($apiSamples -ge $expected) { $metricsReady = $true; break }
    } catch { }
    Start-Sleep -Seconds 2
}
if (-not $metricsReady) { throw "CPU metrics not available for all $expected Bank API Pods" }

ClockCheck (Join-Path $out 'clock-before.csv')
$pod = (Remote 'KUBECONFIG=$HOME/.kube/config k3s kubectl -n bank get pods -l app.kubernetes.io/name=bank-api -o jsonpath="{.items[0].metadata.name}"').Trim()
if (-not $pod.StartsWith('bank-api-')) { throw "Unexpected Pod name: $pod" }
$pod | Set-Content -LiteralPath (Join-Path $out 'target-pod.txt')
$before = Remote 'date -u +%Y-%m-%dT%H:%M:%S.%NZ; KUBECONFIG=$HOME/.kube/config k3s kubectl get nodes -o wide; KUBECONFIG=$HOME/.kube/config k3s kubectl -n bank get pods,deploy,hpa -o wide; KUBECONFIG=$HOME/.kube/config k3s kubectl -n bank top pods; free -h; df -h /'
$before | Set-Content -LiteralPath (Join-Path $out 'environment-before.txt')
$remoteDir = '$HOME/bank-research-20261024/results/' + $runId
$collectorCommand = 'mkdir -p ' + $remoteDir + '; nohup bash $HOME/bank-research-20261024/scripts/collect-cluster.sh ' + $remoteDir + '/cluster 330 5 >' + $remoteDir + '/collector.log 2>&1 </dev/null & echo $!'
$collectorPid = Remote $collectorCommand
$collectorPid | Set-Content -LiteralPath (Join-Path $out 'collector-pid.txt')

$eventJob = Start-Job -ArgumentList $key,$vm,$pod,(Join-Path $out 'raw.json') -ScriptBlock {
    param($keyPath,$targetVm,$targetPod,$rawPath)
    $anchor = $null
    for ($i=0; $i -lt 150; $i++) {
        if (Test-Path -LiteralPath $rawPath) {
            try {
                $line = Get-Content -LiteralPath $rawPath -TotalCount 20 | Where-Object { $_ -match '"metric":"bank_operations_started"' -and $_ -match '"type":"Point"' } | Select-Object -First 1
                if ($line) { $anchor = [DateTimeOffset]::Parse(($line | ConvertFrom-Json -DateKind String).data.time,[Globalization.CultureInfo]::InvariantCulture); break }
            } catch { }
        }
        Start-Sleep -Milliseconds 200
    }
    if (-not $anchor) { throw 'Could not observe first k6 operation in raw JSON' }
    $delay = ($anchor.AddSeconds(120)-[DateTimeOffset]::UtcNow).TotalMilliseconds
    if ($delay -gt 0) { Start-Sleep -Milliseconds ([int]$delay) }
    $opts = @('-i',$keyPath,'-o','BatchMode=yes','-o','ConnectTimeout=7','-o','UserKnownHostsFile=NUL','-o','StrictHostKeyChecking=no','-o','LogLevel=ERROR')
    $before = [DateTimeOffset]::UtcNow.ToString('o')
    $remoteCommand = 'date -u +%Y-%m-%dT%H:%M:%S.%NZ'
    $output = & ssh @opts $targetVm $remoteCommand 2>&1
    $after = [DateTimeOffset]::UtcNow.ToString('o')
    "first_k6_operation_utc=$($anchor.ToString('o'))"
    "target_event_time_utc=$($anchor.AddSeconds(120).ToString('o'))"
    "windows_before=$before"
    $output
    "windows_after=$after"
    if ($LASTEXITCODE -ne 0) { throw "Event command failed with exit $LASTEXITCODE" }
}

$image = 'grafana/k6@sha256:e66db15b860113878fa74670e31f5e274830b7b6e42c8bff28b2f2d86a257603'
$args = @('run','--rm','--mount',"type=bind,source=$project\load,target=/scripts,readonly",'--mount',"type=bind,source=$project\results,target=/results",'-e','BASE_URL=http://192.168.92.129:30080','-e',"RUN_ID=$runId",'-e','PREALLOCATED_VUS=160','-e','MAX_VUS=240',$image,'run','--out',"json=/results/$runId/raw.json",'--summary-export',"/results/$runId/summary.json",'/scripts/spike.js')
"k6_start_windows_utc=$([DateTimeOffset]::UtcNow.ToString('o'))" | Add-Content -LiteralPath (Join-Path $out 'run-info.txt')
& docker @args *> (Join-Path $out 'k6.log')
$dockerExit = $LASTEXITCODE
"k6_end_windows_utc=$([DateTimeOffset]::UtcNow.ToString('o'))" | Add-Content -LiteralPath (Join-Path $out 'run-info.txt')
$eventJob | Wait-Job | Out-Null
$eventOutput = Receive-Job -Job $eventJob 2>&1
$eventOutput | Set-Content -LiteralPath (Join-Path $out 'spike-event.txt')
$eventState = $eventJob.State
Remove-Job -Job $eventJob
"docker_exit=$dockerExit`nevent_job_state=$eventState" | Add-Content -LiteralPath (Join-Path $out 'run-info.txt')

Start-Sleep -Seconds 32
& scp @sshOptions -r "${vm}:bank-research-20261024/results/$runId/cluster" $out 2>&1 | Out-Host
if ($LASTEXITCODE -ne 0) { throw 'Cluster data copy failed' }
& scp @sshOptions "${vm}:bank-research-20261024/results/$runId/collector.log" $out 2>&1 | Out-Host
if ($LASTEXITCODE -ne 0) { throw 'Collector log copy failed' }
$after = Remote 'date -u +%Y-%m-%dT%H:%M:%S.%NZ; KUBECONFIG=$HOME/.kube/config k3s kubectl -n bank get pods,deploy,hpa -o wide; KUBECONFIG=$HOME/.kube/config k3s kubectl -n bank top pods; free -h; df -h /'
$after | Set-Content -LiteralPath (Join-Path $out 'environment-after.txt')
ClockCheck (Join-Path $out 'clock-after.csv')
$sql = 'KUBECONFIG=$HOME/.kube/config k3s kubectl -n bank exec postgres-0 -- psql -U bank -d bank_test -Atc "SELECT id,balance_minor FROM accounts ORDER BY id; SELECT count(*) FROM transfers; SELECT count(*)-count(DISTINCT idempotency_key) FROM transfers"'
$db = Remote $sql
$db | Set-Content -LiteralPath (Join-Path $out 'db-after.txt')
$keys = Remote 'KUBECONFIG=$HOME/.kube/config k3s kubectl -n bank exec postgres-0 -- psql -U bank -d bank_test -Atc "SELECT idempotency_key FROM transfers ORDER BY idempotency_key"'
$keys | Set-Content -LiteralPath (Join-Path $out 'db-keys.txt')
$transferTimes = Remote 'KUBECONFIG=$HOME/.kube/config k3s kubectl -n bank exec postgres-0 -- psql -U bank -d bank_test -A -F , -tc "SELECT idempotency_key,created_at FROM transfers ORDER BY idempotency_key"'
$transferTimes | Set-Content -LiteralPath (Join-Path $out 'db-transfer-times.csv')
$resetAfter = Remote 'KUBECONFIG=$HOME/.kube/config k3s kubectl -n bank exec postgres-0 -- psql -U bank -d bank_test -v ON_ERROR_STOP=1 -f /tmp/reset-test-data.sql'
$resetAfter | Set-Content -LiteralPath (Join-Path $out 'reset-after.txt')
"RUN_ID=$runId"
"DOCKER_EXIT=$dockerExit"
"EVENT_JOB_STATE=$eventState"
"OUTPUT_DIR=$out"

