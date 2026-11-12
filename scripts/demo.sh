#!/bin/bash
# Injects a demo scenario into the Pleos Connect emulator through its Virtual Console (port 8088).
# usage: scripts/demo.sh <headlamp|tire-rain|brake|overheat|battery|steering|airbag|clear>
# The day/night, road and distance conditions are switched with the chips inside the app.
set -e
ADB="${ADB:-adb}"
PORT=18088
B="http://127.0.0.1:$PORT"
"$ADB" forward tcp:$PORT tcp:8088 >/dev/null

# The console only accepts writes while a browser session is alive (3 s timeout).
hb() { curl -s -m 2 -X POST "$B/browser/session?client=cardoctor-demo" -o /dev/null; }
put() { hb; curl -s -m 10 -X PUT -o /dev/null -w "%{http_code} $1\n" "$B$1"; }
lamp() { put "/vehicle/telltales/simulator?type=$1&state=$2&color=$3"; }

hb; sleep 1; hb; sleep 1; hb

clear_all() {
  for t in LEDHeadlamp TPMS ParkingBrake ABS EngineTemperature LowVoltBattery MDPS Airbag; do lamp "$t" Off Red; done
  put "/vehicle/body?front_wiper=Off"
}

case "$1" in
  headlamp)  clear_all; lamp LEDHeadlamp On Yellow ;;                                  # 낮 1등급, 앱에서 '밤'으로 바꾸면 3등급
  tire-rain) clear_all; lamp TPMS On Yellow; put "/vehicle/body?front_wiper=Low" ;;    # 와이퍼 작동 = 비 → 3등급
  brake)     clear_all; lamp ParkingBrake On Red; lamp ABS On Yellow ;;                # 4등급 즉시 정차
  overheat)  clear_all; lamp EngineTemperature On Red ;;                               # 환경 무관 3등급
  battery)   clear_all; lamp LowVoltBattery On Red ;;                                  # 3등급 (등급 바닥)
  steering)  clear_all; lamp MDPS On Red ;;                                            # 3등급 (등급 바닥)
  airbag)    clear_all; lamp Airbag On Yellow ;;                                       # 2등급
  clear)     clear_all ;;
  *) echo "usage: $0 <headlamp|tire-rain|brake|overheat|battery|steering|airbag|clear>"; exit 1 ;;
esac
