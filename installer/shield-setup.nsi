; Shield – Windows-Installer
; Installiert ein kleines Werkzeug (ADB + Shield.apk), das die Android-App per USB
; auf ein angeschlossenes Handy installiert. Shield selbst ist eine Android-App und
; läuft nicht unter Windows.
Unicode true
!include "MUI2.nsh"

!ifndef VERSION
  !define VERSION "0.0.0"
!endif
!ifndef OUTFILE
  !define OUTFILE "Shield-Setup-${VERSION}.exe"
!endif

Name "Shield ${VERSION}"
OutFile "${OUTFILE}"
InstallDir "$LOCALAPPDATA\Shield-Installer"
RequestExecutionLevel user
SetCompressor /SOLID lzma
BrandingText "Shield ${VERSION} · GPL-3.0"

VIProductVersion "${VERSION}.0"
VIAddVersionKey /LANG=1031 "ProductName" "Shield"
VIAddVersionKey /LANG=1031 "FileDescription" "Shield Installer (Android-App per USB installieren)"
VIAddVersionKey /LANG=1031 "FileVersion" "${VERSION}"
VIAddVersionKey /LANG=1031 "LegalCopyright" "GPL-3.0"

!define MUI_ABORTWARNING
!define MUI_WELCOMEPAGE_TITLE "Shield ${VERSION} einrichten"
!define MUI_WELCOMEPAGE_TEXT "Shield ist eine Android-App (DNS-Blocker, Firewall, WireGuard-VPN).$\r$\n$\r$\nDieser Installer legt die App (Shield.apk) und die Android-Platform-Tools (ADB) auf deinem PC ab und installiert Shield danach per USB auf dein Handy.$\r$\n$\r$\nVoraussetzung am Handy: Entwickleroptionen → USB-Debugging an.$\r$\n$\r$\nAlternativ kannst du die APK auch direkt auf dem Handy öffnen."
!define MUI_FINISHPAGE_RUN "$INSTDIR\install-to-phone.cmd"
!define MUI_FINISHPAGE_RUN_TEXT "Jetzt Shield auf das angeschlossene Handy installieren"
!define MUI_FINISHPAGE_SHOWREADME "$INSTDIR"
!define MUI_FINISHPAGE_SHOWREADME_TEXT "Ordner mit der APK öffnen"

!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_LICENSE "../LICENSE"
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE "German"
!insertmacro MUI_LANGUAGE "English"

Section "Shield" SecMain
  SectionIn RO
  SetOutPath "$INSTDIR"
  File "Shield.apk"
  File "install-to-phone.cmd"
  File "LIESMICH.txt"
  File "../LICENSE"
  SetOutPath "$INSTDIR\platform-tools"
  File "platform-tools/adb.exe"
  File "platform-tools/AdbWinApi.dll"
  File "platform-tools/AdbWinUsbApi.dll"
  File /nonfatal "platform-tools/NOTICE.txt"
  SetOutPath "$INSTDIR"
  WriteUninstaller "$INSTDIR\Uninstall.exe"
  CreateDirectory "$SMPROGRAMS\Shield"
  CreateShortcut "$SMPROGRAMS\Shield\Shield auf Handy installieren.lnk" "$INSTDIR\install-to-phone.cmd"
  CreateShortcut "$SMPROGRAMS\Shield\APK-Ordner.lnk" "$INSTDIR"
  CreateShortcut "$SMPROGRAMS\Shield\Deinstallieren.lnk" "$INSTDIR\Uninstall.exe"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\ShieldInstaller" "DisplayName" "Shield Installer ${VERSION}"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\ShieldInstaller" "DisplayVersion" "${VERSION}"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\ShieldInstaller" "UninstallString" "$\"$INSTDIR\Uninstall.exe$\""
  WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\ShieldInstaller" "NoModify" 1
SectionEnd

Section "Uninstall"
  ExecWait '"$INSTDIR\platform-tools\adb.exe" kill-server'
  Delete "$INSTDIR\platform-tools\*.*"
  RMDir "$INSTDIR\platform-tools"
  Delete "$INSTDIR\Shield.apk"
  Delete "$INSTDIR\install-to-phone.cmd"
  Delete "$INSTDIR\LIESMICH.txt"
  Delete "$INSTDIR\LICENSE"
  Delete "$INSTDIR\Uninstall.exe"
  RMDir "$INSTDIR"
  Delete "$SMPROGRAMS\Shield\*.lnk"
  RMDir "$SMPROGRAMS\Shield"
  DeleteRegKey HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\ShieldInstaller"
SectionEnd
