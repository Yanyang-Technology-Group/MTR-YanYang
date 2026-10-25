# SPDX-License-Identifier: MIT
#
# check-java-integration.ps1 — compile the native integration classes against the
# development mappings jar, without Gradle.
#
# Why this exists: the mod's own source set cannot be compiled without Gradle, because
# Loom generates Keys.java and the schema classes and remaps the mappings jar. That
# makes a plain `javac` on the mod sources impossible, which in turn makes a syntax or
# signature error in the native integration expensive to find.
#
# This script compiles the subset that only depends on the mappings jar — the
# nativeobj package plus the two model wrappers — together with small stubs for the
# handful of mod classes they reference. It catches wrong method signatures, visibility
# mistakes and typos in seconds instead of after a full Gradle run.
#
# It is a fast check, not a replacement for the real build: the equivalence test and
# the full compile still run through Gradle.

[CmdletBinding()]
param(
    [string]$OutputDirectory,

    # Also compile and run NativeObjSmokeTest, which exercises the JNI bridge end to
    # end against the built library. Requires the library to exist.
    [switch]$RunSmokeTest,

    # Directory holding wrobj.dll / libwrobj.so. Defaults to native/build-windows.
    [string]$LibraryDirectory
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$nativeRoot = Split-Path -Parent $PSScriptRoot
$repoRoot = Split-Path -Parent $nativeRoot
$sourceRoot = Join-Path $repoRoot 'fabric\src\main\java'
if (-not $OutputDirectory) {
    $OutputDirectory = Join-Path $nativeRoot 'build-windows\java-check'
}

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

function Find-Jar {
    param([string]$GroupPath, [string]$NamePattern, [string]$PreferredVersion, [switch]$NativesOnly)

    $cacheRoot = Join-Path $env:USERPROFILE ".gradle\caches\modules-2\files-2.1\$GroupPath"
    if (-not (Test-Path -LiteralPath $cacheRoot)) { return $null }

    # -Filter is a filesystem filter rather than a PowerShell wildcard, so it has to be a
    # plain pattern; the -NativesOnly switch does the discriminating that -Filter cannot.
    $candidates = Get-ChildItem -LiteralPath $cacheRoot -Recurse -Filter $NamePattern -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch 'sources|javadoc' }
    if ($NativesOnly) {
        $candidates = $candidates | Where-Object { $_.Name -notmatch 'natives' }
    }
    if (-not $candidates) { return $null }

    if ($PreferredVersion) {
        $preferred = $candidates | Where-Object { $_.FullName -match [regex]::Escape($PreferredVersion) } | Select-Object -First 1
        if ($preferred) { return $preferred.FullName }
    }
    return ($candidates | Sort-Object FullName -Descending | Select-Object -First 1).FullName
}

