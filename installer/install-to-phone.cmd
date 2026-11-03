@echo off
chcp 65001 >nul
title Shield auf Android installieren
echo.
echo  Shield auf dein Android-Handy installieren
echo  ==========================================
echo.
echo  1. Am Handy: Einstellungen ^> Ueber das Telefon ^> 7x auf "Build-Nummer" tippen.
echo  2. Einstellungen ^> System ^> Entwickleroptionen ^> "USB-Debugging" einschalten.
echo  3. Handy per USB verbinden und die Frage "USB-Debugging zulassen?" bestaetigen.
echo.
pause
set ADB="%~dp0platform-tools\adb.exe"
%ADB% start-server
echo Warte auf das Handy ...
%ADB% wait-for-device
echo Installiere Shield ...
%ADB% install -r "%~dp0Shield.apk"
if errorlevel 1 (
  echo.
  echo  Installation fehlgeschlagen. Ist eine Version mit anderer Signatur installiert?
  echo  Dann Shield am Handy deinstallieren und erneut versuchen.
) else (
  echo.
  echo  Fertig! Oeffne Shield auf dem Handy und tippe auf den Schalter.
)
echo.
pause
