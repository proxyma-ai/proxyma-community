@echo off
REM ===============================================================================================
REM  common.bat - shared subroutines for build.bat, run.bat and run-ui.bat.
REM
REM  NOT run directly. Callers invoke one subroutine at a time:
REM
REM      call "%~dp0common.bat" init_toolchain
REM      call "%~dp0common.bat" project_version   &&  echo !PX_VERSION!
REM
REM  TWO RULES THIS FILE DEPENDS ON, and both are the caller's job:
REM
REM    1. NO `setlocal` IN HERE. `call` does not create a new variable scope, so everything set
REM       below lands in the caller's environment - which is the whole point: init_toolchain sets
REM       JAVA_HOME and PATH, project_version sets PX_VERSION. A `setlocal` here would throw all
REM       of that away at `exit /b` and every caller would silently see nothing.
REM
REM    2. The CALLER enables delayed expansion (`setlocal EnableDelayedExpansion`). The loops
REM       below read variables they set in the same block, which plain %VAR% expansion cannot do.
REM
REM  Every output variable is prefixed PX_ so a caller can see at a glance what came from here.
REM
REM  These were four PowerShell scripts until 2026-08-31. Batch is more verbose and cannot parse
REM  XML or JSON, but it needs no execution policy, no profile and no -File wrapper - the thing
REM  that made a .ps1 unrunnable on a machine that had never been prepared for it.
REM ===============================================================================================

if "%~1"=="" (
    echo [ERROR] common.bat is a library. Call one of its subroutines, e.g. init_toolchain.
    exit /b 2
)
call :%*
exit /b %errorlevel%


REM -- Output ------------------------------------------------------------------------------------
REM  Same three shapes the PowerShell used, so existing notes and screenshots still read: a step
REM  heading, an indented fact, an indented warning, and an error that names itself.
REM  A message arrives either as bare words or as one quoted string - callers quote whenever the
REM  text contains a comma, a parenthesis or an equals sign, which is most of them. The quotes are
REM  stripped here rather than printed, and the echo goes through a delayed-expansion variable so
REM  that a message containing ) or & cannot be parsed as syntax.
:step
call :msg %*
echo.
echo ==^> !PX_MSG!
exit /b 0

:ok
call :msg %*
echo     !PX_MSG!
exit /b 0

REM  The prefix is [warn] and not [!]: with delayed expansion on, the exclamation opens a variable
REM  reference and cmd eats the rest of the line up to the next one - printing "[PX_MSG" and
REM  swallowing the message this exists to deliver.
:warn
call :msg %*
echo     [warn] !PX_MSG!
exit /b 0

:err
call :msg %*
echo [ERROR] !PX_MSG!
exit /b 0

:msg
set "PX_MSG=%*"
if defined PX_MSG set "PX_MSG=!PX_MSG:"=!"
exit /b 0


REM -- Repo root ---------------------------------------------------------------------------------
REM  tools\dev\common.bat -> tools\dev -> tools -> repo root. Sets PX_ROOT with no trailing slash.
:repo_root
for %%I in ("%~dp0..\..") do set "PX_ROOT=%%~fI"
exit /b 0


REM -- Toolchain ---------------------------------------------------------------------------------
REM  Nothing this build needs is on PATH by default on the maintainer's machine, which is why
REM  earlier hand-offs claimed the project could not be built locally. It can - the tools are all
REM  present, just unregistered. Resolved here rather than asking every developer to set three
REM  variables before every command.
:init_toolchain
call :repo_root

REM  Java. A JRE 8 has been on PATH here while a JDK 21 sat unregistered, so an existing JAVA_HOME
REM  is trusted only if it actually contains a javac - a JRE has none, and the build then fails
REM  much later with a message about a missing compiler rather than a missing JDK.
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javac.exe" goto :java_ok
set "JAVA_HOME="
REM  Highest jdk-2x wins. `for /d` yields names in directory order, so the comparison keeps the
REM  greatest rather than trusting the last one listed.
for /d %%D in ("C:\Program Files\Java\jdk-2*") do (
    if exist "%%~fD\bin\javac.exe" (
        if not defined JAVA_HOME (
            set "JAVA_HOME=%%~fD"
        ) else (
            if "%%~nxD" GTR "!PX_JDK_NAME!" set "JAVA_HOME=%%~fD"
        )
        set "PX_JDK_NAME=%%~nxD"
    )
)
if not defined JAVA_HOME (
    call :err No JDK 21+ found.
    call :err Set JAVA_HOME to a JDK - it must contain bin\javac.exe, so a JRE will not do.
    exit /b 1
)
:java_ok
set "PATH=%JAVA_HOME%\bin;%PATH%"

