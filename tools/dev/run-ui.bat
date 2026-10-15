@echo off
REM ===============================================================================================
REM  run-ui.bat - Vite dev server for the SPA, pointed at a backend started by run.bat.
REM
REM      tools\dev\run-ui.bat desktop
REM      tools\dev\run-ui.bat server -Port 4300
REM
REM  Both editions bake the SPA into their jar, so the app served on the backend port is always the
REM  bundle from the last build. This is the way to iterate on the frontend without rebuilding it:
REM  the dev server serves the SPA with hot reload on :5173 and proxies /api and /oauth2 through to
REM  the running backend.
REM
REM  THE EDITION MATTERS HERE AND IS NOT COSMETIC. `mode` selects which settings pages are in the
REM  module graph at all (an alias on @settings-pages), so a desktop dev server cannot render a
REM  server-only page even if something asks it to - the code is not there to render. Running the
REM  wrong mode against a backend is the one way to see settings that do not match the edition.
REM ===============================================================================================
setlocal EnableDelayedExpansion
set "PX_COMMON=%~dp0common.bat"

set "EDITION=desktop"
set "PORT=0"
set "DO_INSTALL="

:parse
if "%~1"=="" goto :parsed
if /i "%~1"=="desktop"  ( set "EDITION=desktop" & shift & goto :parse )
if /i "%~1"=="server"   ( set "EDITION=server"  & shift & goto :parse )
if /i "%~1"=="-Install" ( set "DO_INSTALL=1"    & shift & goto :parse )
if /i "%~1"=="-Port"    ( set "PORT=%~2"        & shift & shift & goto :parse )
if /i "%~1"=="-h"       ( goto :usage )
if /i "%~1"=="--help"   ( goto :usage )
if /i "%~1"=="/?"       ( goto :usage )
echo [ERROR] Unknown argument: %~1
goto :usage

:usage
echo.
echo   tools\dev\run-ui.bat [desktop^|server] [-Port N] [-Install]
echo.
echo     -Port N     the backend actually running. Defaults: 4299 desktop, 4300 server
echo     -Install    reinstall node_modules from the lockfile first
echo.
exit /b 1

:parsed
call "%PX_COMMON%" init_toolchain || exit /b 1

if "%PORT%"=="0" (
    if /i "%EDITION%"=="server" ( set "PORT=4300" ) else ( set "PORT=4299" )
)

set "FRONTEND=%PX_ROOT%\apps\app\frontend"

REM  Node is not installed system-wide. frontend-maven-plugin fetches a private copy into
REM  apps\app\frontend\node during the first backend build that includes the SPA, and
REM  init_toolchain puts it on PATH when it exists.
where npm >nul 2>&1
if not "%errorlevel%"=="0" (
    call "%PX_COMMON%" err "No npm on PATH, and none under apps\app\frontend\node."
    call "%PX_COMMON%" err "Run build.bat once (without -SkipFrontend) - the backend build provisions Node itself."
    exit /b 1
)

REM  The second test also catches node_modules present but half-deleted by an interrupted install,
REM  which otherwise looks complete and then fails inside vite with a missing package rather than
REM  at the install that never finished.
set "NEED_INSTALL="
if defined DO_INSTALL set "NEED_INSTALL=1"
if not exist "%FRONTEND%\node_modules" set "NEED_INSTALL=1"
if exist "%FRONTEND%\node_modules" if not exist "%FRONTEND%\node_modules\.package-lock.json" set "NEED_INSTALL=1"
if defined NEED_INSTALL (
    call "%PX_COMMON%" step "npm ci"
    call "%PX_COMMON%" npm_ci "%FRONTEND%" || exit /b 1
)

REM  vite.config.ts defaults the proxy target to :4246, which is the INSTALLED app's port. Left
REM  unset, the dev server would quietly talk to whatever is running there instead of the scratch
REM  instance - the frontend equivalent of the data-directory trap run.bat exists to avoid, and
REM  just as invisible.
REM
REM  PROXYMA_DEV_API_TARGET, not VITE_API_BASE_URL: the VITE_ prefix would put this address into
REM  the client bundle, and the page would then call the backend directly on another port instead
REM  of through the proxy - a cross-origin request the desktop edition sends no CORS headers for.
REM  The proxy exists precisely so the browser only ever sees one origin.
set "PROXYMA_DEV_API_TARGET=http://127.0.0.1:%PORT%"
REM  Cleared in case an earlier shell exported it - vite.config falls back to it, and a stale value
REM  there would silently win over the port actually chosen here.
set "VITE_API_BASE_URL="

if /i "%EDITION%"=="server" ( set "NPM_SCRIPT=dev:server" ) else ( set "NPM_SCRIPT=dev" )

call "%PX_COMMON%" ok "edition  = %EDITION%"
call "%PX_COMMON%" ok "backend  = %PROXYMA_DEV_API_TARGET%"
call "%PX_COMMON%" step "vite (%NPM_SCRIPT%) on http://localhost:5173  (Ctrl+C to stop)"

pushd "%FRONTEND%"
call npm run %NPM_SCRIPT%
set "CODE=%errorlevel%"
popd
exit /b %CODE%
