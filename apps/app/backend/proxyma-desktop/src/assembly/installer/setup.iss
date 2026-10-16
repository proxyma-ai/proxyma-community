; Proxyma @project.version@ - Inno Setup 6 Installer Script
;
; THE INSTALLER IS SMALL, since 2026-09-26: proxyma.exe (the launcher), its icon and a Java runtime,
; nothing else. The application, the Python runtime, the headless browser, Tesseract and the
; application window are PARTS that proxyma.exe downloads on its first start, from the release this
; installer belongs to, into %LOCALAPPDATA%\proxyma\parts - and updates part by part afterwards.
; See ProxymaLauncher and PartsStore.
;
; Built by the Maven `windows-desktop` profile (proxyma-desktop/pom.xml, build-windows-installer),
; which stages target/installer-staging and runs iscc there. @project.version@ is filled in by the
; resources plugin before that.

#define MyAppName      "Proxyma"
#define MyAppVersion   "@project.version@"
#define MyAppPublisher "proxyma.ai"
[Setup]
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppVerName={#MyAppName} {#MyAppVersion}
AppPublisher={#MyAppPublisher}
AppComments=AI assistant that searches your own documents, Jira, Confluence and Bitbucket
; Stable GUID - never change this; changing it breaks upgrade detection in Add/Remove Programs.
AppId={{B4E3F2C1-A5D6-4789-B012-C34D56E78F90}}

; Install to %LOCALAPPDATA%\Programs\Proxyma - no UAC required
DefaultDirName={localappdata}\Programs\Proxyma
DefaultGroupName={#MyAppName}
DisableProgramGroupPage=yes

; Source files are relative to the directory one level above this script (the staging root).
SourceDir=..

; The license, SHOWN and never accepted: Proxyma Community is free software under the GNU Affero
; General Public License v3 (AGPL-3.0-only), which needs no acceptance to receive or run a copy
; (sections 9-10). So it is an information page with a Next button, not Inno Setup's LicenseFile
; page with "I accept" - which is what the EULA used until 2026-10-15; the EULA now covers Proxyma
; Enterprise alone. The build copies the repository's LICENSE here (proxyma-desktop/pom.xml).
InfoBeforeFile=LICENSE.txt

; Output location and filename
OutputDir=.
OutputBaseFilename=proxyma-x64-v{#MyAppVersion}

Compression=lzma2/ultra64
SolidCompression=yes

; Minimum Windows 10 (build 10240)
MinVersion=10.0.10240

; No admin rights needed - per-user install
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog

UninstallDisplayName={#MyAppName} {#MyAppVersion}
UninstallDisplayIcon={app}\proxyma.ico
SetupIconFile=proxyma.ico

; Allow the user to specify a non-default install dir
DisableDirPage=no

; Restart Manager off. Left at its default (yes), Setup and the uninstaller both open a
; "close these applications" prompt and then FAIL to close Proxyma: Restart Manager asks an
; application to shut down gracefully through its window messages, and a Launch4j-wrapped JVM
; does not answer. Nothing is lost by disabling it: PrepareToInstall force-kills the process tree
; before extraction, and [UninstallRun] does the same before files are removed.
CloseApplications=no

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "Create a &desktop shortcut"; GroupDescription: "Additional icons:"
Name: "startuprun";  Description: "Start Proxyma &automatically when I log in"; GroupDescription: "Startup:"

; What installers before the small one put in {app}, all of which is now a downloaded part in the
; data directory. Removed so an install over an old one leaves nothing dead behind. The JRE goes
; too and is written fresh below, so a new runtime never merges into an old one.
[InstallDelete]
Type: filesandordirs; Name: "{app}\bin"
Type: filesandordirs; Name: "{app}\window"
Type: filesandordirs; Name: "{app}\tesseract"
Type: filesandordirs; Name: "{app}\setup"
Type: filesandordirs; Name: "{app}\python"
Type: filesandordirs; Name: "{app}\jre"

[Files]
; The launcher, which downloads everything else on its first start.
Source: "proxyma.exe"; DestDir: "{app}"; Flags: ignoreversion
; Application icon - used by shortcuts and the uninstaller.
Source: "proxyma.ico"; DestDir: "{app}"; Flags: ignoreversion
; The open-source notices - every third-party component, its license, and the source offer for
; the GPL and LGPL ones. The EULA points here by name, so the name and the folder are fixed. The
; build joins it (apps/app/third-party/notices.xml) and stages it beside proxyma.exe.
Source: "THIRD-PARTY-NOTICES.txt"; DestDir: "{app}"; Flags: ignoreversion
; The program's own license and copyright notice, beside it.
Source: "LICENSE.txt"; DestDir: "{app}"; Flags: ignoreversion
Source: "NOTICE.txt"; DestDir: "{app}"; Flags: ignoreversion
; The Java runtime proxyma.exe runs on (Launch4j looks for jre\ beside it), and the application
; after it. Unpacked by the build, so the uninstaller knows every file.
Source: "jre\*"; DestDir: "{app}\jre"; Flags: recursesubdirs ignoreversion

; [Icons] section intentionally omitted - all shortcuts are created programmatically at the end of
; CurStepChanged(ssPostInstall) so they only exist once every file is in place.

[Run]
; Offered, and ticked: the first start downloads the rest of Proxyma, and starting it now gets that
; under way while the user is still looking at the screen.
Filename: "{app}\proxyma.exe"; \
    WorkingDir: "{app}"; \
    Description: "Launch Proxyma now"; \
    Flags: postinstall nowait skipifsilent

[UninstallRun]
; Kill the full Proxyma process tree before removing files.
; /T kills proxyma.exe and ALL its descendants (child JVM + ChromaDB + the window) in one shot.
Filename: "{sys}\taskkill.exe"; \
    Parameters: "/T /F /IM proxyma.exe"; \
    Flags: runhidden; \
    RunOnceId: "KillProxymaTree"
; Fallback: kill any ChromaDB left running if the watchdog was already dead (orphaned process).
Filename: "{sys}\taskkill.exe"; \
    Parameters: "/F /IM chroma.exe"; \
    Flags: runhidden; \
    RunOnceId: "KillChroma"
; And anything still running out of the install directory or the downloaded parts. taskkill /T
; above only reaches descendants of proxyma.exe, so an orphaned JVM - watchdog gone, app still up -
; survives it. Matched on PATH, not on java.exe, so unrelated Java applications are untouched.
;
; The doubled braces are NOT a typo. Inno expands {constants} in Parameters, so a PowerShell
; script block - Where-Object { ... } - is read as a constant and the compile dies. A literal brace
; is written {{. {app} and {localappdata} stay single, because those really are constants.
Filename: "powershell.exe"; \
    Parameters: "-NoProfile -ExecutionPolicy Bypass -Command ""Get-CimInstance Win32_Process | Where-Object {{ $_.ExecutablePath -like '{app}\*' -or $_.ExecutablePath -like '{localappdata}\proxyma\*' } | ForEach-Object {{ Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }"""; \
    Flags: runhidden; \
    RunOnceId: "KillProxymaByPath"

[UninstallDelete]
; Remove shortcuts created programmatically in [Code].
Type: files;          Name: "{group}\Proxyma.lnk"
Type: files;          Name: "{group}\Uninstall Proxyma.lnk"
Type: files;          Name: "{userdesktop}\Proxyma.lnk"
Type: dirifempty;     Name: "{group}"
; logs\ may exist in old installs (logs now written to %LOCALAPPDATA%\proxyma\logs).
Type: filesandordirs; Name: "{app}\logs"
; Remove the install directory itself once all contents are gone.
Type: dirifempty;     Name: "{app}"

[Code]

// Runs after the wizard pages close but before Setup extracts any [Files] entries. A running
// Proxyma holds proxyma.exe and the JRE open, and extraction fails partway through - so stop it
// first, exactly like [UninstallRun] does for the uninstaller.
function PrepareToInstall(var NeedsRestart: Boolean): String;
var
  ResultCode: Integer;
begin
  Result := '';
  Exec(ExpandConstant('{sys}\taskkill.exe'), '/T /F /IM proxyma.exe', '',
      SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Exec(ExpandConstant('{sys}\taskkill.exe'), '/F /IM chroma.exe', '',
      SW_HIDE, ewWaitUntilTerminated, ResultCode);
  // Then anything still running FROM THE INSTALL DIRECTORY OR THE PARTS, whatever it is called.
  // taskkill /T misses a JVM that outlived its watchdog. Matching on the executable PATH rather
  // than on java.exe keeps unrelated applications on the machine alive.
  Exec('powershell.exe',
      '-NoProfile -ExecutionPolicy Bypass -Command "Get-CimInstance Win32_Process | Where-Object { $_.ExecutablePath -like ''' + ExpandConstant('{app}') + '\*'' -or $_.ExecutablePath -like ''' + ExpandConstant('{localappdata}\proxyma') + '\*'' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }"',
      '', SW_HIDE, ewWaitUntilTerminated, ResultCode);

  // Give the OS a moment to release file handles after the processes exit.
  Sleep(500);
end;

// Create all shortcuts as the very last installation step.
// Uses {userdesktop} (not {commondesktop}) so no admin rights are needed.
procedure CreateShortcuts;
var
  AppDir, ProxymaExe, GroupDir: String;
begin
  AppDir   := ExpandConstant('{app}');
  ProxymaExe  := AppDir + '\proxyma.exe';
  GroupDir := ExpandConstant('{group}');

  ForceDirectories(GroupDir);

  // Start Menu - launch shortcut. DeleteFile first so an upgrade from an older installer
  // (which wrote the all-caps "Proxyma.lnk") gets a fresh directory entry in the new case -
  // NTFS is case-insensitive, so overwriting in place would keep the original casing.
  DeleteFile(GroupDir + '\Proxyma.lnk');
  CreateShellLink(
    GroupDir + '\Proxyma.lnk',
    'Proxyma',
    ProxymaExe, '', AppDir, ProxymaExe, 0, SW_SHOWNORMAL);

  // Start Menu - uninstall shortcut
  DeleteFile(GroupDir + '\Uninstall Proxyma.lnk');
  CreateShellLink(
    GroupDir + '\Uninstall Proxyma.lnk',
    'Uninstall Proxyma',
    ExpandConstant('{uninstallexe}'), '', AppDir, ProxymaExe, 0, SW_SHOWNORMAL);

  // Desktop shortcut (optional task, per-user)
  if WizardIsTaskSelected('desktopicon') then begin
    DeleteFile(ExpandConstant('{userdesktop}') + '\Proxyma.lnk');
    CreateShellLink(
      ExpandConstant('{userdesktop}') + '\Proxyma.lnk',
      'Proxyma', ProxymaExe, '', AppDir, ProxymaExe, 0, SW_SHOWNORMAL);
  end;

  // Start with Windows (optional task) - registry run key
  if WizardIsTaskSelected('startuprun') then
    RegWriteStringValue(
      HKCU,
      'SOFTWARE\Microsoft\Windows\CurrentVersion\Run',
      'Proxyma',
      ProxymaExe);
end;

procedure CurStepChanged(CurStep: TSetupStep);
begin
  if CurStep = ssPostInstall then CreateShortcuts;
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
var
  DataDir:   String;
  MsgResult: Integer;
begin
  if CurUninstallStep = usPostUninstall then begin
    RegDeleteValue(HKCU, 'SOFTWARE\Microsoft\Windows\CurrentVersion\Run', 'Proxyma');
    // Written by installers before the small one; nothing has read it for a long time.
    RegDeleteValue(HKCU, 'Environment', 'PROXYMA_HOME');

    DataDir := ExpandConstant('{localappdata}\proxyma');
    // The downloaded program itself goes whatever the answer below: the parts, the Python runtime
    // built from them and the headless browser hold no user data, and are most of the disk space
    // Proxyma uses. Leaving them would make uninstalling free almost nothing.
    DelTree(DataDir + '\parts', True, True, True);
    DelTree(DataDir + '\runtime', True, True, True);
    DelTree(DataDir + '\browsers', True, True, True);
    DelTree(DataDir + '\update-staging', True, True, True);
    // The launcher's extracted copy of the application and its class archive (ClassDataSharing).
    DelTree(DataDir + '\class-data', True, True, True);

    if DirExists(DataDir) then begin
      MsgResult := MsgBox(
        'Do you also want to delete your Proxyma data and settings?'
        + #13#10#13#10 + '  ' + DataDir
        + #13#10#13#10
        + 'This includes all indexed documents, preferences, and logs.'
        + #13#10 + 'This action cannot be undone.',
        mbConfirmation,
        MB_YESNO or MB_DEFBUTTON2);
      if MsgResult = IDYES then begin
        DelTree(DataDir, True, True, True);
        // The application window's own storage - its preferences, cache and size - which
        // Electron keeps under the ROAMING profile, not beside the data above.
        DelTree(ExpandConstant('{userappdata}\Proxyma'), True, True, True);
      end;
    end;
  end;
end;
