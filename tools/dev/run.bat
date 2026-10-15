@echo off
REM ===============================================================================================
REM  run.bat - start a built edition against a throwaway data directory.
REM
REM      tools\dev\run.bat desktop
REM      tools\dev\run.bat desktop -Seed -Reset
REM      tools\dev\run.bat server -StartDb -Seed
REM
REM  THE WHOLE POINT OF THIS SCRIPT IS THAT IT NEVER TOUCHES THE INSTALLED APP. Every path and
REM  port the app resolves is redirected, and the traps that make "isolated" runs silently not
REM  isolated are handled here rather than left to be remembered:
REM
REM    * LOCALAPPDATA, not --proxyma.data.dir. AppPaths resolves the data root from the environment
REM      variable and never reads that property; passing it moves the H2 JDBC URLs and NOTHING
REM      else, leaving ChromaDB, Lucene, conversations\, markdown\, temp\, logs\, the bundled
REM      Python and .secret.dpapi on the real install. A "scratch" run once started ChromaDB
REM      against the REAL vector store and ran a sync through it.
REM    * Skills and workflows default to %USERPROFILE%\.proxyma\..., which LOCALAPPDATA does not
REM      move. Two "isolated" instances otherwise share one skill set with each other AND with the
REM      installed app, so both are overridden below.
REM    * NOTHING SETS -Djava.net.preferIPv6Addresses ANY MORE. ChromaDB used to bind [::1] and
REM      be reached at localhost, so the JVM was bent IPv6-first; that is JVM-wide and broke
REM      every outbound call on an IPv4-only network. ChromaStoreService binds and addresses
REM      chroma by literal 127.0.0.1 now, so no preference is needed. See its chromaHost field.
REM      for 60s - which reads exactly like "ChromaDB is missing" and invites the wrong conclusion
REM      that Docker is needed.
REM    * Non-default ports, so a managed ChromaDB start cannot attach to the installed app's.
REM
REM  See docs\development.html#run-scripts for every argument.
REM ===============================================================================================
setlocal EnableDelayedExpansion
set "PX_COMMON=%~dp0common.bat"

set "EDITION=desktop"
set "DO_SEED="
set "DO_RESET="
set "PORT=0"
set "CHROMA_PORT=8001"
REM  Deliberately short. The bundled Python runtime's deepest file needs 144 characters of its own,
REM  and robocopy skips whatever overflows MAX_PATH *silently* - a long scratch root produces a
REM  runtime that looks copied and is not.
set "DATA_ROOT=C:\pxdev"
set "NO_RUNTIME="
set "NO_BROWSER="
set "DB_URL="
set "DB_USER="
set "DB_PASSWORD="
set "START_DB="

:parse
if "%~1"=="" goto :parsed
if /i "%~1"=="desktop"      ( set "EDITION=desktop"     & shift & goto :parse )
if /i "%~1"=="server"       ( set "EDITION=server"      & shift & goto :parse )
if /i "%~1"=="-Seed"        ( set "DO_SEED=1"           & shift & goto :parse )
if /i "%~1"=="-Reset"       ( set "DO_RESET=1"          & shift & goto :parse )
if /i "%~1"=="-NoRuntime"   ( set "NO_RUNTIME=1"        & shift & goto :parse )
REM  Accepted and ignored: the copy it used to request is now what happens by default. Kept so a
REM  command somebody already has in their history does not fail on an unknown argument.
if /i "%~1"=="-WithRuntime" ( shift & goto :parse )
if /i "%~1"=="-NoBrowser"   ( set "NO_BROWSER=1"        & shift & goto :parse )
if /i "%~1"=="-StartDb"     ( set "START_DB=1"          & shift & goto :parse )
if /i "%~1"=="-Port"        ( set "PORT=%~2"            & shift & shift & goto :parse )
if /i "%~1"=="-ChromaPort"  ( set "CHROMA_PORT=%~2"     & shift & shift & goto :parse )
if /i "%~1"=="-DataRoot"    ( set "DATA_ROOT=%~2"       & shift & shift & goto :parse )
if /i "%~1"=="-DbUrl"       ( set "DB_URL=%~2"          & shift & shift & goto :parse )
if /i "%~1"=="-DbUser"      ( set "DB_USER=%~2"         & shift & shift & goto :parse )
if /i "%~1"=="-DbPassword"  ( set "DB_PASSWORD=%~2"     & shift & shift & goto :parse )
REM Anything starting -D is a JVM system property, passed straight through. Added so a dev switch
REM that has no dedicated flag here - -Dproxyma.updates.releases-api-url for the fake releases API,
REM say - does not need one invented every time. Collected in order and appended AFTER the script's
REM own properties, so a passthrough deliberately wins over a default this script sets.
REM
REM Batch's == is exact equality, so it cannot express "starts with -D" the way the flags above are
REM matched. The argument is copied into a variable first because only a variable can be sliced -
REM !ARG:~0,2! is its first two characters, and that needs the delayed expansion this script already
REM enables.
REM
REM IT MUST BE QUOTED BY THE CALLER: "-Dfoo=bar", not -Dfoo=bar. Batch treats = as an argument
REM DELIMITER, exactly like a space, so an unquoted property arrives here as two arguments and the
REM value half then falls through to "Unknown argument: bar". That is confusing enough to be worth
REM the line in the usage text.
set "ARG=%~1"
if "!ARG:~0,2!"=="-D" ( set "EXTRA_JAVA_ARGS=!EXTRA_JAVA_ARGS! !ARG!" & shift & goto :parse )
if /i "%~1"=="-h"           ( goto :usage )
if /i "%~1"=="--help"       ( goto :usage )
if /i "%~1"=="/?"           ( goto :usage )
echo [ERROR] Unknown argument: %~1
goto :usage

