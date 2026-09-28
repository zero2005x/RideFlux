# Copyright (C) 2026 RideFlux project contributors.
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Captures the Play Store phone screenshots from a running emulator.
#
# Prerequisites: one emulator running (Android 13+), the debug APK installed
# (`.\gradlew.bat :app:installDebug`), and Python with Pillow on PATH.
# Run from the repository root:
#   .\docs\play-store\tools\capture.ps1 -Locale zh-TW
#   .\docs\play-store\tools\capture.ps1 -Locale en-US
#
# Every screen is rendered by the debug-only StoreScreenshotActivity with
# sample data. All captures land in build\play-store-screenshots\<locale>;
# the published set is copied to docs\play-store\phone\<locale>.
param([string]$Locale = 'zh-TW')
$ErrorActionPreference = 'Stop'

$sdk = $env:ANDROID_HOME
if (-not $sdk) {
    $line = Get-Content local.properties | Where-Object { $_ -like 'sdk.dir=*' }
    $sdk = ($line -replace '^sdk\.dir=', '') -replace '\\\\', '\' -replace '\\:', ':'
}
$adb = Join-Path $sdk 'platform-tools\adb.exe'
$raw = "build\play-store-screenshots\$Locale"
$published = "docs\play-store\phone\$Locale"
New-Item -ItemType Directory -Force $raw, $published | Out-Null

# 9:16 at a density that fits the dashboard, dark UI, clean status bar.
& $adb shell wm size 1080x1920
& $adb shell wm density 360
& $adb shell cmd uimode night yes | Out-Null
& $adb shell cmd locale set-app-locales com.rideflux.app --locales $Locale
& $adb shell settings put global sysui_demo_allowed 1
function Demo([string[]]$extras) {
    & $adb shell am broadcast -a com.android.systemui.demo @extras | Out-Null
}
Demo '-e', 'command', 'enter'
Demo '-e', 'command', 'battery', '-e', 'level', '86', '-e', 'plugged', 'false'
Demo '-e', 'command', 'network', '-e', 'wifi', 'show', '-e', 'level', '4', '-e', 'fully', 'true'
Demo '-e', 'command', 'network', '-e', 'mobile', 'hide', '-e', 'sims', '1', '-e', 'nosim', 'hide'
Demo '-e', 'command', 'notifications', '-e', 'visible', 'false'

function Shot($name) {
    Start-Sleep -Milliseconds 1800
    # Match the status-bar clock to the dashboard's live clock.
    $hhmm = (& $adb shell date +%H%M).Trim()
    Demo '-e', 'command', 'clock', '-e', 'hhmm', $hhmm
    Start-Sleep -Milliseconds 400
    cmd /c "`"$adb`" exec-out screencap -p > $raw\$name.png"
}
function Launch($screen) {
    & $adb shell am force-stop com.rideflux.app
    & $adb shell am start -W -n com.rideflux.app/.screenshot.StoreScreenshotActivity --es screen $screen | Out-Null
    Start-Sleep 2
}

try {
    & $adb logcat -c -b crash
    Launch dashboard
    $pages = 'd1-main', 'd2-graph', 'd3-parameters', 'd4-bms', 'd5-trips', 'd6-events', 'd7-map'
    for ($i = 0; $i -lt $pages.Count; $i++) {
        Shot $pages[$i]
        if ($i -lt $pages.Count - 1) { & $adb shell input swipe 950 700 130 700 220 }
    }
    foreach ($s in 'history', 'detail', 'settings', 'hud') { Launch $s; Shot "s-$s" }

    $crash = & $adb logcat -d -b crash | Select-String 'FATAL|Caused by'
    if ($crash) { $crash | Select-Object -First 3 | ForEach-Object Line; throw 'App crashed during capture' }
} finally {
    Demo '-e', 'command', 'exit'
    & $adb shell wm size reset
    & $adb shell wm density reset
}

# Published set, in upload order. Play expects opaque 24-bit PNGs.
$pick = [ordered]@{
    'd1-main'       = '01-dashboard'
    'd2-graph'      = '02-live-charts'
    'd3-parameters' = '03-parameters'
    'd5-trips'      = '04-trip-recording'
    's-detail'      = '05-trip-detail'
    's-hud'         = '06-hud'
    's-settings'    = '07-alert-settings'
}
foreach ($k in $pick.Keys) {
    python -c "import sys; from PIL import Image; Image.open(sys.argv[1]).convert('RGB').save(sys.argv[2], optimize=True)" `
        "$raw\$k.png" "$published\$($pick[$k]).png"
}
Get-ChildItem $published | ForEach-Object Name
