# SPDX-License-Identifier: MIT
#
# run-native-tests.ps1 — build (if needed) and run the native OBJ pipeline tests.
#
# Kept separate from build-native.ps1 so a CI step can run just the tests against
# an existing build tree, and so the exit code is unambiguous:
#   0  every check passed
#   1  a check failed
#   2  the build tree or test binary is missing

[CmdletBinding()]
param(
    [string]$BuildDirectory,

    # Golden case directory. When omitted, the equivalence layer is skipped and
    # only the unit tests run.
    [string]$GoldenDirectory,

    # Build first instead of requiring an existing binary.
    [switch]$Build
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$nativeRoot = Split-Path -Parent $PSScriptRoot
if (-not $BuildDirectory) {
    $BuildDirectory = Join-Path $nativeRoot 'build-windows'
}

if ($Build) {
    $buildArgs = @('-File', (Join-Path $PSScriptRoot 'build-native.ps1'), '-SkipTests')
    if ($GoldenDirectory) { $buildArgs += @('-GoldenDirectory', $GoldenDirectory) }
    & pwsh -NoProfile @buildArgs
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

$testExe = Get-ChildItem -LiteralPath $BuildDirectory -Recurse -Filter 'wrobj_tests.exe' -ErrorAction SilentlyContinue |
    Select-Object -First 1
if (-not $testExe) {
    $testExe = Get-ChildItem -LiteralPath $BuildDirectory -Recurse -Filter 'wrobj_tests' -ErrorAction SilentlyContinue |
        Select-Object -First 1
}
if (-not $testExe) {
    Write-Error "wrobj_tests was not found under $BuildDirectory. Run tools/build-native.ps1 first."
    exit 2
}

if ($GoldenDirectory -and (Test-Path -LiteralPath $GoldenDirectory)) {
    & $testExe.FullName $GoldenDirectory
} else {
    & $testExe.FullName
}
exit $LASTEXITCODE
