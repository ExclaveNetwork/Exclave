@echo off

set CGO_LDFLAGS=-Wl,-z,max-page-size=16384

gomobile bind -v -androidapi 21 -tags="with_clash" "github.com/dyhkwong/libsagernetcore"
if errorlevel 1 (
    exit /b 1
)

set "proj=..\..\app\libs"

if exist "%proj%" (
    copy /Y libsagernetcore.aar "%proj%"
)
