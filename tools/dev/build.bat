@echo off
REM ===============================================================================================
REM  build.bat - build one or both editions from the reactor root.
REM
REM      tools\dev\build.bat                 both editions
REM      tools\dev\build.bat desktop         desktop only
REM      tools\dev\build.bat server          server only
REM      tools\dev\build.bat desktop -Clean  wipe target\ first
REM
REM  One reactor: building an edition brings its dependencies from source, so there is no separate
REM  "build the libraries first" step.
REM
REM  See docs\development.html#run-scripts for every argument and for what each one costs.
REM ===============================================================================================
setlocal EnableDelayedExpansion
set "PX_COMMON=%~dp0common.bat"

set "EDITION=both"
set "DO_CLEAN="
set "SKIP_FRONTEND="

:parse
if "%~1"=="" goto :parsed
if /i "%~1"=="desktop"       ( set "EDITION=desktop"   & shift & goto :parse )
if /i "%~1"=="server"        ( set "EDITION=server"    & shift & goto :parse )
if /i "%~1"=="both"          ( set "EDITION=both"      & shift & goto :parse )
if /i "%~1"=="-Clean"        ( set "DO_CLEAN=1"        & shift & goto :parse )
if /i "%~1"=="-SkipFrontend" ( set "SKIP_FRONTEND=1"   & shift & goto :parse )
if /i "%~1"=="-h"            ( goto :usage )
if /i "%~1"=="--help"        ( goto :usage )
if /i "%~1"=="/?"            ( goto :usage )
echo [ERROR] Unknown argument: %~1
goto :usage

:usage
echo.
echo   tools\dev\build.bat [desktop^|server^|both] [-Clean] [-SkipFrontend]
echo.
echo     desktop ^| server ^| both   which edition to build (default: both^)
echo     -Clean                    wipe target\ first; a warm incremental build is ~15s
echo     -SkipFrontend             COMPILE VALIDATION ONLY - the jar bundles no UI
echo.
exit /b 1

:parsed
call "%PX_COMMON%" init_toolchain || exit /b 1
call "%PX_COMMON%" project_version || exit /b 1
call "%PX_COMMON%" ok "version  = %PX_VERSION%"

set "GOALS=package"
if defined DO_CLEAN set "GOALS=clean package"

set "MVN_COMMON=-DskipTests --no-transfer-progress"
if defined SKIP_FRONTEND (
    REM  COMPILE VALIDATION ONLY, and there is no safe combination. CLAUDE.md warned that
    REM  -Dskip.frontend.build=true combined with `clean` wipes target\classes\static\ and never
    REM  repopulates it; measured 2026-08-31, a plain `package` with the flag does it too. The jar
    REM  went from 31 BOOT-INF/classes/static/ entries to 2, and the resulting jar starts and
    REM  serves no UI. Never copy one over a real install. Check by ENTRY COUNT, not file size:
    REM  the jar is ~342 MB of bundled runtime either way and the SPA disappears into rounding.
    call "%PX_COMMON%" warn "Frontend build SKIPPED - the resulting jar bundles no UI. Compile validation only."
    call "%PX_COMMON%" warn "This holds with or without -Clean. Rebuild without -SkipFrontend before running it."
    set "MVN_COMMON=!MVN_COMMON! -Dskip.frontend.build=true"
) else (
    REM  The Maven build runs `npm ci` itself, through frontend-maven-plugin, so this script cannot
    REM  wrap that call - but it can make sure the directory it is about to delete is not already
    REM  half-deleted from an interrupted run. Left alone, the plugin fails with a bare
    REM  "Process exited with an error: -4051" that names nothing.
    call "%PX_COMMON%" clear_wrecked_node_modules "%PX_ROOT%\apps\app\frontend" || exit /b 1
)

REM  -Dskip.installer=true on every desktop build here. The installer's trim-playwright-driver step
REM  shells out to bare `python`, which on a machine without a real Python on PATH is the Windows
REM  Store alias decoy, and the build then fails with Ant "exec returned: 9009" AFTER writing a
REM  perfectly good jar. Local development wants the jar, not the .exe - tools\release\windows.bat
REM  builds the installer, by hand, when a release is cut.
if /i "%EDITION%"=="desktop" call :build proxyma-desktop "-Dskip.installer=true" || exit /b 1
if /i "%EDITION%"=="both"    call :build proxyma-desktop "-Dskip.installer=true" || exit /b 1

REM  Server mode compiles only under -Pserver: the Enterprise modules (ee\) join the reactor only
REM  in that profile, so without it the module does not build at all.
if /i "%EDITION%"=="server" call :build proxyma-server "-Pserver" || exit /b 1
if /i "%EDITION%"=="both"   call :build proxyma-server "-Pserver" || exit /b 1

call "%PX_COMMON%" step "Build complete"
echo     tools\dev\run.bat desktop            start the desktop edition on a scratch data directory
echo     tools\dev\run.bat server             start the server edition (needs PostgreSQL^)
echo     tools\dev\run.bat desktop -Seed      ... and inject the pseudo data set first
exit /b 0


REM  %1 = module   %2 = extra maven arguments
:build
call "%PX_COMMON%" step "Building %~1"
pushd "%PX_ROOT%"
call mvn -pl :%~1 -am %GOALS% %MVN_COMMON% %~2
set "CODE=%errorlevel%"
popd
if not "%CODE%"=="0" (
    call "%PX_COMMON%" err "%~1 build failed (exit %CODE%)."
    exit /b 1
)
REM  Assert rather than trust: the build can write a perfectly good jar and still fail a later
REM  packaging step, and it can also succeed while producing only the thin unclassified jar.
set "JAR=%PX_ROOT%\apps\app\backend\%~1\target\%~1-%PX_VERSION%-exec.jar"
if /i "%~1"=="proxyma-server" set "JAR=%PX_ROOT%\ee\backend\%~1\target\%~1-%PX_VERSION%-exec.jar"
if not exist "%JAR%" (
    call "%PX_COMMON%" err "Build reported success but %~1-%PX_VERSION%-exec.jar is not there."
    exit /b 1
)
for %%F in ("%JAR%") do set /a MIB=%%~zF / 1048576
call "%PX_COMMON%" ok "%~1-%PX_VERSION%-exec.jar  (%MIB% MiB)"
exit /b 0