REM  Maven. A hand-placed unzip under %USERPROFILE%\tools, with no registry entry and nothing on
REM  PATH. Skipped entirely when mvn already resolves.
where mvn >nul 2>&1
if %errorlevel%==0 goto :maven_ok
set "PX_MAVEN_HOME="
for /d %%D in ("%USERPROFILE%\tools\apache-maven-*") do (
    if exist "%%~fD\bin\mvn.cmd" (
        if not defined PX_MAVEN_HOME (
            set "PX_MAVEN_HOME=%%~fD"
        ) else (
            if "%%~nxD" GTR "!PX_MAVEN_NAME!" set "PX_MAVEN_HOME=%%~fD"
        )
        set "PX_MAVEN_NAME=%%~nxD"
    )
)
if not defined PX_MAVEN_HOME (
    call :err Maven not found.
    call :err Put an apache-maven-* unzip under "%USERPROFILE%\tools", or add mvn to PATH.
    exit /b 1
)
set "PATH=%PX_MAVEN_HOME%\bin;%PATH%"
:maven_ok

REM  Node is not installed system-wide either: frontend-maven-plugin fetches a private copy under
REM  the frontend directory on the first backend build that includes the SPA. Absent before that
REM  first build, which is not an error here - only run-ui.bat actually needs it.
if exist "%PX_ROOT%\apps\app\frontend\node" set "PATH=%PX_ROOT%\apps\app\frontend\node;%PATH%"

call :ok JAVA_HOME = %JAVA_HOME%
exit /b 0


REM -- Project version ---------------------------------------------------------------------------
REM  The pom is the ONE place a version is written - tools\release\windows.bat reads it from
REM  there too - so read it rather than keeping a copy here that goes stale on every release.
REM
REM  The FIRST <version> in that file belongs to the spring-boot parent (3.5.6), not to us, so
REM  the line number of </parent> is found first and only later <version> lines are considered.
REM  That is what the PowerShell got for free from an XML parser and is the one place batch has
REM  to be told how a pom is shaped.
:project_version
call :repo_root
set "PX_POM=%PX_ROOT%\apps\app\backend\pom.xml"
set "PX_VERSION="
set "PX_PARENT_END=0"
for /f "tokens=1 delims=:" %%N in ('findstr /n /c:"</parent>" "%PX_POM%"') do (
    if "!PX_PARENT_END!"=="0" set "PX_PARENT_END=%%N"
)
for /f "tokens=1,* delims=:" %%N in ('findstr /n /c:"<version>" "%PX_POM%"') do (
    if not defined PX_VERSION if %%N GTR !PX_PARENT_END! (
        for /f "tokens=2 delims=<> " %%V in ("%%O") do set "PX_VERSION=%%V"
    )
)
if not defined PX_VERSION (
    call :err Could not read the project version from "%PX_POM%".
    exit /b 1
)
exit /b 0


