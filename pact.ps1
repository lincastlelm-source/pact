<#
.SYNOPSIS
    Runs Gradle for this project with a JDK and Android SDK that the Android Gradle Plugin accepts.

.DESCRIPTION
    This machine's system Java is 26, which AGP rejects (it supports 17-21), and there is no
    Android SDK on PATH. This script points Gradle at the self-contained toolchain and forwards
    everything to the Gradle wrapper.

    If you later install Android Studio, or put a JDK 17-21 on JAVA_HOME yourself, you can call
    .\gradlew.bat directly instead and delete this file.

.EXAMPLE
    .\pact.ps1 :app:assembleDebug
    .\pact.ps1 :app:testDebugUnitTest
    .\pact.ps1 :app:assembleRelease
    .\pact.ps1 tasks
#>
[CmdletBinding()]
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $GradleArgs
)

$ErrorActionPreference = 'Stop'

$Toolchain = 'C:\Users\lincastle\android-toolchain'
$Jdk       = Join-Path $Toolchain 'jdk\jdk-21.0.12.1+1'
$Sdk       = Join-Path $Toolchain 'sdk'

if (-not (Test-Path $Jdk)) {
    Write-Error "JDK not found at $Jdk. See docs/ARCHITECTURE.md, or set JAVA_HOME to any JDK 17-21 and use .\gradlew.bat directly."
}
if (-not (Test-Path $Sdk)) {
    Write-Error "Android SDK not found at $Sdk. Set sdk.dir in local.properties to your own SDK."
}

$env:JAVA_HOME        = $Jdk
$env:ANDROID_HOME     = $Sdk
$env:ANDROID_SDK_ROOT = $Sdk

if (-not $GradleArgs -or $GradleArgs.Count -eq 0) {
    $GradleArgs = @(':app:assembleDebug')
}

Push-Location $PSScriptRoot
try {
    & (Join-Path $PSScriptRoot 'gradlew.bat') @GradleArgs
    exit $LASTEXITCODE
}
finally {
    Pop-Location
}
