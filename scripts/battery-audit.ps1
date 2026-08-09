# Measure what Korabooks actually does while it sits in the background.
#
# Answers, with numbers rather than reading:
#   - how much CPU the process burns per second while idle, and which thread
#   - which wakelocks it holds, which alarms and jobs are scheduled for it
#   - what the last discharge cycle attributed to its uid
#   - how much it sent over the network with nobody watching
#
# Usage (PowerShell, phone connected):
#   .\scripts\battery-audit.ps1
#   .\scripts\battery-audit.ps1 -Seconds 120        # longer idle window
#   .\scripts\battery-audit.ps1 -Package io.github.mkdevtests.korabooks
#
# Turn the screen off when told to: a spinning thread spins either way, but
# anything driven by the frame clock only shows up when the screen is off.
#
# Caveat worth knowing before reading the output: over USB the phone is
# charging, so Doze never engages. CPU-per-second is measured correctly, but
# for the standby picture read the BATTERYSTATS section, which covers the last
# real discharge.
#
# ASCII only, on purpose: Windows PowerShell 5.1 reads this file as ANSI and a
# single accented character turns the whole script into a parse error.

param(
    [string]$Package = "",
    [int]$Seconds = 60,
    [string]$OutFile = "$env:TEMP\korabooks-battery-audit.txt"
)

$ErrorActionPreference = "Stop"

function Adb-Shell([string]$cmd) {
    # stderr is captured by the harness; don't redirect it (see CLAUDE.md).
    & adb shell $cmd
}

$devices = & adb devices
if (-not ($devices | Select-String -Pattern "\tdevice$")) {
    Write-Output "No device. Plug the tablet in, allow USB debugging, run again."
    exit 1
}

# ----- resolve the package (release build first, then .debug) -----
if ($Package -eq "") {
    $candidates = @(
        "io.github.mkdevtests.korabooks",
        "io.github.mkdevtests.korabooks.debug",
        "io.github.mkdevtests.korabooks.r8test"
    )
    $installed = Adb-Shell "pm list packages"
    foreach ($c in $candidates) {
        # Anchored: a substring match on the release id also matches the
        # ".debug" line, and would pick a package that isn't installed.
        $pattern = "^package:" + [regex]::Escape($c) + "$"
        if ($installed | Select-String -Pattern $pattern) { $Package = $c; break }
    }
}
if ($Package -eq "") {
    Write-Output "Korabooks is not installed on this device."
    exit 1
}

$pidRaw = (Adb-Shell "pidof $Package" | Out-String).Trim()
if ($pidRaw -eq "") {
    Write-Output "$Package is not running. Launch it, go back to the launcher, run again."
    exit 1
}
# pidof can return several pids (the :error_handler process); keep the first.
$procId = ($pidRaw -split "\s+")[0]

$uid = (Adb-Shell "dumpsys package $Package | grep -m1 userId=" | Out-String).Trim()

Write-Output "Package : $Package"
Write-Output "PID     : $procId   ($uid)"
Write-Output "Window  : $Seconds s"
Write-Output ""
Write-Output ">>> Turn the screen OFF now and leave the device alone for $Seconds seconds."
Write-Output ""

$clkTck = (Adb-Shell "getconf CLK_TCK" | Out-String).Trim()
if (-not ($clkTck -match "^\d+$")) { $clkTck = "100" }
$clkTck = [int]$clkTck

# ----- two samples of every thread's CPU, taken on-device so the gap is exact -----
$sampler = "for t in /proc/$procId/task/*/stat; do cat " + '$t' + "; done"
$raw = Adb-Shell "$sampler; echo ===CUT===; sleep $Seconds; $sampler"
$rawText = ($raw | Out-String)
$halves = $rawText -split "===CUT==="
if ($halves.Count -lt 2) {
    Write-Output "Sampling failed (did the process die during the window?)."
    exit 1
}