REM -- Built jars --------------------------------------------------------------------------------
REM  "*-exec.jar is the runnable one" - the unclassified jar is thin and `java -jar` on it fails
REM  with "no main manifest attribute". Asserting the file exists is deliberate: an unmatched glob
REM  silently becomes an empty string and the failure then surfaces as a confusing java error.
REM  Sets PX_JAR.
:exec_jar
call :project_version || exit /b 1
set "PX_JAR=%PX_ROOT%\apps\app\backend\%~1\target\%~1-%PX_VERSION%-exec.jar"
REM  The Enterprise assembly is in ee\ since 2026-10-15 (docs\architecture.html#oss-boundary).
if /i "%~1"=="proxyma-server" set "PX_JAR=%PX_ROOT%\ee\backend\%~1\target\%~1-%PX_VERSION%-exec.jar"
if not exist "%PX_JAR%" (
    call :err Not built: %PX_JAR%
    if /i "%~1"=="proxyma-server" (
        call :err Run "tools\dev\build.bat server" first.
    ) else (
        call :err Run "tools\dev\build.bat desktop" first.
    )
    exit /b 1
)
exit /b 0


REM -- node_modules recovery ---------------------------------------------------------------------
REM  `npm ci` DELETES node_modules before it installs. On Windows that deletion fails outright if
REM  any process still holds a handle under it - a hard-killed vite or esbuild, an editor indexing
REM  the tree, a virus scanner mid-file - and npm gives up part-way with libuv's ENOTEMPTY,
REM  surfacing through frontend-maven-plugin as:
REM
REM      Failed to run task: 'npm ci' failed. ... Process exited with an error: -4051
REM
REM  which names neither node_modules nor the reason. What is left is a directory with a fraction
REM  of the packages and NO .package-lock.json, because npm writes that last. That pair - the
REM  directory exists, its manifest does not - is a reliable marker for "half-deleted", and is not
REM  something a healthy install can look like. Detecting it is what turns an hour into a second.
:clear_wrecked_node_modules
set "PX_MODULES=%~1\node_modules"
if not exist "%PX_MODULES%" exit /b 0
if exist "%PX_MODULES%\.package-lock.json" exit /b 0
call :warn node_modules has no .package-lock.json - an npm ci was interrupted part-way.
call :warn Removing %PX_MODULES% so the next install starts from nothing.
rmdir /s /q "%PX_MODULES%" 2>nul
if exist "%PX_MODULES%" (
    call :err Could not remove %PX_MODULES% - something still holds a handle under it.
    call :err Close the editor and any dev server, then try again.
    exit /b 1
)
exit /b 0

REM  `npm ci`, with the one retry that matters: a first failure is most often the deletion above
REM  losing a race with a handle that has since gone. A second failure is a real problem and is
REM  reported rather than looped over.
:npm_ci
call :clear_wrecked_node_modules "%~1" || exit /b 1
pushd "%~1"
call npm ci
if %errorlevel%==0 (
    popd
    exit /b 0
)
call :warn npm ci failed. Clearing node_modules and retrying once.
if exist "node_modules" rmdir /s /q "node_modules" 2>nul
if exist "node_modules" (
    popd
    call :err Could not remove node_modules. Close the editor and any running dev server, then retry.
    exit /b 1
)
call npm ci
set "PX_NPM_CODE=%errorlevel%"
popd
if not "%PX_NPM_CODE%"=="0" (
    call :err npm ci failed again. This is not the interrupted-install case.
    exit /b 1
)
exit /b 0


REM -- JDBC drivers ------------------------------------------------------------------------------
REM  Both drivers are already in the local Maven repository after a build - neither is downloaded
REM  separately and neither is checked in. Sets PX_DRIVER_JAR.
REM    %1 = path under .m2\repository    %2 = filename pattern
:driver_jar
set "PX_DRIVER_DIR=%USERPROFILE%\.m2\repository\%~1"
set "PX_DRIVER_JAR="
REM  Parentheses are ESCAPED in the messages below, and quoting them would not be enough: cmd
REM  parses a whole parenthesized block before running any of it, and a bare ) inside one closes
REM  the block early. The symptom is ". was unexpected at this time" from a line that looks fine.
if not exist "%PX_DRIVER_DIR%" (
    call :err %~2 is not in the local Maven repository - %PX_DRIVER_DIR%
    call :err Run build.bat once, the build downloads it.
    exit /b 1
)
for /f "delims=" %%F in ('dir /b /s /a-d "%PX_DRIVER_DIR%\%~2" 2^>nul') do (
    echo %%~nxF | findstr /i /c:"sources" /c:"javadoc" >nul || set "PX_DRIVER_JAR=%%~fF"
)
if not defined PX_DRIVER_JAR (
    call :err No %~2 under %PX_DRIVER_DIR%.
    exit /b 1
)
exit /b 0