:usage
echo.
echo   tools\dev\run.bat [desktop^|server] [options]
echo.
echo     -Seed                 inject the pseudo data set first (costs one extra boot^)
echo     -Reset                delete the scratch data directory first
echo     -Port N               default 4299 desktop, 4300 server
echo     -ChromaPort N         default 8001
echo     -DataRoot PATH        default C:\pxdev - keep it SHORT, see MAX_PATH note above
echo     -NoRuntime            do not copy the bundled Python/ChromaDB runtime
echo     -NoBrowser            do not open a browser when the app starts serving
echo     -StartDb              server: start a throwaway PostgreSQL+pgvector container
echo     -DbUrl ^| -DbUser ^| -DbPassword    server: point at a database you already run
echo     "-Dname=value"        any JVM system property, passed through to the app.
echo                           QUOTE it - batch splits an unquoted argument at the = sign.
echo.
exit /b 1

:parsed
call "%PX_COMMON%" init_toolchain || exit /b 1
call "%PX_COMMON%" project_version || exit /b 1

if "%PORT%"=="0" (
    if /i "%EDITION%"=="server" ( set "PORT=4300" ) else ( set "PORT=4299" )
)

set "INSTANCE_ROOT=%DATA_ROOT%\%EDITION%"

if defined DO_RESET if exist "%INSTANCE_ROOT%" (
    call "%PX_COMMON%" step "Resetting %INSTANCE_ROOT%"
    rmdir /s /q "%INSTANCE_ROOT%" 2>nul
    if exist "%INSTANCE_ROOT%" (
        REM  Almost always a previous instance still running on this data directory, holding its
        REM  own log file or ChromaDB's sqlite. Name the cause rather than one file - and name
        REM  ChromaDB's python explicitly, because it is a CHILD of the app that outlives a Ctrl+C
        REM  in the terminal and is the half people miss.
        call "%PX_COMMON%" err "Could not delete %INSTANCE_ROOT% - something still has it open."
        call "%PX_COMMON%" err "An earlier run is probably still going, including the ChromaDB it started:"
        call "%PX_COMMON%" err "that is a python.exe under this directory and it does not always go away"
        call "%PX_COMMON%" err "with Ctrl+C. Stop them with:"
        echo.
        echo         taskkill /F /IM python.exe
        echo         taskkill /F /IM java.exe
        echo.
        exit /b 1
    )
)

if not exist "%INSTANCE_ROOT%" mkdir "%INSTANCE_ROOT%"
set "SKILLS_DIR=%INSTANCE_ROOT%\skills"
set "WORKFLOWS_DIR=%INSTANCE_ROOT%\workflows"
if not exist "%SKILLS_DIR%" mkdir "%SKILLS_DIR%"
if not exist "%WORKFLOWS_DIR%" mkdir "%WORKFLOWS_DIR%"

