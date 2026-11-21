; HoldFrame - visible, per-user installer with optional launch on the Finish page.
Unicode true
!include "MUI2.nsh"

!define APP_NAME "HoldFrame"
Name "${APP_NAME}"
OutFile "..\release\HoldFrame-Setup.exe"
InstallDir "$LOCALAPPDATA\Programs\HoldFrame"
RequestExecutionLevel user
SetCompressor /SOLID lzma
ShowInstDetails show
ShowUninstDetails show

!define MUI_ABORTWARNING
!define MUI_ICON "..\assets\HoldFrame.ico"
!define MUI_UNICON "..\assets\HoldFrame.ico"
!define MUI_FINISHPAGE_RUN "$INSTDIR\HoldFrame.exe"
!define MUI_FINISHPAGE_RUN_TEXT "Launch HoldFrame now"
!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_COMPONENTS
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE "English"

Section "HoldFrame application (required)" SecApp
  SectionIn RO
  SetOutPath "$INSTDIR"
  File /r "..\packaged\HoldFrame\*.*"
  WriteUninstaller "$INSTDIR\Uninstall.exe"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\HoldFrame" "DisplayName" "HoldFrame"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\HoldFrame" "UninstallString" '$"$INSTDIR\Uninstall.exe$"'
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\HoldFrame" "DisplayIcon" "$INSTDIR\HoldFrame.exe"
  WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\HoldFrame" "NoModify" 1
  WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\HoldFrame" "NoRepair" 1
SectionEnd

Section "Start Menu shortcuts" SecStartMenu
  CreateDirectory "$SMPROGRAMS\HoldFrame"
  CreateShortcut "$SMPROGRAMS\HoldFrame\HoldFrame.lnk" "$INSTDIR\HoldFrame.exe"
  CreateShortcut "$SMPROGRAMS\HoldFrame\Uninstall.lnk" "$INSTDIR\Uninstall.exe"
SectionEnd

Section "Desktop shortcut" SecDesktop
  CreateShortcut "$DESKTOP\HoldFrame.lnk" "$INSTDIR\HoldFrame.exe"
SectionEnd

Section "Uninstall"
  Delete "$DESKTOP\HoldFrame.lnk"
  Delete "$SMPROGRAMS\HoldFrame\HoldFrame.lnk"
  Delete "$SMPROGRAMS\HoldFrame\Uninstall.lnk"
  RMDir "$SMPROGRAMS\HoldFrame"
  ; Delete only HoldFrame-created components; do not wipe an arbitrary user-selected folder.
  RMDir /r "$INSTDIR\app"
  RMDir /r "$INSTDIR\runtime"
  Delete "$INSTDIR\HoldFrame.exe"
  Delete "$INSTDIR\Uninstall.exe"
  RMDir "$INSTDIR"
  DeleteRegKey HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\HoldFrame"
SectionEnd