:h2_jar
call :driver_jar "com\h2database\h2" "h2-*.jar" || exit /b 1
set "PX_H2_JAR=%PX_DRIVER_JAR%"
exit /b 0


REM -- Waiting for the app -----------------------------------------------------------------------
REM  Polls the one endpoint that answers before sign-in in both editions. ANY http code means the
REM  app is listening and serving - server mode answers 401 there before sign-in, and that is a
REM  perfectly good moment to stop waiting. Only "no answer at all" is still-starting.
REM
REM  curl.exe ships with Windows 10 1803 and later; there is no PowerShell here on purpose.
REM  Sets PX_UP=1 on success, PX_UP=0 on timeout - the caller decides whether that is fatal.
REM    %1 = port    %2 = timeout in seconds
:wait_for_app
set "PX_UP=0"
set /a PX_TRIES=%~2 / 2
if %PX_TRIES% LSS 1 set "PX_TRIES=1"
for /l %%I in (1,1,%PX_TRIES%) do (
    if "!PX_UP!"=="0" (
        for /f "delims=" %%C in ('curl.exe -s -o nul -w "%%{http_code}" --max-time 5 "http://127.0.0.1:%~1/api/v1/config" 2^>nul') do set "PX_CODE=%%C"
        if not "!PX_CODE!"=="000" if not "!PX_CODE!"=="" set "PX_UP=1"
        REM  ping rather than `timeout`: timeout refuses to run when stdin is redirected, which
        REM  is exactly what happens when this script is driven by anything but a console.
        if "!PX_UP!"=="0" ping -n 3 127.0.0.1 >nul
    )
)
exit /b 0

REM  Opens the browser once the app answers. Started detached by run.bat, because java runs in the
REM  FOREGROUND there and the script has no "after startup" to hook.
REM
REM  THE PORT IS THE PROBLEM THIS SOLVES. run.bat deliberately avoids 4246 so a scratch instance
REM  cannot collide with an installed app - but 4246 is the port everybody knows, so the natural
REM  thing to do is open it and get ERR_CONNECTION_REFUSED from a run that is working perfectly.
REM  Printing the URL was not enough: it is one line, and Spring writes several hundred more over
REM  the top of it while the app boots.
REM    %1 = port    %2 = the first page, "/" by default (a desktop instance's access handshake)
:browser_when_up
call :wait_for_app %~1 360
set "PX_PAGE=%~2"
if not defined PX_PAGE set "PX_PAGE=/"
if "%PX_UP%"=="1" start "" "http://127.0.0.1:%~1%PX_PAGE%"
exit /b 0


REM -- SQL -----------------------------------------------------------------------------------------
REM  H2's RunScript is a general JDBC batch client, not an H2-only one: given -driver and the right
REM  jar on the classpath it runs a script against PostgreSQL just as happily. That is why there is
REM  one subroutine here and not two, and why the server path needs neither psql nor a shell inside
REM  the database container.
REM    %1 url   %2 user   %3 password (may be "")   %4 script   %5 extra jar (optional)
:run_sql
if not exist "%~4" (
    call :err Seed script not found: %~4
    exit /b 1
)
call :h2_jar || exit /b 1
set "PX_CP=%PX_H2_JAR%"
if not "%~5"=="" set "PX_CP=%PX_CP%;%~5"
set "PX_SQL_ARGS=-url "%~1" -user "%~2" -script "%~4""
REM  An empty -password argument is dropped on its way to the JVM rather than passed as "", and
REM  RunScript then reads the next token as the password. Omitting it entirely is what actually
REM  means "no password" - which is every desktop H2 file.
if not "%~3"=="" set "PX_SQL_ARGS=%PX_SQL_ARGS% -password "%~3""
echo %~1 | findstr /b /c:"jdbc:postgresql:" >nul && set "PX_SQL_ARGS=%PX_SQL_ARGS% -driver org.postgresql.Driver"
java -cp "%PX_CP%" org.h2.tools.RunScript %PX_SQL_ARGS%
if not "%errorlevel%"=="0" (
    call :err RunScript failed against %~1  - script: %~4
    exit /b 1
)
exit /b 0