if /i "%EDITION%"=="desktop" ( call :desktop_launch || exit /b 1 ) else ( call :server_launch || exit /b 1 )

call "%PX_COMMON%" ok "edition  = %EDITION% %PX_VERSION%"
call "%PX_COMMON%" ok "data     = %INSTANCE_ROOT%"
call "%PX_COMMON%" ok "url      = http://127.0.0.1:%PORT%"

if defined DO_SEED (
    call :schema_boot || exit /b 1
    call :seed || exit /b 1
)

REM  A detached poller rather than an inline wait, because java runs in the FOREGROUND below and
REM  this script has no "after startup" to hook. It exits quietly on timeout: a browser that does
REM  not open is a small annoyance, and a script that refused to start the app because it could
REM  not open one would be a large one.
REM  cmd /v:on, not a plain cmd /c: this is the one place common.bat runs outside an entry script,
REM  and its polling loop and its echo helpers both need delayed expansion. Without /v:on the
REM  poller reads !PX_UP! literally, never sees the app come up, and the browser never opens.
REM  A desktop instance opens through the access handshake (AccessGate), the way its window does.
set "PX_FIRST_PAGE=/"
if /i "%EDITION%"=="desktop" set "PX_FIRST_PAGE=/?access=%PROXYMA_ACCESS_CODE%"
if not defined NO_BROWSER start "proxyma-dev-browser" /min cmd /v:on /c call "%PX_COMMON%" browser_when_up %PORT% "%PX_FIRST_PAGE%"

call "%PX_COMMON%" step "proxyma-%EDITION% is starting"
echo     http://127.0.0.1:%PORT%
if /i "%EDITION%"=="server" echo     sign in as owner@proxyma.test / proxyma-dev (seeded^)
if /i "%EDITION%"=="desktop" echo     access code: %PROXYMA_ACCESS_CODE%
echo     Ctrl+C to stop. ChromaDB is a CHILD python.exe and can outlive it - see the -Reset note
echo     in docs\development.html#run-runtime if a later run cannot delete the data dir.
echo.

java %JAVA_ARGS% -jar "%PX_JAR%"
exit /b %errorlevel%


REM ===============================================================================================
REM  Desktop
REM ===============================================================================================
:desktop_launch
call "%PX_COMMON%" exec_jar proxyma-desktop || exit /b 1
REM  AppPaths appends "proxyma" to LOCALAPPDATA, so this is where the data actually lands.
set "DESKTOP_DATA_DIR=%INSTANCE_ROOT%\proxyma"
if not defined NO_RUNTIME call :copy_runtime "%DESKTOP_DATA_DIR%" || exit /b 1
set "LOCALAPPDATA=%INSTANCE_ROOT%"
REM  A fixed access code for scratch instances, so tools\dev\run-ui.bat's access screen takes the same
REM  one every time. Never an installed app's: those make their own (AccessGate).
set "PROXYMA_ACCESS_CODE=proxyma-dev-access-code"
set "JAVA_ARGS=-Dserver.port=%PORT% -Dproxyma.chromadb.port=%CHROMA_PORT% -Dproxyma.skills-dir=%SKILLS_DIR% -Dproxyma.workflows-dir=%WORKFLOWS_DIR%!EXTRA_JAVA_ARGS!"
exit /b 0

