@echo off
setlocal enabledelayedexpansion

set "EXEC="
set "TARGET=bin"
set "PARAMS="

:parse_args
if "%~1"=="" goto run_exec
set "TARGET=!TARGET!\%~1"
if not "!PARAMS!"=="" set "PARAMS=!PARAMS! "
set "PARAMS=!PARAMS!%~1"
shift

if exist "!TARGET!.bat" (
    set "EXEC=!TARGET!.bat"
    set "PARAMS="
)
goto parse_args

:run_exec
echo ^>^> %EXEC%
if defined EXEC (
    call "%EXEC%" %PARAMS%
) else (
    echo Error: No executable found
    exit /b 1
)