$classpath = @(
    (Join-Path $repoRoot 'libs\Minecraft-Mappings-fabric-1.20.1-0.0.1-dev.jar'),
    (Join-Path $repoRoot 'libs\Minecraft-Mappings-common-0.0.1.jar'),
    (Join-Path $repoRoot 'libs\Shadow-Libraries-util-0.0.1.jar')
)
# Every spec carries NativesOnly explicitly: this script runs under StrictMode, where
# reading a hashtable key that was never set is an error rather than $null.
foreach ($spec in @(
    @{ Group = 'org.joml\joml'; Pattern = 'joml-*.jar'; Version = '1.10.5'; NativesOnly = $false },
    @{ Group = 'org.apache.commons\commons-lang3'; Pattern = 'commons-lang3-*.jar'; Version = $null; NativesOnly = $false },
    @{ Group = 'commons-io\commons-io'; Pattern = 'commons-io-*.jar'; Version = $null; NativesOnly = $false },
    @{ Group = 'com.google.code.findbugs\jsr305'; Pattern = 'jsr305-*.jar'; Version = $null; NativesOnly = $false },
    @{ Group = 'org.slf4j\slf4j-api'; Pattern = 'slf4j-api-*.jar'; Version = $null; NativesOnly = $false },
    # The GL host calls LWJGL directly, so the OpenGL bindings have to be on the classpath.
    #
    # The `nativesOnly` flag excludes the `...-natives-windows.jar` siblings that share this
    # directory: they contain only the native binaries and no classes, and the filter below
    # cannot express that. Note that Get-ChildItem's -Filter is a filesystem filter and does
    # not understand character classes, so the pattern is deliberately a plain wildcard.
    #
    # The version is pinned because the cache can hold several, and LWJGL 3.3.2 split the
    # versioned GL classes into per-version modules, so `org.lwjgl.opengl.GL33` no longer
    # exists there. Minecraft 1.20.1 ships 3.3.1, which is what the mod compiles against.
    @{ Group = 'org.lwjgl\lwjgl-opengl'; Pattern = 'lwjgl-opengl-*.jar'; Version = '3.3.1'; NativesOnly = $true },
    @{ Group = 'org.lwjgl\lwjgl'; Pattern = 'lwjgl-*.jar'; Version = '3.3.1'; NativesOnly = $true }
)) {
    $jar = Find-Jar -GroupPath $spec.Group -NamePattern $spec.Pattern -PreferredVersion $spec.Version -NativesOnly:$spec.NativesOnly
    if ($jar) { $classpath += $jar }
}

# A stub tree for the few mod classes the checked subset touches. Each one only has to
# satisfy the compiler; nothing here is ever executed.
$stubRoot = Join-Path $nativeRoot 'build-windows\java-check-stubs'
if (Test-Path -LiteralPath $stubRoot) { Remove-Item -LiteralPath $stubRoot -Recurse -Force }
New-Item -ItemType Directory -Force -Path $stubRoot | Out-Null

$stubs = @{
    'org\mtr\mod\data\IGui.java' = @'
package org.mtr.mod.data;

/**
 * Stub for the mod's GUI constants interface.
 *
 * Only ARGB_WHITE is referenced by the checked subset, and the value is copied from the real
 * interface so a compile here means the same thing it does in the mod. The real interface
 * also carries drawing helpers that would drag in the whole client.
 */
public interface IGui {
	int ARGB_WHITE = 0xFFFFFFFF;
	int ARGB_BLACK = 0xFF000000;
}
'@
    'org\mtr\mod\Init.java' = @'
package org.mtr.mod;

/** Stub: only the logger is referenced by the checked subset. */
public final class Init {
	public static final String MOD_ID = "mtr";
	public static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("mtr");
	private Init() {
	}
}
'@
}

foreach ($entry in $stubs.GetEnumerator()) {
    $target = Join-Path $stubRoot $entry.Key
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target) | Out-Null
    Set-Content -LiteralPath $target -Value $entry.Value -Encoding utf8
}

# The checked subset. Everything here depends only on the mappings jar plus the stub.
$files = @(
    'org\mtr\mod\resource\OptimizedObjModel.java',
    'org\mtr\mod\resource\JavaObjModel.java',
    'org\mtr\mod\resource\OptimizedModelWrapper.java',
    'org\mtr\mod\resource\nativeobj\NativeTextureResolver.java',
    'org\mtr\mod\resource\nativeobj\NativeObjKey.java',
    'org\mtr\mod\resource\nativeobj\NativeObjDraw.java',
    'org\mtr\mod\resource\nativeobj\NativeObjResult.java',
    'org\mtr\mod\resource\nativeobj\NativeObjPipeline.java',
    'org\mtr\mod\resource\nativeobj\NativeObjModel.java',
    'org\mtr\mod\resource\nativeobj\NativeObjModelAdapter.java',
    'org\mtr\mod\resource\nativeobj\NativeObjModelBuilder.java',
    'org\mtr\mod\resource\nativeobj\NativeGlHost.java',
    'org\mtr\mod\resource\nativeobj\NativeDrawList.java',
    'org\mtr\mod\resource\nativeobj\NativeModelAccess.java',
    # The renderer wrapper is checked too: it only depends on the mapping library plus the
    # access helper, and it is the file most likely to drift from the backend's API.
    'org\mtr\mod\resource\OptimizedRendererWrapper.java'
) | ForEach-Object { Join-Path $sourceRoot $_ }