REM  The runtime is not in this repository - it is unpacked into the data directory by the
REM  installer. Copying it from an installed desktop app is what makes ChromaDB work locally with
REM  no Docker at all. Runs BEFORE LOCALAPPDATA is rewritten, which is why the order above matters.
:copy_runtime
set "INSTALLED_RUNTIME=%LOCALAPPDATA%\proxyma\runtime"
set "TARGET_RUNTIME=%~1\runtime"
if exist "%TARGET_RUNTIME%\python.exe" (
    call "%PX_COMMON%" ok "Bundled runtime already present in the scratch instance."
    exit /b 0
)
if not exist "%INSTALLED_RUNTIME%\python.exe" (
    REM  Said at length, because this is the one remaining way to end up with an instance whose
    REM  search silently returns nothing, and because the symptom points somewhere else entirely:
    REM  the log's first complaint is "ChromaApi must not be null", which reads as a bug in the
    REM  vector store rather than as a missing interpreter.
    call "%PX_COMMON%" warn "No installed Proxyma runtime at %INSTALLED_RUNTIME%."
    call "%PX_COMMON%" warn "ChromaDB will NOT start in this instance. The app runs, and every search"
    call "%PX_COMMON%" warn "returns nothing - the log says ChromaApi must not be null, which is this."
    call "%PX_COMMON%" warn "Install the desktop app once to get a runtime to copy, or pass -NoRuntime."
    exit /b 0
)
call "%PX_COMMON%" step "Copying the bundled Python/ChromaDB runtime (this takes a minute)"
if not exist "%TARGET_RUNTIME%" mkdir "%TARGET_RUNTIME%"
REM  Exit codes 0-7 are success for robocopy; 8 and above means something was actually not copied.
robocopy "%INSTALLED_RUNTIME%" "%TARGET_RUNTIME%" /E /NP /NFL /NDL /NJH /NJS >nul
if %errorlevel% GEQ 8 (
    call "%PX_COMMON%" err "robocopy reported failures - the runtime copy is incomplete."
    call "%PX_COMMON%" err "This is usually MAX_PATH: use a shorter -DataRoot than %DATA_ROOT%."
    exit /b 1
)
if not exist "%TARGET_RUNTIME%\python.exe" (
    call "%PX_COMMON%" err "Runtime copied but python.exe is not there - treat the copy as failed."
    exit /b 1
)
call "%PX_COMMON%" ok "Runtime copied to %TARGET_RUNTIME%"
exit /b 0


REM ===============================================================================================
REM  Server
REM ===============================================================================================
:server_launch
call "%PX_COMMON%" exec_jar proxyma-server || exit /b 1

if not defined DB_URL set "DB_URL=%PROXYMA_DB_URL%"
if not defined DB_URL set "DB_URL=jdbc:postgresql://127.0.0.1:55432/proxyma"
if not defined DB_USER set "DB_USER=%PROXYMA_DB_USER%"
if not defined DB_USER set "DB_USER=proxyma"
if not defined DB_PASSWORD set "DB_PASSWORD=%PROXYMA_DB_PASSWORD%"
if not defined DB_PASSWORD set "DB_PASSWORD=proxyma"

if defined START_DB call :start_db || exit /b 1

REM  JwtTokenService does not generate these when they are missing - it throws at startup and the
REM  edition never boots.
set "KEYS_DIR=%INSTANCE_ROOT%\keys"
call "%PX_COMMON%" step "JWT signing keys"
java "%~dp0GenerateJwtKeys.java" "%KEYS_DIR%"
if not "%errorlevel%"=="0" (
    call "%PX_COMMON%" err "Could not generate the JWT key pair."
    exit /b 1
)

REM  Every one of these defaults to a path under /data, which is the container layout and does not
REM  exist on Windows. Left unset the app fails on the first write, at a different moment for each.
set "SPRING_PROFILES_ACTIVE=server"
set "PROXYMA_DB_URL=%DB_URL%"
set "PROXYMA_DB_USER=%DB_USER%"
set "PROXYMA_DB_PASSWORD=%DB_PASSWORD%"
set "PROXYMA_SERVER_DATA_DIR=%INSTANCE_ROOT%"
set "PROXYMA_STORAGE_ROOT=%INSTANCE_ROOT%"
set "PROXYMA_CONVERSATIONS_DIR=%INSTANCE_ROOT%"
set "PROXYMA_EVALUATION_MEDIA_DIR=%INSTANCE_ROOT%\evaluation-media"
set "PROXYMA_SKILLS_DIR=%SKILLS_DIR%"
set "PROXYMA_SYSTEM_PROMPT_FILE=%INSTANCE_ROOT%\system-prompt-override.md"
set "PROXYMA_JWT_PRIVATE_KEY_FILE=%KEYS_DIR%\server-jwt-private.pem"
set "PROXYMA_JWT_PUBLIC_KEY_FILE=%KEYS_DIR%\server-jwt-public.pem"
REM  The dev frontend server, so run-ui.bat can talk to this instance with credentials.
set "PROXYMA_CORS_ALLOWED_ORIGINS=http://localhost:5173"
REM  Nothing local should ship telemetry to the production ingest. Empty is a supported value
REM  meaning "send nothing anywhere".
set "PROXYMA_TELEMETRY_SERVER_URL="
REM  Nor should every fresh dev database ask the production license server for an Enterprise trial -
REM  each one is a grant on record there. Pass "-Dproxyma.license.trial.enabled=true" to try it.
set "PROXYMA_LICENSE_TRIAL_ENABLED=false"