function Parse-Threads([string]$block) {
    $map = @{}
    foreach ($line in ($block -split "`n")) {
        $line = $line.Trim()
        if ($line -eq "") { continue }
        # /proc/<pid>/stat: "<tid> (<comm>) <state> ..." - comm can contain
        # spaces ("Jit thread pool"), so cut on the last ')'.
        $open = $line.IndexOf("(")
        $close = $line.LastIndexOf(")")
        if ($open -lt 0 -or $close -lt $open) { continue }
        $tid = $line.Substring(0, $open).Trim()
        $comm = $line.Substring($open + 1, $close - $open - 1)
        $rest = $line.Substring($close + 2) -split "\s+"
        # After comm, fields are state(3), ppid(4)... so utime(14) is rest[11]
        # and stime(15) is rest[12], zero-indexed.
        if ($rest.Count -lt 13) { continue }
        $jiffies = [int64]$rest[11] + [int64]$rest[12]
        $map[$tid] = @{ Name = $comm; Jiffies = $jiffies }
    }
    return $map
}

$before = Parse-Threads $halves[0]
$after = Parse-Threads $halves[1]

$rows = @()
$totalDelta = 0
foreach ($tid in $after.Keys) {
    $d = $after[$tid].Jiffies
    if ($before.ContainsKey($tid)) { $d = $d - $before[$tid].Jiffies }
    if ($d -le 0) { continue }
    $totalDelta += $d
    $rows += [pscustomobject]@{
        Thread      = $after[$tid].Name
        Tid         = $tid
        CpuMsPerSec = [math]::Round(($d * 1000.0 / $clkTck) / $Seconds, 1)
    }
}
$rows = $rows | Sort-Object -Property CpuMsPerSec -Descending

$totalCpuPercent = [math]::Round((($totalDelta * 100.0 / $clkTck) / $Seconds), 2)

$report = New-Object System.Text.StringBuilder
function Add-Section([string]$title, $body) {
    [void]$report.AppendLine("")
    [void]$report.AppendLine("=== $title ===")
    [void]$report.AppendLine(($body | Out-String))
}

$stamp = Get-Date -Format s
[void]$report.AppendLine("Korabooks battery audit - $stamp")
[void]$report.AppendLine("package=$Package pid=$procId $uid window=${Seconds}s CLK_TCK=$clkTck")
[void]$report.AppendLine("")
[void]$report.AppendLine("IDLE PROCESS CPU: $totalCpuPercent % of one core")
[void]$report.AppendLine("(under 0.2 % = normal; over 2 % = something is looping)")

Add-Section "BUSIEST THREADS (ms of CPU per second)" ($rows | Select-Object -First 20 | Format-Table -AutoSize)

# Which api is live decides whether the Komga SSE retry loop runs at all, so
# read the flag rather than guess. Debuggable builds only.
$modeInner = 'for f in files/*offline*.sqlite; do echo $f; sqlite3 $f "select is_offline_mode_enabled from SETTINGS;"; done'
$modeQuery = "run-as $Package sh -c '$modeInner'"
Add-Section "MODE (is_offline_mode_enabled: 1 = offline, 0 = online)" (Adb-Shell $modeQuery)
Add-Section "WAKELOCKS HELD" (Adb-Shell "dumpsys power | grep -i -A40 'Wake Locks:'")
Add-Section "ALARMS" (Adb-Shell "dumpsys alarm | grep -i -B2 -A6 korabooks")
Add-Section "JOBS (WorkManager goes through here)" (Adb-Shell "dumpsys jobscheduler | grep -i -A12 korabooks")
Add-Section "DOZE / IDLE" (Adb-Shell "dumpsys deviceidle | head -30")
Add-Section "BATTERYSTATS (last discharge cycle)" (Adb-Shell "dumpsys batterystats --charged $Package | head -120")
Add-Section "NETWORK PER UID" (Adb-Shell "dumpsys netstats detail | grep -i -A6 korabooks | head -60")
Add-Section "RUNNING SERVICES" (Adb-Shell "dumpsys activity services $Package | head -40")
Add-Section "MEMORY / GC (constant GC pressure is constant CPU)" (Adb-Shell "dumpsys meminfo $Package | head -30")

$report.ToString() | Out-File -FilePath $OutFile -Encoding utf8

Write-Output "Idle process CPU: $totalCpuPercent % of one core"
Write-Output ""
$rows | Select-Object -First 10 | Format-Table -AutoSize
Write-Output "Full report: $OutFile"
