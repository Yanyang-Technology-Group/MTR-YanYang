# SPDX-License-Identifier: MIT
#
# build-native.ps1 — configure and build the native OBJ pipeline on Windows.
#
# The MSVC toolchain is not on PATH by default, so this script locates a Visual
# Studio installation, imports its environment, and then drives CMake. Ninja is
# preferred because it produces a single-config build tree that matches the CMake
# presets in this directory; the Visual Studio generator is the fallback.
#
# Usage:
#   pwsh -File tools/build-native.ps1                 # Release build + test run
#   pwsh -File tools/build-native.ps1 -Configuration Debug -SkipTests
#   pwsh -File tools/build-native.ps1 -Clean

[CmdletBinding()]
param(
    [ValidateSet('Debug', 'Release', 'RelWithDebInfo', 'MinSizeRel')]
    [string]$Configuration = 'Release',

    # Directory for the build tree. Defaults to native/build-windows.
    [string]$BuildDirectory,

    # Re-run CMake configure even when a cache is present.
    [switch]$Reconfigure,

    # Delete the build tree first.
    [switch]$Clean,

    # Skip the test run after a successful build.
    [switch]$SkipTests,

    # Additional native tests directory argument (golden cases).
    [string]$GoldenDirectory
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

# This script lives in native/tools, so the native root is one level up.
$nativeRoot = Split-Path -Parent $PSScriptRoot
if (-not $BuildDirectory) {
    $BuildDirectory = Join-Path $nativeRoot 'build-windows'
}

# ---------------------------------------------------------------------------
# Locate the MSVC toolchain
# ---------------------------------------------------------------------------

function Get-VisualStudioPath {
    $vswhereCandidates = @(
        (Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\Installer\vswhere.exe'),
        (Join-Path $env:ProgramFiles 'Microsoft Visual Studio\Installer\vswhere.exe')
    )
    foreach ($candidate in $vswhereCandidates) {
        if ($candidate -and (Test-Path -LiteralPath $candidate)) {
            $path = & $candidate -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath 2>$null
            if ($path) { return $path.Trim() }
            $path = & $candidate -latest -products * -property installationPath 2>$null
            if ($path) { return $path.Trim() }
        }
    }

    # Fall back to scanning the usual install roots.
    foreach ($root in @('D:\Visual Studio', 'C:\Program Files\Microsoft Visual Studio', 'C:\Program Files (x86)\Microsoft Visual Studio')) {
        if (-not (Test-Path -LiteralPath $root)) { continue }
        $found = Get-ChildItem -LiteralPath $root -Directory -ErrorAction SilentlyContinue |
            ForEach-Object {
                Get-ChildItem -LiteralPath $_.FullName -Directory -ErrorAction SilentlyContinue
            } |
            Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'VC\Auxiliary\Build\vcvars64.bat') } |
            Select-Object -First 1
        if ($found) { return $found.FullName }
    }
    return $null
}

function Import-MsvcEnvironment {
    param([string]$VisualStudioPath)

    $vcvars = Join-Path $VisualStudioPath 'VC\Auxiliary\Build\vcvars64.bat'
    if (-not (Test-Path -LiteralPath $vcvars)) {
        throw "vcvars64.bat not found under $VisualStudioPath"
    }

    # Run the batch file in cmd and capture the resulting environment, then apply
    # it to this process so cmake inherits a usable toolchain.
    $output = & cmd.exe /c "`"$vcvars`" >nul 2>&1 && set"
    foreach ($line in $output) {
        $separator = $line.IndexOf('=')
        if ($separator -lt 1) { continue }
        $name = $line.Substring(0, $separator)
        $value = $line.Substring($separator + 1)
        Set-Item -Path "env:$name" -Value $value -ErrorAction SilentlyContinue
    }
    Write-Host "MSVC environment imported from $vcvars"
}

# ---------------------------------------------------------------------------
# Configure and build
# ---------------------------------------------------------------------------

if ($Clean -and (Test-Path -LiteralPath $BuildDirectory)) {
    Write-Host "Removing $BuildDirectory"
    Remove-Item -LiteralPath $BuildDirectory -Recurse -Force
}

$needMsvc = $true
if ($env:CC -and $env:CXX) { $needMsvc = $false }
if ($needMsvc) {
    $vsPath = Get-VisualStudioPath
    if (-not $vsPath) {
        throw 'No Visual Studio installation with the C++ toolset was found. Install the Desktop development with C++ workload.'
    }
    Import-MsvcEnvironment -VisualStudioPath $vsPath
}

$ninja = Get-Command ninja -ErrorAction SilentlyContinue
$generatorArgs = @()
if ($ninja) {
    $generatorArgs = @('-G', 'Ninja', "-DCMAKE_BUILD_TYPE=$Configuration")
    Write-Host "Generator: Ninja"
} else {
    $generatorArgs = @('-G', 'Visual Studio 18 2026', '-A', 'x64')
    Write-Host "Generator: Visual Studio 18 2026 (Ninja was not found on PATH)"
}

$cacheFile = Join-Path $BuildDirectory 'CMakeCache.txt'
if ($Reconfigure -or -not (Test-Path -LiteralPath $cacheFile)) {
    Write-Host "Configuring $BuildDirectory"
    & cmake -S $nativeRoot -B $BuildDirectory @generatorArgs
    if ($LASTEXITCODE -ne 0) { throw "CMake configure failed with exit code $LASTEXITCODE" }
}

Write-Host "Building ($Configuration)"
if ($ninja) {
    & cmake --build $BuildDirectory
} else {
    & cmake --build $BuildDirectory --config $Configuration
}
if ($LASTEXITCODE -ne 0) { throw "Build failed with exit code $LASTEXITCODE" }

# ---------------------------------------------------------------------------
# Test
# ---------------------------------------------------------------------------

if (-not $SkipTests) {
    $testExe = Get-ChildItem -LiteralPath $BuildDirectory -Recurse -Filter 'wrobj_tests.exe' -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if (-not $testExe) {
        throw 'wrobj_tests.exe was not produced; check that WROBJ_BUILD_TESTS is ON.'
    }
    Write-Host "`nRunning $($testExe.FullName)"
    if ($GoldenDirectory -and (Test-Path -LiteralPath $GoldenDirectory)) {
        & $testExe.FullName $GoldenDirectory
    } else {
        & $testExe.FullName
    }
    if ($LASTEXITCODE -ne 0) { throw "Tests failed with exit code $LASTEXITCODE" }
    Write-Host 'All native tests passed.'
}

Write-Host "`nBuild tree: $BuildDirectory"
Get-ChildItem -LiteralPath $BuildDirectory -Recurse -Include 'wrobj*.dll', 'wrobj*.lib', 'wrobj_tests.exe', 'wrobj_bench.exe' -ErrorAction SilentlyContinue |
    Select-Object @{ n = 'Artifact'; e = { $_.FullName.Substring($BuildDirectory.Length + 1) } }, Length |
    Format-Table -AutoSize