REM  Server mode never touches ChromaDB - PgVectorStoreService is @Primary under @Profile("server")
REM  - so no ChromaDB port here, deliberately.
set "JAVA_ARGS=-Dserver.port=%PORT% -Dproxyma.workflows-dir=%WORKFLOWS_DIR% -Dspring.profiles.active=server!EXTRA_JAVA_ARGS!"
exit /b 0

:start_db
where docker >nul 2>&1
if not "%errorlevel%"=="0" (
    call "%PX_COMMON%" err "Docker is not installed - cannot start a database with -StartDb."
    call "%PX_COMMON%" err "Either install Docker Desktop, or point -DbUrl at a PostgreSQL you already run."
    exit /b 1
)
set "PG_NAME=proxyma-dev-pg"
set "PG_EXISTS="
for /f "delims=" %%N in ('docker ps -a --filter "name=^%PG_NAME%$" --format "{{.Names}}" 2^>nul') do set "PG_EXISTS=%%N"
if "%PG_EXISTS%"=="%PG_NAME%" (
    call "%PX_COMMON%" step "Starting existing container %PG_NAME%"
    docker start %PG_NAME% >nul
) else (
    REM  The app creates the `vector` extension and the vector_documents table itself in
    REM  @PostConstruct, so a stock pgvector image needs no init script: a server deployment that
    REM  STARTED has a working vector store.
    call "%PX_COMMON%" step "Creating container %PG_NAME% (pgvector/pgvector:pg16) on port 55432"
    docker run -d --name %PG_NAME% -e POSTGRES_USER=%DB_USER% -e POSTGRES_PASSWORD=%DB_PASSWORD% -e POSTGRES_DB=proxyma -p 55432:5432 pgvector/pgvector:pg16 >nul
)
if not "%errorlevel%"=="0" (
    call "%PX_COMMON%" err "Failed to start the database container."
    exit /b 1
)
call "%PX_COMMON%" step "Waiting for PostgreSQL"
set "PG_READY="
for /l %%I in (1,1,60) do (
    if not defined PG_READY (
        docker exec %PG_NAME% pg_isready -U %DB_USER% -h 127.0.0.1 >nul 2>&1
        if not errorlevel 1 ( set "PG_READY=1" ) else ( ping -n 3 127.0.0.1 >nul )
    )
)
if not defined PG_READY (
    call "%PX_COMMON%" err "PostgreSQL did not become ready."
    exit /b 1
)
call "%PX_COMMON%" ok "PostgreSQL ready."
exit /b 0


REM ===============================================================================================
REM  Seeding
REM ===============================================================================================

REM  The schema is whatever ddl-auto last produced, so it has to exist before any INSERT. Boot the
REM  app once, wait until it serves, then stop it - H2's MVStore and PostgreSQL are both crash-safe
REM  and the DDL is committed by then, so a hard stop here is safe.
REM
REM  Started in its own titled window so it can be killed by title: batch has no PID handle, and
REM  /T takes the java child with the cmd that launched it.
:schema_boot
call "%PX_COMMON%" step "Creating the schema (one throwaway boot)"
start "proxyma-dev-schema-boot" /min cmd /c "java %JAVA_ARGS% -jar ""%PX_JAR%"" > ""%INSTANCE_ROOT%\schema-boot.log"" 2>&1"
call "%PX_COMMON%" wait_for_app %PORT% 180
if not "%PX_UP%"=="1" (
    call "%PX_COMMON%" err "The app did not start within 180s. See %INSTANCE_ROOT%\schema-boot.log"
    taskkill /F /T /FI "WINDOWTITLE eq proxyma-dev-schema-boot*" >nul 2>&1
    exit /b 1
)
call "%PX_COMMON%" ok "Schema created."
taskkill /F /T /FI "WINDOWTITLE eq proxyma-dev-schema-boot*" >nul 2>&1
REM  H2 releases its file lock on process exit, but the OS can take a moment to reflect that.
ping -n 5 127.0.0.1 >nul
exit /b 0

