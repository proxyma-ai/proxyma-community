@echo off
:: Proxyma - wheel downloader for the Windows python part
::
::   download-wheels.bat [--unattended] <wheels-dir> <requirements.txt>
::
:: Downloads every wheel requirements.txt needs into <wheels-dir> and writes SHA256SUMS.txt beside
:: them. The launcher installs from that directory with --no-index --find-links, so a user's machine
:: never resolves anything against PyPI. Called by the Maven build's "build-windows-installer" step
:: (proxyma-desktop/pom.xml) with --unattended, which makes it exit with a status instead of
:: pausing for a keypress.
::
:: Needs Python 3.13 on PATH - the same minor as the bundled embeddable Python, or pip resolves
:: wheels for the wrong ABI and every one of them is refused on the user's machine - and the network,
:: on THIS machine only.

setlocal enabledelayedexpansion

set "UNATTENDED=0"
if /i "%~1"=="--unattended" (
    set "UNATTENDED=1"
    shift
)
set "WHEELS_DIR=%~1"
set "REQUIREMENTS=%~2"
if "%WHEELS_DIR%"=="" goto :usage
if "%REQUIREMENTS%"=="" goto :usage
:: Backslashes only. Maven passes paths with forward slashes, and `for %%f in ("...\*.whl")` below
:: matches NOTHING in a path that has any - so the checksum file came out empty and the launcher
:: skipped the check without a word.
set "WHEELS_DIR=%WHEELS_DIR:/=\%"
set "REQUIREMENTS=%REQUIREMENTS:/=\%"

echo ============================================================
echo  Proxyma - wheels for the python part
echo ============================================================
echo  Target:       %WHEELS_DIR%
echo  Requirements: %REQUIREMENTS%
echo.

python --version 2>nul | findstr /C:"Python 3.13" >nul
if errorlevel 1 (
    echo [ERROR] Python 3.13 not found on PATH.
    echo         Install Python 3.13 (must match the bundled embeddable version^).
    goto :fail
)
for /f "tokens=*" %%v in ('python --version 2^>^&1') do echo [OK]   Found %%v

if not exist "%WHEELS_DIR%" mkdir "%WHEELS_DIR%"

:: ONE resolution for the whole list. Downloading package by package resolved each separately, and
:: two of them wanted different onnxruntime versions - so both were shipped, and which one pip then
:: installed depended on the order of the launcher's calls.
echo [....] Downloading wheels...
python -m pip download -r "%REQUIREMENTS%" --dest "%WHEELS_DIR%"
if errorlevel 1 (
    echo [ERROR] Failed to download the wheels.
    goto :fail
)
echo [OK]   Wheels downloaded
echo.

:: The launcher (ProxymaLauncher.verifyWheelChecksums) checks every wheel against this before pip
:: sees it. Regenerated in full every run.
::
:: Python, not certutil. The certutil loop this replaced filtered its banner out with findstr AND
:: skipped the first remaining line - which was the hash - so it wrote an empty file on every build,
:: and the launcher, finding no entries, skipped the check without a word.
echo [....] Generating SHA-256 checksums...
set "SUMS_FILE=%WHEELS_DIR%\SHA256SUMS.txt"
python -c "import hashlib,os,sys; d=sys.argv[1]; names=sorted(n for n in os.listdir(d) if n.endswith('.whl')); open(os.path.join(d,'SHA256SUMS.txt'),'w',newline=chr(10)).writelines(hashlib.sha256(open(os.path.join(d,n),'rb').read()).hexdigest()+'  '+n+chr(10) for n in names)" "%WHEELS_DIR%"
if errorlevel 1 (
    echo [ERROR] Could not write %SUMS_FILE%.
    goto :fail
)
for /f %%c in ('dir /b "%WHEELS_DIR%\*.whl" 2^>nul ^| C:\Windows\System32\find.exe /c /v ""') do set WHEEL_COUNT=%%c
echo [OK]   %WHEEL_COUNT% wheel(s), checksums in %SUMS_FILE%
goto :end

:usage
echo usage: download-wheels.bat [--unattended] ^<wheels-dir^> ^<requirements.txt^>

:fail
echo.
echo Download failed. Resolve the error above and re-run.
if "%UNATTENDED%"=="1" (
    endlocal
    exit /b 1
)
:end
:: THE CHECK COMES BEFORE endlocal, and that is the whole point. `endlocal` discards everything
:: `setlocal` established, UNATTENDED included, and a batch file expands a variable when it reads
:: the line - so a test after it always compared an EMPTY string and the pause always ran, which
:: left an unattended build waiting for a key press forever.
if "%UNATTENDED%"=="1" (
    endlocal
    exit /b 0
)
endlocal
pause
