; Shield für Windows – Installer
; Installiert Shield (DNS-Blocker, verschlüsseltes DNS, WireGuard-VPN mit Kill-Switch)
; und optional die Android-App per USB auf ein Handy.
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
InstallDir "$PROGRAMFILES64\Shield"
RequestExecutionLevel admin
SetCompressor /SOLID lzma
BrandingText "Shield ${VERSION} · GPL-3.0"
Icon "shield.ico"
UninstallIcon "shield.ico"

VIProductVersion "${VERSION}.0"
VIAddVersionKey /LANG=1031 "ProductName" "Shield"
VIAddVersionKey /LANG=1031 "FileDescription" "Shield für Windows – Installer"
VIAddVersionKey /LANG=1031 "FileVersion" "${VERSION}"
VIAddVersionKey /LANG=1031 "LegalCopyright" "GPL-3.0"

!define MUI_ICON "shield.ico"
!define MUI_UNICON "shield.ico"
!define MUI_ABORTWARNING
!define MUI_WELCOMEPAGE_TITLE "Shield ${VERSION} für Windows"
!define MUI_WELCOMEPAGE_TEXT "Shield schützt deinen PC: Werbung, Tracker, Telemetrie, Malware und Phishing werden blockiert, alle DNS-Anfragen laufen verschlüsselt. Dein VPN (z. B. Proton) läuft per WireGuard direkt in Shield – mit Kill-Switch.$\r$\n$\r$\nShield braucht Administratorrechte, weil es die DNS-Einstellungen von Windows ändert. Beim Beenden werden sie wiederhergestellt."
!define MUI_FINISHPAGE_RUN "$INSTDIR\Shield.exe"
!define MUI_FINISHPAGE_RUN_TEXT "Shield jetzt starten"

!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_LICENSE "../LICENSE"
!insertmacro MUI_PAGE_COMPONENTS
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE "German"
!insertmacro MUI_LANGUAGE "English"

Section "Shield für Windows" SecMain
  SectionIn RO
  ; update: stop a running instance (it restores the DNS on exit)
  nsExec::Exec 'taskkill /IM Shield.exe /F'
  SetOutPath "$INSTDIR"
  File "Shield.exe"
  File "wintun.dll"
  File "shield.ico"
  File "../LICENSE"
  WriteUninstaller "$INSTDIR\Uninstall.exe"
  CreateDirectory "$SMPROGRAMS\Shield"
  CreateShortcut "$SMPROGRAMS\Shield\Shield.lnk" "$INSTDIR\Shield.exe" "" "$INSTDIR\shield.ico"
  CreateShortcut "$SMPROGRAMS\Shield\Shield – DNS zurücksetzen.lnk" "$INSTDIR\Shield.exe" "--restore-dns" "$INSTDIR\shield.ico"
  CreateShortcut "$SMPROGRAMS\Shield\Deinstallieren.lnk" "$INSTDIR\Uninstall.exe"
  CreateShortcut "$DESKTOP\Shield.lnk" "$INSTDIR\Shield.exe" "" "$INSTDIR\shield.ico"
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\Shield" "DisplayName" "Shield ${VERSION}"
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\Shield" "DisplayVersion" "${VERSION}"
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\Shield" "Publisher" "Shield Open Source"
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\Shield" "DisplayIcon" "$INSTDIR\shield.ico"
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\Shield" "UninstallString" "$\"$INSTDIR\Uninstall.exe$\""
  WriteRegDWORD HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\Shield" "NoModify" 1
SectionEnd

Section /o "Android-App aufs Handy installieren (USB)" SecAndroid
  SetOutPath "$INSTDIR\Android"
  File "Shield.apk"
  File "install-to-phone.cmd"
  File "LIESMICH.txt"
  SetOutPath "$INSTDIR\Android\platform-tools"
  File "platform-tools/adb.exe"
  File "platform-tools/AdbWinApi.dll"
  File "platform-tools/AdbWinUsbApi.dll"
  File /nonfatal "platform-tools/NOTICE.txt"
  CreateShortcut "$SMPROGRAMS\Shield\Android-App aufs Handy installieren.lnk" "$INSTDIR\Android\install-to-phone.cmd"
SectionEnd

!insertmacro MUI_FUNCTION_DESCRIPTION_BEGIN
  !insertmacro MUI_DESCRIPTION_TEXT ${SecMain} "Shield für Windows: DNS-Blocker, verschlüsseltes DNS, WireGuard-VPN, Selbsttest und Selbstheilung."
  !insertmacro MUI_DESCRIPTION_TEXT ${SecAndroid} "Zusätzlich die Android-App und ADB, um Shield per USB auf ein Handy zu installieren."
!insertmacro MUI_FUNCTION_DESCRIPTION_END

Section "Uninstall"
  nsExec::Exec 'taskkill /IM Shield.exe /F'
  ExecWait '"$INSTDIR\Shield.exe" --restore-dns'
  nsExec::Exec 'schtasks /Delete /TN Shield /F'
  nsExec::Exec '"$INSTDIR\Android\platform-tools\adb.exe" kill-server'
  RMDir /r "$INSTDIR\Android"
  Delete "$INSTDIR\Shield.exe"
  Delete "$INSTDIR\wintun.dll"
  Delete "$INSTDIR\shield.ico"
  Delete "$INSTDIR\LICENSE"
  Delete "$INSTDIR\Uninstall.exe"
  RMDir "$INSTDIR"
  ; settings, lists and encrypted keys
  RMDir /r "$COMMONAPPDATA\Shield"
  RMDir /r "$LOCALAPPDATA\Shield"
  Delete "$SMPROGRAMS\Shield\*.lnk"
  RMDir "$SMPROGRAMS\Shield"
  Delete "$DESKTOP\Shield.lnk"
  DeleteRegKey HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\Shield"
SectionEnd
