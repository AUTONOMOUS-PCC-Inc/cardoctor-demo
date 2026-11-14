#!/usr/bin/env python3
"""Records the demo video scenes (docs: 카닥터 시연영상 시나리오) on the running Pleos emulator.

Each scene is its own .webm clip (emulator screen + app audio), driven the same way the PC demo
console drives the app: Virtual Console for telltales/wipers, adb broadcasts for app-side values,
adb taps for the app's own buttons (found by their on-screen text, so layout shifts do not matter).

    python tools/record_demo.py [output_dir] [scene numbers...]
"""

from __future__ import annotations

import re
import subprocess
import sys
import threading
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import demo_console as dc  # noqa: E402

OUT = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parent.parent / "captures" / "demo_video"
ONLY = {int(a) for a in sys.argv[2:]}
OUT.mkdir(parents=True, exist_ok=True)

console = dc.VirtualConsole()
console.forward()


def adb(*args: str, timeout: float = 30) -> str:
    result = console.run_adb(*args, timeout=timeout)
    return (result.stdout + result.stderr).decode("utf-8", errors="replace")


def heartbeat_loop() -> None:
    while True:
        try:
            console.heartbeat()
        except Exception:
            pass
        time.sleep(1)


threading.Thread(target=heartbeat_loop, daemon=True).start()


def log(msg: str) -> None:
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


# ---------- app and vehicle controls ----------

def app(cmd: str, **extras) -> None:
    if not console.app_command(cmd, **extras):
        log(f"app command failed: {cmd} {extras}")


def telltale(kind: str, on: bool) -> None:
    status = console.set_telltale(kind, "On" if on else "Off", dc.DEFAULT_COLOR.get(kind, "Red"))
    if status != 200:
        log(f"telltale {kind} -> HTTP {status}")


def clear_vehicle() -> None:
    for kind in dc.DEFAULT_COLOR:
        console.set_telltale(kind, "Off", dc.DEFAULT_COLOR[kind])
    console.set_wiper("Off")
    app("tires_clear")


def nodes() -> list[tuple[str, int, int, int, int]]:
    adb("shell", "uiautomator", "dump", "--user", "10", "/sdcard/ui.xml")
    xml = adb("shell", "cat", "/sdcard/ui.xml")
    found = []
    for m in re.finditer(r'text="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        found.append((m.group(1), *map(int, m.groups()[1:])))
    return found


def tap_text(text: str, exact: bool = False, pick=min, tries: int = 6) -> bool:
    """Taps the element whose text matches; `pick` chooses among several (by top edge)."""
    for _ in range(tries):
        hits = [n for n in nodes() if (n[0] == text if exact else text in n[0])]
        if hits:
            t, x1, y1, x2, y2 = pick(hits, key=lambda n: n[2])
            adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
            log(f"tap '{t}'")
            return True
        time.sleep(1)
    log(f"not found: {text}")
    return False


def front_app() -> None:
    adb("shell", "am", "start", "--user", "10", "-n", dc.APP_ACTIVITY)


def wait(seconds: float) -> None:
    time.sleep(seconds)


# ---------- recording ----------

def record(number: int, title: str, body) -> None:
    if ONLY and number not in ONLY:
        return
    path = OUT / f"scene{number:02d}.webm"
    if path.exists():
        path.unlink()
    log(f"scene {number}: {title}")
    adb("emu", "screenrecord", "start", "--time-limit", "180", str(path))
    time.sleep(1.0)
    try:
        body()
    finally:
        adb("emu", "screenrecord", "stop")
        time.sleep(2.0)
    log(f"saved {path} ({path.stat().st_size // 1024} KB)" if path.exists() else f"missing {path}")


# ---------- scenes (timings follow the scenario table) ----------

def prepare() -> None:
    log("prepare: fresh app, clean vehicle, demo environment locked")
    adb("shell", "am", "force-stop", "--user", "10", dc.APP_PACKAGE)
    clear_vehicle()
    adb("logcat", "-c")
    front_app()
    for _ in range(40):
        if "Tmap" in adb("logcat", "-d", "-s", "CarDoctorLog:I"):
            break
        time.sleep(1)
    app("env_reset")
    app("env_lock")
    wait(4)


def scene1() -> None:
    wait(12)


def scene2() -> None:
    wait(13)


def scene3() -> None:
    telltale("LEDHeadlamp", True)          # day: grade 1
    wait(9)
    app("env", key="night", value="on")    # night: +3 -> grade 3, spoken warning
    wait(20)


def scene4() -> None:
    clear_vehicle()
    app("env_lock")
    wait(3)
    app("tire", wheel="RIGHT_REAR", low=True)   # grade 2
    wait(6)
    app("env", key="weather", value="on")       # rain: +2 -> grade 3
    wait(8)
    tap_text("자세히보기", exact=True)
    wait(9)
    tap_text("닫기", exact=True)
    wait(1)


def scene5() -> None:
    clear_vehicle()
    app("env", key="weather", value="on")       # still raining from scene 4 (also when re-shot alone)
    telltale("ParkingBrake", True)
    telltale("ABS", True)
    wait(14)


def scene6() -> None:
    telltale("ParkingBrake", False)
    telltale("ABS", False)
    telltale("TPMS", True)
    console.set_wiper("Low")
    app("env", key="weather", value="on")       # rain stays on from scene 4 (also when re-shot alone)
    wait(4)
    app("env", key="driving", value="on")
    wait(4)
    tap_text("자세히")  # while driving the button reads "정차 후 / 자세히"
    wait(5)
    app("ask", q=0)                             # "지금 정비소 가야 돼?" (same path as speech)
    wait(16)


def scene7() -> None:
    app("env", key="driving", value="off")
    wait(4)
    tap_text("지도에서 보기", exact=True)
    wait(11)
    tap_text("안내 시작", exact=True, pick=min)  # the map's first row (top-most button)
    wait(16)  # the car navigation shows the route, counts down 5 s, then starts guidance


def scene8() -> None:
    front_app()
    wait(3)
    tap_text("정비소 리포트 · QR", exact=True)
    wait(7)
    tap_text("저장")
    wait(6)
    tap_text("닫기", exact=True)
    wait(2)


def scene9() -> None:
    clear_vehicle()
    app("env_lock")
    wait(10)


def end_navigation() -> None:
    adb("shell", "monkey", "-p", "ai.umos.maps.android.navigation.app", "-c", "android.intent.category.LAUNCHER", "1")
    wait(3)
    if tap_text("안내 종료", exact=True, tries=2):
        wait(2)
    front_app()


if __name__ == "__main__":
    prepare()
    record(1, "opening", scene1)
    record(2, "driving context chips", scene2)
    record(3, "same fault, day vs night", scene3)
    record(4, "rainy tire, details", scene4)
    record(5, "brake + ABS, stop now", scene5)
    record(6, "driving lock + voice question", scene6)
    record(7, "map, route preview, car navigation", scene7)
    if not ONLY or 7 in ONLY:
        end_navigation()
    record(8, "service report QR", scene8)
    record(9, "closing", scene9)
    log("done")
