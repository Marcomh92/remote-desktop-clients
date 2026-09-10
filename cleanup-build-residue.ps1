<#
.SYNOPSIS
    Cleans up residue from the WSL-based FreeRDP native build process.

.DESCRIPTION
    Removes ~5+ GB of build artifacts left over from the multi-hour
    FreeRDP rebuild (NDK extraction, Gradle outputs, cloned source
    tree, my .tmp/ work products, and the WSL Ubuntu toolchain).

    PRESERVES the project source tree: none of the 9 modified files
    or the new patch (22_freerdp_add_cursor_callback.patch) are touched.

    All flags default to "remove". Use -Keep* flags to skip a phase.
    Every phase is idempotent — safe to re-run if interrupted.

.PARAMETER KeepWindowsNDK
    Keep the extracted Android NDK at remoteClientLib/android-ndk-r25c/
    (~1.6 GB) and its .zip. Only safe to keep if you intend to rebuild.

.PARAMETER KeepGradleOutputs
    Keep <module>/build/ directories (the aRDP-app-debug.apk files,
    intermediate .dex, .class files, R.class). Useful if you want
    to re-install without rebuilding.

.PARAMETER KeepDepsTree
    Keep remoteClientLib/jni/libs/deps/FreeRDP/ (the cloned + patched
    FreeRDP source tree used to produce the .so). Keeps <2 GB; a clean
    rebuild would re-fetch it but skipping saves ~3 minutes.

.PARAMETER KeepTmpDir
    Keep .tmp/ (my gen_patch.py, verify_patch.py, source mirrors,
    build logs). ~few MB.

.PARAMETER KeepWSLToolchain
    Keep /home/marc/jdk21/, /home/marc/android-sdk/, /home/marc/ardp_setup/,
    /home/marc/tmp_build/, and the ardp-build systemd unit. ~5 GB.

.PARAMETER WSLDistro
    Override the WSL distro name. Default: "Ubuntu".

.EXAMPLE
    .\cleanup-build-residue.ps1
    Full cleanup. ~5+ GB reclaimed.

.EXAMPLE
    .\cleanup-build-residue.ps1 -KeepWSLToolchain
    Windows-side cleanup only. Keeps the JDK 21 + Android SDK installed
    in WSL for future rebuilds.

.EXAMPLE
    .\cleanup-build-residue.ps1 -KeepWSLToolchain -KeepDepsTree -KeepTmpDir
    Minimal cleanup. Only removes NDK install + Gradle build outputs.
#>

[CmdletBinding()]
param(
    [switch]$KeepWindowsNDK,
    [switch]$KeepGradleOutputs,
    [switch]$KeepDepsTree,
    [switch]$KeepTmpDir,
    [switch]$KeepWSLToolchain,
    [string]$WSLDistro = "Ubuntu"
)

$ErrorActionPreference = "Continue"
$repoRoot = (Resolve-Path "$PSScriptRoot/..").Path
Write-Host "Cleanup script — repo root: $repoRoot"
Write-Host ""

# ---------------------------------------------------------------------------
# Phase 1 — Windows host residue
# ---------------------------------------------------------------------------

# 1.1  Android NDK install (sometimes drops at remoteClientLib/android-ndk-*)
if (-not $KeepWindowsNDK) {
    Write-Host "=== Phase 1.1: Android NDK install residue ==="
    Get-ChildItem -Path "$repoRoot/remoteClientLib" -Filter "android-ndk-*.zip" -ErrorAction SilentlyContinue |
        ForEach-Object { Write-Host "  rm $($_.FullName)"; Remove-Item -LiteralPath $_.FullName -Force }
    Get-ChildItem -Path "$repoRoot/remoteClientLib" -Directory -Filter "android-ndk-*" -ErrorAction SilentlyContinue |
        ForEach-Object { Write-Host "  rm $($_.FullName) (full tree)"; Remove-Item -LiteralPath $_.FullName -Recurse -Force }
} else {
    Write-Host "[skipped] Phase 1.1: -KeepWindowsNDK"
}

# 1.2  Gradle build outputs (every Gradle module + top-level)
if (-not $KeepGradleOutputs) {
    Write-Host ""
    Write-Host "=== Phase 1.2: Gradle build outputs ==="
    $modules = @(
        'aRDP-app', 'aSPICE-app', 'bVNC', 'bVNC-app', 'common',
        'CustomVnc-app', 'freeaRDP-app', 'freeaSPICE-app', 'freebVNC-app',
        'Opaque-app', 'pubkeyGenerator', 'remoteClientLib',
        'freeRDPCore'  # we wrote a custom alias to deps/; covered below too
    )
    foreach ($m in $modules) {
        $p = Join-Path $repoRoot "$m/build"
        if (Test-Path $p) { Write-Host "  rm $p"; Remove-Item -LiteralPath $p -Recurse -Force -ErrorAction SilentlyContinue }
    }
    $top = Join-Path $repoRoot "build"
    if (Test-Path $top) { Write-Host "  rm $top"; Remove-Item -LiteralPath $top -Recurse -Force -ErrorAction SilentlyContinue }
} else {
    Write-Host "[skipped] Phase 1.2: -KeepGradleOutputs"
}