foreach ($file in $files) {
    if (-not (Test-Path -LiteralPath $file)) {
        throw "expected source file is missing: $file"
    }
}

if (Test-Path -LiteralPath $OutputDirectory) { Remove-Item -LiteralPath $OutputDirectory -Recurse -Force }
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null

Write-Host "Type-checking $($files.Count) integration source file(s)"
$arguments = @('-encoding', 'UTF-8', '-nowarn', '-d', $OutputDirectory, '-cp', ($classpath -join [IO.Path]::PathSeparator)) + $files
$arguments += (Get-ChildItem -LiteralPath $stubRoot -Recurse -Filter '*.java' | ForEach-Object { $_.FullName })

& $javac @arguments
if ($LASTEXITCODE -ne 0) {
    throw "javac reported errors (exit code $LASTEXITCODE)"
}

Write-Host ''
Write-Host 'The native integration classes type-check against the development mappings jar.'

if (-not $RunSmokeTest) {
    return
}

# ---------------------------------------------------------------------------
# JNI smoke test
# ---------------------------------------------------------------------------

$java = Get-JavaTool -Name 'java'
if (-not $LibraryDirectory) {
    $LibraryDirectory = Join-Path $nativeRoot 'build-windows'
}

$libraryName = if ($IsWindows -or $env:OS -eq 'Windows_NT') { 'wrobj.dll' }
    elseif ($IsMacOS) { 'libwrobj.dylib' }
    else { 'libwrobj.so' }
$library = Join-Path $LibraryDirectory $libraryName
if (-not (Test-Path -LiteralPath $library)) {
    throw "the native library was not found at $library. Run tools/build-native.ps1 first."
}

$smokeSource = Join-Path $repoRoot 'fabric\src\test\java\org\mtr\mod\resource\nativeobj\NativeObjSmokeTest.java'
if (-not (Test-Path -LiteralPath $smokeSource)) {
    throw "the smoke test source was not found at $smokeSource"
}

Write-Host ''
Write-Host 'Compiling the JNI smoke test'
& $javac -encoding UTF-8 -nowarn -d $OutputDirectory -cp "$OutputDirectory$([IO.Path]::PathSeparator)$classpath" $smokeSource
if ($LASTEXITCODE -ne 0) {
    throw "javac failed on the smoke test (exit code $LASTEXITCODE)"
}

Write-Host ''
Write-Host "Running the JNI smoke test against $library"
# NativeObjPipeline falls back to the library path for the plain library name, and this
# JVM property is the standard way to extend it.
#
# slf4j-api is added explicitly rather than relied on from the type-check classpath. The mod's
# Init class declares its logger as a static field, so any code that touches Init - including the
# failure path of NativeModelAccess.isAvailable, which logs why the draw path was disabled -
# forces that class to initialise and fails with NoClassDefFoundError if the binding is absent.
$smokeClasspath = "$OutputDirectory$([IO.Path]::PathSeparator)$classpath"
$slf4jJar = Find-Jar -GroupPath 'org.slf4j\slf4j-api' -NamePattern 'slf4j-api-*.jar' -PreferredVersion $null -NativesOnly:$false
if ($slf4jJar) {
    $smokeClasspath = "$smokeClasspath$([IO.Path]::PathSeparator)$slf4jJar"
} else {
    Write-Host 'note: slf4j-api was not found; the smoke test may fail if it touches Init'
}
& $java "-Djava.library.path=$LibraryDirectory" -cp $smokeClasspath `
    org.mtr.mod.resource.nativeobj.NativeObjSmokeTest
$smokeExit = $LASTEXITCODE

if ($smokeExit -ne 0) {
    throw "the JNI smoke test failed with exit code $smokeExit"
}