REM  The folder connectors in the seed point at these files, and they have to actually be there:
REM  SyncOrchestratorService re-walks a due connector every 60s and reconciles it against the real
REM  filesystem, so a fixture pointing at folders that do not exist is rewritten within seconds of
REM  the app starting. See the note at the bottom of seed\desktop-local.sql.
:seed
if /i "%EDITION%"=="server" goto :seed_server

set "SAMPLE_DOCS=%DATA_ROOT%\sample-docs"
call "%PX_COMMON%" step "Placing the sample documents in %SAMPLE_DOCS%"
if not exist "%SAMPLE_DOCS%" mkdir "%SAMPLE_DOCS%"
robocopy "%~dp0seed\sample-docs" "%SAMPLE_DOCS%" /E /NP /NFL /NDL /NJH /NJS >nul
if %errorlevel% GEQ 8 (
    call "%PX_COMMON%" err "Could not copy the sample documents."
    exit /b 1
)

REM  Two H2 files, because the desktop edition splits its entities across ten datasources and these
REM  are the two that hold anything worth looking at: `local` (@Primary) owns the connectors and the
REM  documents, `chat` owns the conversations and their messages.
REM
REM  Forward slashes throughout: H2 accepts either on Windows, and a mixed-separator URL is the kind
REM  of thing that reads as a typo in a log line.
set "H2_BASE=%DESKTOP_DATA_DIR:\=/%"
set "RENDERED=%INSTANCE_ROOT%\rendered-desktop-local.sql"
java "%~dp0RenderSeed.java" "%~dp0seed\desktop-local.sql" "%RENDERED%" "@@SAMPLE_DOCS@@=%SAMPLE_DOCS%"
if not "%errorlevel%"=="0" (
    call "%PX_COMMON%" err "Could not render the desktop seed script."
    exit /b 1
)
call "%PX_COMMON%" step "Seeding local_folders (connectors, documents)"
call "%PX_COMMON%" run_sql "jdbc:h2:file:%H2_BASE%/local_folders;LOCK_TIMEOUT=10000;AUTO_SERVER=TRUE" "sa" "" "%RENDERED%" || exit /b 1
call "%PX_COMMON%" step "Seeding chat_history (conversations, messages)"
call "%PX_COMMON%" run_sql "jdbc:h2:file:%H2_BASE%/chat_history;LOCK_TIMEOUT=10000;AUTO_SERVER=TRUE" "sa" "" "%~dp0seed\desktop-chat.sql" || exit /b 1
call "%PX_COMMON%" ok "Pseudo data injected."
exit /b 0

REM  Server-mode uploads are owned by the app under {storageRoot}\users\{userId}\uploads\, and the
REM  seeded connectors name the two owners below. Same reasoning as the desktop copy: the
REM  orchestrator reconciles against what is really there.
:seed_server
set "OWNER_UPLOADS=%INSTANCE_ROOT%\users\de900001-0000-4000-8000-000000000001\uploads"
set "ADMIN_UPLOADS=%INSTANCE_ROOT%\users\de900002-0000-4000-8000-000000000002\uploads"
if not exist "%OWNER_UPLOADS%" mkdir "%OWNER_UPLOADS%"
if not exist "%ADMIN_UPLOADS%" mkdir "%ADMIN_UPLOADS%"
robocopy "%~dp0seed\sample-docs\engineering-handbook" "%OWNER_UPLOADS%\engineering-handbook" /E /NP /NFL /NDL /NJH /NJS >nul
robocopy "%~dp0seed\sample-docs\customer-contracts" "%ADMIN_UPLOADS%\customer-contracts" /E /NP /NFL /NDL /NJH /NJS >nul
call "%PX_COMMON%" step "Seeding PostgreSQL (users, connectors, documents, conversations, messages)"
call "%PX_COMMON%" driver_jar "org\postgresql\postgresql" "postgresql-*.jar" || exit /b 1
call "%PX_COMMON%" run_sql "%DB_URL%" "%DB_USER%" "%DB_PASSWORD%" "%~dp0seed\server.sql" "%PX_DRIVER_JAR%" || exit /b 1
call "%PX_COMMON%" ok "Pseudo data injected."
exit /b 0
