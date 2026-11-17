; winapk.iss -- the Win.apk installer (Inno Setup 6).
;
;   iscc /DSourceDir=<staged ARM64 folder with jre> /DAppVersion=0.1.0-preview windows\package\winapk.iss
;
; Makes Win.apk-Setup-arm64.exe, for Windows on Arm only.
;
; Installs per user (no administrator rights) to %LOCALAPPDATA%\Programs\Win.apk,
; adds Win.apk to the Start menu (and the desktop if asked), opens .apk files
; with it, and uninstalls cleanly. Apps and their data live in
; %LOCALAPPDATA%\Win.apk and stay when Win.apk is uninstalled.

#ifndef SourceDir
  #error Pass /DSourceDir=<the staged Win.apk folder>
#endif
#ifndef AppVersion
  #define AppVersion "0.1.0"
#endif
; The numeric part (0.2.0 of 0.2.0-preview), for the file's version resource.
#ifndef AppNumber
  #define AppNumber "0.1.0"
#endif

[Setup]
AppId={{C20601C8-B46D-4558-AFF2-CD1EA0D4A473}
AppName=Win.apk
AppVersion={#AppVersion}
AppVerName=Win.apk {#AppVersion}
AppPublisher=Win.apk
VersionInfoVersion={#AppNumber}
DefaultDirName={localappdata}\Programs\Win.apk
DisableProgramGroupPage=yes
DisableDirPage=yes
PrivilegesRequired=lowest
ArchitecturesAllowed=arm64
ArchitecturesInstallIn64BitMode=arm64
MinVersion=10.0.22000
OutputBaseFilename=Win.apk-Setup-arm64
SetupIconFile=..\launcher\winapk.ico
UninstallDisplayIcon={app}\Win.apk.exe
UninstallDisplayName=Win.apk
WizardStyle=modern
Compression=lzma2/max
SolidCompression=yes
ChangesAssociations=yes
CloseApplications=yes

[Tasks]
Name: desktopicon; Description: "Put Win.apk on the desktop"

[InstallDelete]
; The Java runtime is replaced whole: files an older one had and this one has
; not (other modules' DLLs, a second CDS archive) would otherwise stay.
Type: filesandordirs; Name: "{app}\jre"

[Files]
Source: "{#SourceDir}\*"; DestDir: "{app}"; Flags: recursesubdirs ignoreversion createallsubdirs

[Icons]
Name: "{userprograms}\Win.apk"; Filename: "{app}\Win.apk.exe"; Comment: "Android apps on Windows"
Name: "{userdesktop}\Win.apk"; Filename: "{app}\Win.apk.exe"; Comment: "Android apps on Windows"; Tasks: desktopicon

[Registry]
Root: HKCU; Subkey: "Software\Classes\.apk"; ValueType: string; ValueData: "WinApk.Package"; Flags: uninsdeletevalue
Root: HKCU; Subkey: "Software\Classes\WinApk.Package"; ValueType: string; ValueData: "Android app"; Flags: uninsdeletekey
Root: HKCU; Subkey: "Software\Classes\WinApk.Package\DefaultIcon"; ValueType: string; ValueData: """{app}\Win.apk.exe"",0"
Root: HKCU; Subkey: "Software\Classes\WinApk.Package\shell\open\command"; ValueType: string; ValueData: """{app}\Win.apk.exe"" ""%1"""

[UninstallDelete]
; The apps' shortcuts the library made (Start > Win.apk Apps).
Type: filesandordirs; Name: "{userprograms}\Win.apk Apps"

[Run]
Filename: "{app}\Win.apk.exe"; Description: "Open Win.apk"; Flags: nowait postinstall skipifsilent
