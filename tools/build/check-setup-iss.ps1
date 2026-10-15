<#
.SYNOPSIS
  Compiles setup.iss and fails if it does not.

.DESCRIPTION
  WHY THIS EXISTS. setup.iss used to be compiled in exactly one place - the release workflow,
  which runs only on a v*.*.* tag - and iscc runs at the very END of that build, after the jlink
  runtime, the pip wheels and the browser payload. So a one-character mistake in the installer
  script was undetectable until someone cut a tag, and then took about fifty minutes to report.
  Three release cycles went that way: CloseApplications in the wrong section, and twice on brace
  and quoting rules. Every one of them was a compile error that iscc reports in under a second.

  It is called by tools/release/windows.bat, as step 3b - straight after Inno Setup goes on PATH
  and well before the build that would otherwise report the error fifty minutes later. That is the
  only caller and the only machine that can be one: it needs Inno Setup's compiler, which is
  Windows only, and the pipeline runs on Ubuntu. It ran on pull requests until the GitHub workflows
  were deleted, and as a step of tools/ci/gate.bat until the gate moved to GitLab on 2026-09-21.

  WHAT IT DOES NOT COVER. It proves the script COMPILES, not that the installer is correct. The
  payload does not exist at this point, so [Files] entries are stubbed from the script's own
  Source: lines - which means a Source: naming a file the build never produces still passes here
  and fails in the real build. That is a different failure with a legible message ("the system
  cannot find the file specified", naming the file), not the cryptic compile error this guards.
#>
[CmdletBinding()]
param(
    # Repo root. Resolved below when not supplied - NOT defaulted here, because $PSScriptRoot is
    # not yet populated while param() defaults are evaluated in Windows PowerShell.
    [string] $RepoRoot
)

$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrEmpty($RepoRoot)) {
    $RepoRoot = (Resolve-Path (Join-Path (Join-Path $PSScriptRoot '..') '..')).Path
}

$source = Join-Path $RepoRoot 'apps/app/backend/proxyma-desktop/src/assembly/installer/setup.iss'
if (-not (Test-Path $source)) { Write-Error "setup.iss not found at $source"; exit 1 }

# ${env:ProgramFiles(x86)} needs the braces: $env:ProgramFiles(x86) parses as $env:ProgramFiles
# followed by a literal "(x86)" and silently resolves to a path that does not exist.
$iscc = Join-Path ${env:ProgramFiles(x86)} 'Inno Setup 6\ISCC.exe'
if (-not (Test-Path $iscc)) { Write-Error "ISCC.exe not found at $iscc - is Inno Setup installed?"; exit 1 }

$stage = Join-Path ([System.IO.Path]::GetTempPath()) ("iss-check-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $stage, "$stage/installer", "$stage/out" | Out-Null

try {
    # Maven filters the script during staging. Substituting a placeholder here keeps the check
    # independent of the real version - it is the syntax we are testing, not the number.
    $version = '0.0.0-syntaxcheck'
    $text = (Get-Content $source -Raw).Replace('@project.version@', $version)
    Set-Content "$stage/installer/setup.iss" -Value $text -Encoding UTF8

    # Stub every [Files] Source so iscc gets past file existence and on to parsing the rest of the
    # script. Derived from the script itself rather than hardcoded, so adding a file to [Files]
    # does not silently break this check.
    foreach ($m in [regex]::Matches($text, '(?im)^\s*Source:\s*"([^"]+)"')) {
        $rel = $m.Groups[1].Value
        # Split-Path -Parent is '' for a file sitting at the staging root, and Join-Path refuses
        # an empty argument - so fall back to the stage directory itself.
        $parent = Split-Path $rel -Parent
        $dir = if ([string]::IsNullOrEmpty($parent)) { $stage } else { Join-Path $stage $parent }
        New-Item -ItemType Directory -Force -Path $dir | Out-Null
        if ($rel -match '[\*\?]') {
            # A wildcard needs a directory with at least one file in it.
            New-Item -ItemType File -Force -Path (Join-Path $dir 'placeholder.txt') | Out-Null
        } else {
            $path = Join-Path $dir (Split-Path $rel -Leaf)
            if (-not (Test-Path $path)) { New-Item -ItemType File -Force -Path $path | Out-Null }
        }
    }
    # LicenseFile is resolved against SourceDir like a Source: line, and the real one is generated
    # by the build (from eula.html) - so it needs a stub for the same reason.
    foreach ($m in [regex]::Matches($text, '(?im)^\s*LicenseFile=(.+?)\s*$')) {
        $path = Join-Path $stage $m.Groups[1].Value
        New-Item -ItemType Directory -Force -Path (Split-Path $path -Parent) | Out-Null
        Set-Content $path -Value 'stub license text' -Encoding UTF8
    }
    # SetupIconFile and friends are read relative to the script's own directory.
    $icon = Join-Path $RepoRoot 'apps/app/backend/proxyma-desktop/src/assembly/installer/proxyma.ico'
    if (Test-Path $icon) { Copy-Item $icon (Join-Path $stage 'proxyma.ico') -Force }

    Push-Location $stage
    try {
        $out = & $iscc /Q "/O$stage/out" 'installer/setup.iss' 2>&1 | Out-String
        $code = $LASTEXITCODE
    } finally { Pop-Location }

    if ($code -ne 0) {
        Write-Host $out
        Write-Error "setup.iss does not compile. Fix it before this can produce an installer."
        exit 1
    }

    Write-Host "setup.iss compiles."
    exit 0
}
finally {
    Remove-Item $stage -Recurse -Force -ErrorAction SilentlyContinue
}
