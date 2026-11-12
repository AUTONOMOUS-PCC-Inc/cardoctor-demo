@echo off
rem Injects a demo scenario into the Pleos Connect emulator through its Virtual Console (port 8088).
rem Windows version of scripts/demo.sh, for cmd and PowerShell.
rem usage: scripts\demo.cmd ^<headlamp^|tire-rain^|brake^|overheat^|battery^|steering^|airbag^|clear^>
rem The day/night, road and distance conditions are switched with the chips inside the app.
setlocal
chcp 65001 >nul

set "ADB=adb"
where adb >nul 2>nul || set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if not "%ADB%"=="adb" if not exist "%ADB%" (
  echo adb를 찾을 수 없습니다. Android SDK platform-tools를 설치하거나 PATH에 추가하세요.
  exit /b 1
)
set "B=http://127.0.0.1:18088"

if "%~1"=="" goto usage

"%ADB%" forward tcp:18088 tcp:8088 >nul 2>nul
if errorlevel 1 (
  echo 에뮬레이터에 연결할 수 없습니다. 에뮬레이터가 켜져 있는지 확인하세요.
  exit /b 1
)

rem The console only accepts writes while a browser session is alive (3 s timeout).
call :hb
ping -n 2 127.0.0.1 >nul
call :hb
ping -n 2 127.0.0.1 >nul
call :hb

if /i "%~1"=="headlamp"  ( call :clear_all & call :lamp LEDHeadlamp On Yellow & goto done )
if /i "%~1"=="tire-rain" ( call :clear_all & call :lamp TPMS On Yellow & call :put "/vehicle/body?front_wiper=Low" & goto done )
if /i "%~1"=="brake"     ( call :clear_all & call :lamp ParkingBrake On Red & call :lamp ABS On Yellow & goto done )
if /i "%~1"=="overheat"  ( call :clear_all & call :lamp EngineTemperature On Red & goto done )
if /i "%~1"=="battery"   ( call :clear_all & call :lamp LowVoltBattery On Red & goto done )
if /i "%~1"=="steering"  ( call :clear_all & call :lamp MDPS On Red & goto done )
if /i "%~1"=="airbag"    ( call :clear_all & call :lamp Airbag On Yellow & goto done )
if /i "%~1"=="clear"     ( call :clear_all & goto done )
goto usage

:done
echo 완료: %~1
exit /b 0

:usage
echo 사용법: scripts\demo.cmd ^<headlamp^|tire-rain^|brake^|overheat^|battery^|steering^|airbag^|clear^>
echo   headlamp   전조등 이상. 낮 1등급, 앱에서 '낮' 칩을 눌러 '밤'으로 바꾸면 3등급
echo   tire-rain  타이어 공기압 경고 + 와이퍼 작동(비) = 3등급
echo   brake      브레이크 + ABS 동시 점등 = 4등급
echo   overheat   과열 경고 = 3등급
echo   battery    저전압 배터리 경고 = 3등급
echo   steering   조향 보조(MDPS) 경고 = 3등급
echo   airbag     에어백 경고 = 2등급
echo   clear      원상 복구
exit /b 1

:hb
curl -s -m 2 -X POST "%B%/browser/session?client=cardoctor-demo" -o NUL
exit /b 0

:put
call :hb
curl -s -m 10 -X PUT -o NUL -w "%%{http_code} %~1\n" "%B%%~1"
exit /b 0

:lamp
call :put "/vehicle/telltales/simulator?type=%~1&state=%~2&color=%~3"
exit /b 0

:clear_all
for %%T in (LEDHeadlamp TPMS ParkingBrake ABS EngineTemperature LowVoltBattery MDPS Airbag) do call :lamp %%T Off Red
call :put "/vehicle/body?front_wiper=Off"
exit /b 0
