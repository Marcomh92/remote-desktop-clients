[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$OperationFile,

    [Parameter()]
    [string]$LockFile = ".build-lock",

    [Parameter()]
    [int]$TimeoutSeconds = 120,

    [Parameter()]
    [int]$PollIntervalSeconds = 5
)

$ErrorActionPreference = "Stop"

$LockFile = [System.IO.Path]::GetFullPath($LockFile)

if (-not (Test-Path -Path $OperationFile)) {
    Write-Error "Operation file not found: $OperationFile"
    exit 2
}

function Test-LockFileAvailable {
    param([string]$Path)
    try {
        # Check for stale lock from a killed process
        if (Test-Path -Path $Path) {
            $content = Get-Content -Path $Path -Raw -ErrorAction SilentlyContinue
            if ($content -match '^\d+$') {
                $stalePid = [int]$content
                $process = Get-Process -Id $stalePid -ErrorAction SilentlyContinue
                if (-not $process) {
                    # Lock owner is dead — clean up stale lock
                    Remove-Item -Path $Path -Force -ErrorAction SilentlyContinue
                }
            }
        }

        # Attempt to create the file atomically. If it already exists, this throws an IOException.
        $stream = [System.IO.File]::Open(
            $Path,
            [System.IO.FileMode]::CreateNew,
            [System.IO.FileAccess]::Write,
            [System.IO.FileShare]::None
        )
        # Write our PID so stale locks can be detected later
        $writer = [System.IO.StreamWriter]::new($stream)
        $writer.Write($PID)
        $writer.Flush()
        $stream.Close()
        return $true
    } catch [System.IO.IOException] {
        return $false
    } catch {
        return $false
    }
}

function Wait-AndAcquireLock {
    param(
        [string]$Path,
        [int]$Timeout,
        [int]$PollInterval
    )
    $startTime = [datetime]::UtcNow
    while ($true) {
        if (Test-LockFileAvailable -Path $Path) {
            return $true
        }

        $elapsed = ([datetime]::UtcNow - $startTime).TotalSeconds
        if ($elapsed -ge $Timeout) {
            return $false
        }

        Start-Sleep -Seconds $PollInterval
    }
}

function Remove-LockFile {
    param([string]$Path)
    if (Test-Path -Path $Path) {
        Remove-Item -Path $Path -Force -ErrorAction SilentlyContinue
    }
}

if (-not (Wait-AndAcquireLock -Path $LockFile -Timeout $TimeoutSeconds -PollInterval $PollIntervalSeconds)) {
    # Exit code 100 signals to the caller that the lock was already held and the wait timed out.
    # (100 is used instead of 1 to avoid collision with gradlew's build-failure exit code.)
    exit 100
}

try {
    # Execute the operation file directly so stdout/stderr passes through to the caller.
    # (Start-Process would NOT forward the child's output streams to the parent console.)
    cmd /c "$OperationFile"
    exit $LASTEXITCODE
} finally {
    Remove-LockFile -Path $LockFile
}
