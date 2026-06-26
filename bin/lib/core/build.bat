@echo off
setlocal

rem Get project root directory
for %%I in ("%~dp0..\..\..") do set "PROJECT=%%~fI"

rem Clean build directory
if exist "%PROJECT%\library\core\build" (
    rmdir /s /q "%PROJECT%\library\core\build"
)

rem Build library
cd /d "%PROJECT%\library\core"
call build.bat
if errorlevel 1 exit /b 1

rem Copy to app/libs
if not exist "%PROJECT%\app\libs" mkdir "%PROJECT%\app\libs"
copy /Y libexclavecore.aar "%PROJECT%\app\libs"