# 1.3  Prebuilt-dependencies tarball (in .gitignore)
Write-Host ""
Write-Host "=== Phase 1.3: prebuilt-dependencies tarball ==="
Get-ChildItem -Path $repoRoot -Filter "remote-desktop-clients-libs-*.tar.gz" -ErrorAction SilentlyContinue |
    ForEach-Object { Write-Host "  rm $($_.FullName)"; Remove-Item -LiteralPath $_.FullName -Force }

# 1.4  Cloned FreeRDP source tree (~6000 files) — only if not keeping
if (-not $KeepDepsTree) {
    Write-Host ""
    Write-Host "=== Phase 1.4: FreeRDP deps/ tree (clone) ==="
    $depsRoot = "$repoRoot/remoteClientLib/jni/libs/deps"
    if (Test-Path $depsRoot) {
        Write-Host "  rm $depsRoot (FreeRDP clone; rebuild will re-fetch)"
        Remove-Item -LiteralPath $depsRoot -Recurse -Force
    }
    # Also ndk extracted inside jni/libs/ if a previous build did that
    Get-ChildItem -Path "$repoRoot/remoteClientLib/jni/libs" -Directory -Filter "android-ndk-*" -ErrorAction SilentlyContinue |
        ForEach-Object { Write-Host "  rm $($_.FullName) (full tree)"; Remove-Item -LiteralPath $_.FullName -Recurse -Force }
    Get-ChildItem -Path "$repoRoot/remoteClientLib/jni/libs" -Filter "android-ndk-*.zip" -ErrorAction SilentlyContinue |
        ForEach-Object { Write-Host "  rm $($_.FullName)"; Remove-Item -LiteralPath $_.FullName -Force }
    Get-ChildItem -Path "$repoRoot/remoteClientLib/jni/libs" -Directory -Filter "cmake-*" -ErrorAction SilentlyContinue |
        ForEach-Object { Write-Host "  rm $($_.FullName)"; Remove-Item -LiteralPath $_.FullName -Recurse -Force }
} else {
    Write-Host "[skipped] Phase 1.4: -KeepDepsTree"
}

# 1.5  .tmp/ (my work products; few MB)
if (-not $KeepTmpDir) {
    Write-Host ""
    Write-Host "=== Phase 1.5: .tmp/ ==="
    $tmp = "$repoRoot/.tmp"
    if (Test-Path $tmp) {
        Write-Host "  rm $tmp (gen_patch.py, verify_patch.py, source mirrors, build logs)"
        Remove-Item -LiteralPath $tmp -Recurse -Force
    }
} else {
    Write-Host "[skipped] Phase 1.5: -KeepTmpDir"
}

# 1.6  .gradle/ cache (optional; usually preserved)
# not removed by default — speeds up next run

# ---------------------------------------------------------------------------
# Phase 2 — WSL Ubuntu toolchain
# ---------------------------------------------------------------------------

if (-not $KeepWSLToolchain) {
    Write-Host ""
    Write-Host "=== Phase 2: WSL Ubuntu toolchain ($WSLDistro) ==="
    $wslStatus = wsl --status 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Host "  wsl --status failed: $wslStatus"
        Write-Host "  WSL may not be installed or the distro is not registered."
        Write-Host "  Manual cleanup of WSL side: see README at top of this script."
    } else {
        $wslCleanup = @'
set -e
echo "  -- stopping ardp-build systemd unit (if any)"
systemctl --user stop ardp-build.service 2>/dev/null || true
systemctl --user reset-failed ardp-build.service 2>/dev/null || true

echo "  -- removing WSL install roots"
for p in /home/marc/jdk21 /home/marc/android-sdk /home/marc/ardp_setup /home/marc/tmp_build; do
    if [ -e "$p" ]; then
        echo "    rm -rf $p"
        rm -rf "$p"
    fi
done

echo "  -- removing bashrc-managed env block (idempotent: removes only lines we added)"
sed -i '/^export JAVA_HOME=\/home\/marc\/jdk21$/d' ~/.bashrc 2>/dev/null || true
sed -i '/^export ANDROID_HOME=\/home\/marc\/android-sdk$/d' ~/.bashrc 2>/dev/null || true
sed -i '/^export ANDROID_SDK_ROOT=\/home\/marc\/android-sdk$/d' ~/.bashrc 2>/dev/null || true
sed -i '/^export PATH="\$JAVA_HOME\/bin:\$ANDROID_HOME\/cmdline-tools\/latest\/bin:\$ANDROID_HOME\/platform-tools:\$ANDROID_HOME\/ndk/d' ~/.bashrc 2>/dev/null || true

echo "  -- WSL cleanup OK"
'@
        Write-Host "  invoking WSL cleanup..."
        wsl -d $WSLDistro -- /bin/bash -c $wslCleanup
        if ($LASTEXITCODE -eq 0) {
            Write-Host "  WSL cleanup OK"
        } else {
            Write-Host "  WSL cleanup reported exit code $LASTEXITCODE (some hosts/paths may not exist; that's fine)"
        }
    }
} else {
    Write-Host "[skipped] Phase 2: -KeepWSLToolchain"
}

# ---------------------------------------------------------------------------
# Done
# ---------------------------------------------------------------------------

Write-Host ""
Write-Host "=== Cleanup complete ==="
Write-Host ""
Write-Host "Working tree status:"
git status --short
