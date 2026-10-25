# SPDX-License-Identifier: MIT
#
# generate-golden.ps1 — build the reference buffers from the real MTR rendering
# code, then verify the native pipeline against them.
#
# This is the equivalence gate. It does three things:
#
#   1. Assembles a classpath from the same prebuilt jars the mod compiles against
#      (libs/Minecraft-Mappings-*.jar plus their JOML / commons dependencies out
#      of the Gradle cache) and compiles native/tools/GenerateGolden.java.
#   2. Runs it over native/tests/golden/manifest.txt, which drives the real
#      ObjReader / ObjSplitting / ObjUtils / RawMesh code and writes one .golden
#      per case.
#   3. Runs the native test binary over the same directory, which rebuilds every
#      case natively and compares the buffers byte for byte.
#
# Usage:
#   pwsh -File tools/generate-golden.ps1
#   pwsh -File tools/generate-golden.ps1 -SkipBuild

[CmdletBinding()]
param(
    # Reuse an existing build tree instead of rebuilding first.
    [switch]$SkipBuild,

    # Keep the generated .golden files in the source tree after verifying.
    [switch]$KeepGoldens
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$nativeRoot = Split-Path -Parent $PSScriptRoot
$repoRoot = Split-Path -Parent $nativeRoot
$goldenSource = Join-Path $nativeRoot 'tests\golden'
$workRoot = Join-Path $nativeRoot 'build-windows\golden-work'
$classesDir = Join-Path $workRoot 'classes'

# ---------------------------------------------------------------------------
# Locate the JDK and a decompiler-free classpath
# ---------------------------------------------------------------------------

function Get-JavaTool {
    param([string]$Name)

    if ($env:JAVA_HOME) {
        $candidate = Join-Path $env:JAVA_HOME "bin\$Name.exe"
        if (Test-Path -LiteralPath $candidate) { return $candidate }
    }
    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    throw "$Name was not found. Set JAVA_HOME or put a JDK on PATH."
}

$javac = Get-JavaTool -Name 'javac'
$java = Get-JavaTool -Name 'java'

function Find-Jar {
    param([string]$GroupPath, [string]$NamePattern, [string]$PreferredVersion)

    $cacheRoot = Join-Path $env:USERPROFILE ".gradle\caches\modules-2\files-2.1\$GroupPath"
    if (-not (Test-Path -LiteralPath $cacheRoot)) { return $null }

    $candidates = Get-ChildItem -LiteralPath $cacheRoot -Recurse -Filter $NamePattern -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch 'sources|javadoc' }
    if (-not $candidates) { return $null }

    if ($PreferredVersion) {
        $preferred = $candidates | Where-Object { $_.FullName -match [regex]::Escape($PreferredVersion) } | Select-Object -First 1
        if ($preferred) { return $preferred.FullName }
    }
    return ($candidates | Sort-Object FullName -Descending | Select-Object -First 1).FullName
}

$mappingsJar = Join-Path $repoRoot 'libs\Minecraft-Mappings-fabric-1.20.1-0.0.1-dev.jar'
$commonJar = Join-Path $repoRoot 'libs\Minecraft-Mappings-common-0.0.1.jar'
foreach ($required in @($mappingsJar, $commonJar)) {
    if (-not (Test-Path -LiteralPath $required)) {
        throw "required jar not found: $required"
    }
}

$classpathEntries = @($mappingsJar, $commonJar)

# The OBJ path needs JOML (through the holder wrappers), commons-io for
# IOUtils.toInputStream, and commons-lang3 for StringUtils.isEmpty/equals.
$dependencyJars = @(
    @{ Group = 'org.joml\joml'; Pattern = 'joml-*.jar'; Version = '1.10.5' },
    @{ Group = 'commons-io\commons-io'; Pattern = 'commons-io-*.jar'; Version = $null },
    @{ Group = 'org.apache.commons\commons-lang3'; Pattern = 'commons-lang3-*.jar'; Version = $null },
    @{ Group = 'com.google.code.gson\gson'; Pattern = 'gson-*.jar'; Version = $null }
)
foreach ($dependency in $dependencyJars) {
    $jar = Find-Jar -GroupPath $dependency.Group -NamePattern $dependency.Pattern -PreferredVersion $dependency.Version
    if ($jar) {
        $classpathEntries += $jar
        Write-Host "  + $(Split-Path -Leaf $jar)"
    } else {
        Write-Warning "dependency not found in the Gradle cache: $($dependency.Pattern)"
    }
}

$classpath = ($classpathEntries -join [IO.Path]::PathSeparator)

# ---------------------------------------------------------------------------
# Compile the generator
# ---------------------------------------------------------------------------

New-Item -ItemType Directory -Force -Path $classesDir | Out-Null
Get-ChildItem -LiteralPath $classesDir -Filter '*.class' -ErrorAction SilentlyContinue | Remove-Item -Force

Write-Host 'Compiling native/tools/GenerateGolden.java'
& $javac -encoding UTF-8 -cp $classpath -d $classesDir (Join-Path $PSScriptRoot 'GenerateGolden.java')
if ($LASTEXITCODE -ne 0) { throw "javac failed with exit code $LASTEXITCODE" }

# ---------------------------------------------------------------------------
# Generate the goldens
# ---------------------------------------------------------------------------

New-Item -ItemType Directory -Force -Path $workRoot | Out-Null

# The generator writes next to the manifest, and it reads the OBJ/MTL files by name
# relative to that directory, so the golden inputs are copied into the work tree
# rather than generated into the source tree.
$workGolden = Join-Path $workRoot 'golden'
if (Test-Path -LiteralPath $workGolden) { Remove-Item -LiteralPath $workGolden -Recurse -Force }
New-Item -ItemType Directory -Force -Path $workGolden | Out-Null
Copy-Item -Path (Join-Path $goldenSource '*') -Destination $workGolden -Force

$manifest = Join-Path $workGolden 'manifest.txt'
Write-Host ''
Write-Host 'Generating reference buffers with the real MTR rendering code'
& $java -cp "$classesDir$([IO.Path]::PathSeparator)$classpath" GenerateGolden $manifest
if ($LASTEXITCODE -ne 0) { throw "golden generation failed with exit code $LASTEXITCODE" }

# ---------------------------------------------------------------------------
# Verify the native pipeline against them
# ---------------------------------------------------------------------------

if (-not $SkipBuild) {
    Write-Host ''
    Write-Host 'Building the native library'
    & pwsh -NoProfile -File (Join-Path $PSScriptRoot 'build-native.ps1') -SkipTests
    if ($LASTEXITCODE -ne 0) { throw "native build failed with exit code $LASTEXITCODE" }
}

$testExe = Get-ChildItem -LiteralPath (Join-Path $nativeRoot 'build-windows') -Recurse -Filter 'wrobj_tests.exe' -ErrorAction SilentlyContinue |
    Select-Object -First 1
if (-not $testExe) {
    throw 'wrobj_tests.exe was not found. Run tools/build-native.ps1 first.'
}

Write-Host ''
Write-Host 'Comparing the native pipeline against the reference buffers'
& $testExe.FullName $workGolden
$testExit = $LASTEXITCODE

if ($KeepGoldens) {
    Write-Host ''
    Write-Host "Reference buffers kept in $workGolden"
}

if ($testExit -ne 0) {
    throw "equivalence check failed with exit code $testExit"
}

Write-Host ''
Write-Host 'Equivalence verified: the native pipeline reproduces the Java buffers byte for byte.'
