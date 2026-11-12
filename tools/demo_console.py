#!/usr/bin/env python3
"""카닥터 시연 콘솔 — Pleos Connect 에뮬레이터에 고장을 주입하는 GUI 도구.

에뮬레이터의 Virtual Console(기기 포트 8088)에 HTTP로 값을 넣는다. 표준 라이브러리(tkinter, urllib)만 쓴다.

    python tools/demo_console.py

요구 사항: Python 3.9+, adb (PATH 또는 Android SDK 기본 위치), 켜져 있는 Pleos Connect 에뮬레이터.

화면은 앱과 같은 디자인 언어를 쓴다: 피아노 블랙 바탕, 유리 패널, 얇은 서체, 그리고 차량 상태에 따라
색이 바뀌는 앰비언트 조명. tkinter 기본 위젯으로는 둥근 모서리와 발광을 낼 수 없어 전부 Canvas에 그린다.
"""

from __future__ import annotations

import json
import os
import queue
import shutil
import subprocess
import sys
import threading
import time
import tkinter as tk
import tkinter.font as tkfont
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime
from pathlib import Path
from tkinter import ttk

ROOT = Path(__file__).resolve().parent.parent
LOCAL_PORT = 18088
DEVICE_PORT = 8088
BASE = f"http://127.0.0.1:{LOCAL_PORT}"
APP_ACTIVITY = "com.cardoctor.demo/.MainActivity"
CAPTURE_DIR = ROOT / "captures"
FONT_DIR = ROOT / "app" / "src" / "main" / "res" / "font"

# (표시 이름, 콘솔의 경고등 종류, 기본 색, 앱에서의 결과). 종류가 None이면 와이퍼.
FAULTS = [
    ("전조등", "LEDHeadlamp", "Yellow", "낮 1등급 · 밤 3등급"),
    ("타이어 공기압", "TPMS", "Yellow", "2등급 · 비 오면 3등급"),
    ("ABS", "ABS", "Yellow", "2등급 · 비 오면 3등급"),
    ("브레이크 경고등", "ParkingBrake", "Red", "단독은 안내 · ABS와 4등급"),
    ("과열", "EngineTemperature", "Red", "3등급"),
    ("저전압 배터리", "LowVoltBattery", "Red", "3등급"),
    ("조향 보조", "MDPS", "Red", "3등급"),
    ("에어백", "Airbag", "Yellow", "2등급"),
    ("와이퍼 작동 (비)", None, "Blue", "앱이 악천후로 판단"),
]

# (이름, 설명, 켤 경고등, 와이퍼 작동 여부, 앱에서 기대하는 등급 표시)
SCENARIOS = [
    ("전조등 이상", "낮 1등급. 앱에서 밤으로 바꾸면 3등급", ["LEDHeadlamp"], False, "1·3"),
    ("빗길 타이어", "공기압 경고 + 와이퍼 작동(비)", ["TPMS"], True, "3"),
    ("브레이크 + ABS", "동시 점등, 즉시 정차", ["ParkingBrake", "ABS"], False, "4"),
    ("과열", "환경과 관계없이 즉시 정비", ["EngineTemperature"], False, "3"),
    ("저전압 배터리", "즉시 정비", ["LowVoltBattery"], False, "3"),
    ("조향 보조", "즉시 정비", ["MDPS"], False, "3"),
    ("에어백", "목적지 후 정비", ["Airbag"], False, "2"),
]

DEFAULT_COLOR = {t: c for _, t, c, _ in FAULTS if t}

# ---- palette (same as the app's ui/Palette.kt) ----
INK = "#06080b"
PANEL = "#0f1319"
ROW = "#151a22"
EDGE = "#232a33"
TEXT, BODY, MUTED, FAINT = "#f4f6f8", "#cdd3da", "#929ba7", "#5e6772"
ACCENT, OK, AMBER, RED = "#9cc9ff", "#63e0c6", "#f5b94a", "#ff4d57"
LAMP = {"Red": RED, "Yellow": AMBER, "Green": "#5fd38d", "Blue": ACCENT, "White": TEXT, "Gray": MUTED}


def mix(a: str, b: str, t: float) -> str:
    """Blend colour a towards b by t (0..1). Stands in for transparency, which tkinter lacks."""
    ar, ag, ab = int(a[1:3], 16), int(a[3:5], 16), int(a[5:7], 16)
    br, bg, bb = int(b[1:3], 16), int(b[3:5], 16), int(b[5:7], 16)
    return "#%02x%02x%02x" % (round(ar + (br - ar) * t), round(ag + (bg - ag) * t), round(ab + (bb - ab) * t))


def find_adb() -> str | None:
    found = shutil.which("adb")
    if found:
        return found
    candidates = []
    for variable in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(variable):
            candidates.append(Path(os.environ[variable]) / "platform-tools")
    if os.environ.get("LOCALAPPDATA"):
        candidates.append(Path(os.environ["LOCALAPPDATA"]) / "Android" / "Sdk" / "platform-tools")
    candidates.append(Path.home() / "Library" / "Android" / "sdk" / "platform-tools")
    candidates.append(Path.home() / "Android" / "Sdk" / "platform-tools")
    for folder in candidates:
        for name in ("adb.exe", "adb"):
            if (folder / name).is_file():
                return str(folder / name)
    return None


class VirtualConsole:
    """HTTP client for the emulator's Virtual Console, reached through an adb port forward."""

    def __init__(self) -> None:
        self.adb = find_adb()
        self.forwarded = False

    def run_adb(self, *args: str, timeout: float = 15) -> subprocess.CompletedProcess:
        if not self.adb:
            raise RuntimeError("adb를 찾을 수 없습니다")
        flags = subprocess.CREATE_NO_WINDOW if sys.platform == "win32" else 0
        return subprocess.run([self.adb, *args], capture_output=True, timeout=timeout, creationflags=flags)

    def forward(self) -> None:
        result = self.run_adb("forward", f"tcp:{LOCAL_PORT}", f"tcp:{DEVICE_PORT}")
        if result.returncode != 0:
            raise RuntimeError(result.stderr.decode(errors="replace").strip() or "adb forward 실패")
        self.forwarded = True

    def _request(self, method: str, path: str, timeout: float = 4) -> tuple[int, bytes]:
        request = urllib.request.Request(BASE + path, method=method, data=b"" if method != "GET" else None)
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                return response.status, response.read()
        except urllib.error.HTTPError as error:
            return error.code, error.read()

    def heartbeat(self) -> None:
        """The console accepts writes only while a session heartbeat is alive (3 s timeout)."""
        self._request("POST", "/browser/session?client=cardoctor-tool", timeout=2)

    def state(self) -> dict:
        status, body = self._request("GET", "/vehicle/state")
        if status != 200:
            raise RuntimeError(f"상태 조회 실패 (HTTP {status})")
        return json.loads(body)

    def set_telltale(self, console_type: str, state: str, color: str) -> int:
        query = urllib.parse.urlencode({"type": console_type, "state": state, "color": color})
        return self._request("PUT", f"/vehicle/telltales/simulator?{query}")[0]

    def set_wiper(self, mode: str) -> int:
        return self._request("PUT", f"/vehicle/body?front_wiper={mode}")[0]

    def screenshot(self) -> Path:
        result = self.run_adb("exec-out", "screencap", "-p", timeout=30)
        if result.returncode != 0 or not result.stdout:
            raise RuntimeError("화면 캡처 실패")
        CAPTURE_DIR.mkdir(exist_ok=True)
        path = CAPTURE_DIR / f"cardoctor_{datetime.now():%Y%m%d_%H%M%S}.png"
        path.write_bytes(result.stdout)
        return path


def load_fonts() -> dict[str, str]:
    """Uses the app's bundled Pretendard when it can be loaded (Windows), else a system font."""
    fallback = "Malgun Gothic" if sys.platform == "win32" else "TkDefaultFont"
    names = {"light": fallback, "regular": fallback, "medium": fallback, "semi": fallback}
    if sys.platform == "win32":
        try:
            import ctypes
            for file in FONT_DIR.glob("pretendard_*.otf"):
                ctypes.windll.gdi32.AddFontResourceExW(str(file), 0x10, 0)  # FR_PRIVATE: this process only
        except Exception:
            return names
    families = set(tkfont.families())
    wanted = {"light": "Pretendard Light", "regular": "Pretendard", "medium": "Pretendard Medium",
              "semi": "Pretendard SemiBold"}
    for key, family in wanted.items():
        if family in families:
            names[key] = family
    return names


class DemoConsoleApp(tk.Tk):
    def __init__(self) -> None:
        super().__init__()
        self.title("CarDoctor · Demo Console")
        self.configure(bg=INK)
        self.geometry("1280x860")
        self.minsize(1180, 780)
        self.fonts = load_fonts()

        self.console = VirtualConsole()
        self.jobs: queue.Queue = queue.Queue()
        self.online: bool | None = None       # None = not known yet
        self.ready = False
        self.lit: dict[str, dict] = {}
        self.wiper_mode = "Off"
        self.entries: list[tuple[str, str, str]] = []   # (time, kind, text)
        self.closing = False
        self.render_pending = False

        self.canvas = tk.Canvas(self, bg=INK, highlightthickness=0, bd=0)
        self.canvas.pack(fill="both", expand=True)
        self._make_inputs()
        self.canvas.bind("<Configure>", lambda _event: self.request_render())

        threading.Thread(target=self._worker, daemon=True).start()
        self.protocol("WM_DELETE_WINDOW", self._close)
        self.log("info", "도구 시작. 에뮬레이터에 연결하는 중…")
        self.submit(self._load_types)
        self._poll()

    # ================= background work =================

    def _worker(self) -> None:
        while True:
            job = self.jobs.get()
            try:
                job()
            except Exception as error:  # shown in the log; the tool keeps running
                self.ui(self.log, "error", f"오류: {error}")

    def submit(self, job) -> None:
        self.jobs.put(job)

    def ui(self, function, *args) -> None:
        """Schedules a UI update from the worker thread."""
        if not self.closing:
            self.after(0, function, *args)

    def _poll(self) -> None:
        if self.closing:
            return
        if self.jobs.qsize() < 3:  # do not pile up polls while the emulator is unreachable
            self.submit(self._refresh)
        self.after(1000, self._poll)

    def _refresh(self) -> None:
        try:
            if not self.console.forwarded:
                self.console.forward()
            self.console.heartbeat()
            state = self.console.state()
        except Exception as error:
            self.console.forwarded = False
            self.ui(self._show_offline, self._explain(error))
            return
        telltales = state.get("telltales", {})
        lit = {name: value for name, value in telltales.get("actual", {}).items() if value.get("state") != "Off"}
        wiper = state.get("body", {}).get("wipers", {}).get("front", {}).get("mode", "Off")
        self.ui(self._show_state, lit, wiper, bool(telltales.get("emulator_active")))

    @staticmethod
    def _explain(error: Exception) -> str:
        text = str(error)
        if "adb를 찾을 수 없습니다" in text:
            return "adb를 찾을 수 없습니다. Android SDK platform-tools를 설치하세요."
        if "no devices" in text or "device offline" in text or "not found" in text:
            return "에뮬레이터가 연결되어 있지 않습니다."
        return "가상 콘솔에 연결할 수 없습니다. 에뮬레이터가 켜져 있는지 확인하세요."

    def _load_types(self) -> None:
        for _ in range(20):
            try:
                if not self.console.forwarded:
                    self.console.forward()
                types = list(self.console.state().get("telltales", {}).get("types", []))
                if types:
                    self.ui(lambda: self.type_box.configure(values=types))
                    return
            except Exception:
                pass
            time.sleep(1.5)

    # ================= actions =================

    def inject(self, console_type: str, on: bool, color: str | None = None, state: str | None = None) -> None:
        color = color or DEFAULT_COLOR.get(console_type, "Red")
        target = state or ("On" if on else "Off")

        def job() -> None:
            status = self.console.set_telltale(console_type, target, color)
            self.ui(self._log_result, f"경고등 {console_type} → {target} ({color})", status)

        self.submit(job)

    def set_wiper(self, on: bool) -> None:
        def job() -> None:
            status = self.console.set_wiper("Low" if on else "Off")
            self.ui(self._log_result, "와이퍼 작동(비)" if on else "와이퍼 정지", status)

        self.submit(job)

    def toggle(self, console_type: str | None) -> None:
        if console_type is None:
            self.set_wiper(self.wiper_mode == "Off")
        else:
            self.inject(console_type, console_type not in self.lit)

    def clear_all(self, announce: bool = True) -> None:
        def job() -> None:
            for name in sorted(set(self.lit) | set(DEFAULT_COLOR)):
                self.console.set_telltale(name, "Off", DEFAULT_COLOR.get(name, "Red"))
            self.console.set_wiper("Off")
            if announce:
                self.ui(self.log, "inject", "모든 고장 해제")

        self.submit(job)

    def run_scenario(self, name: str, types: list[str], rain: bool) -> None:
        self.clear_all(announce=False)

        def job() -> None:
            statuses = [self.console.set_telltale(t, "On", DEFAULT_COLOR.get(t, "Red")) for t in types]
            if rain:
                statuses.append(self.console.set_wiper("Low"))
            self.ui(self._log_result, f"시나리오 · {name}", next((s for s in statuses if s != 200), 200))

        self.submit(job)

    def launch_app(self) -> None:
        def job() -> None:
            result = self.console.run_adb("shell", "am", "start", "-n", APP_ACTIVITY)
            output = (result.stdout + result.stderr).decode(errors="replace")
            ok = result.returncode == 0 and "Error" not in output
            self.ui(self.log, "info" if ok else "error",
                    "카닥터 앱 실행" if ok else "앱 실행 실패. 앱이 설치되어 있는지 확인하세요.")

        self.submit(job)

    def capture(self) -> None:
        def job() -> None:
            path = self.console.screenshot()
            self.ui(self.log, "info", f"화면 캡처 저장: {path}")

        self.submit(job)

    def apply_custom(self) -> None:
        console_type = self.type_var.get().strip()
        if not console_type:
            self.log("error", "경고등 종류를 먼저 고르세요.")
            return
        self.inject(console_type, True, self.color_var.get(), self.state_var.get())

    # ================= state from the worker =================

    def _show_offline(self, reason: str) -> None:
        if self.online is not False:
            self.log("error", reason)
        self.online = False
        self.request_render()

    def _show_state(self, lit: dict, wiper: str, ready: bool) -> None:
        changed = self.online is not True or ready != self.ready
        if self.online is not True:
            self.log("info", "가상 콘솔에 연결됐습니다.")
        self.online, self.ready = True, ready
        for name in sorted(set(lit) - set(self.lit)):
            self.log("vehicle", f"{name} 점등 · {lit[name].get('state')} · {lit[name].get('color')}")
            changed = True
        for name in sorted(set(self.lit) - set(lit)):
            self.log("vehicle", f"{name} 소등")
            changed = True
        if wiper != self.wiper_mode:
            self.log("vehicle", f"앞 와이퍼 {wiper}")
            changed = True
        if lit != self.lit:
            changed = True
        self.lit, self.wiper_mode = lit, wiper
        if changed:
            self.request_render()

    def _log_result(self, what: str, status: int) -> None:
        if status == 200:
            self.log("inject", what)
        elif status == 503:
            self.log("error", f"{what} 실패 · 콘솔 세션 준비 중입니다. 1~2초 뒤 다시 누르세요.")
        else:
            self.log("error", f"{what} 실패 · HTTP {status}")

    def log(self, kind: str, message: str) -> None:
        self.entries.append((f"{datetime.now():%H:%M:%S}", kind, message))
        del self.entries[:-200]
        self.request_render()

    # ================= drawing =================

    def font(self, size: int, weight: str = "regular") -> tuple:
        return (self.fonts[weight], size)

    def request_render(self) -> None:
        if not self.render_pending and not self.closing:
            self.render_pending = True
            self.after_idle(self.render)

    def ambient(self) -> str:
        """Colour of the room light: follows what the car reports, as in the app."""
        if self.online is not True:
            return "#8e7bff"
        colors = {value.get("color") for value in self.lit.values()}
        if "Red" in colors:
            return RED
        return AMBER if self.lit else OK

    def rounded(self, x1, y1, x2, y2, r, **kwargs) -> int:
        points = [x1 + r, y1, x2 - r, y1, x2, y1, x2, y1 + r, x2, y2 - r, x2, y2,
                  x2 - r, y2, x1 + r, y2, x1, y2, x1, y2 - r, x1, y1 + r, x1, y1]
        return self.canvas.create_polygon(points, smooth=True, splinesteps=24, **kwargs)

    def text(self, x, y, value, size=11, weight="regular", fill=TEXT, anchor="w", tags=("dyn",), **kwargs) -> int:
        return self.canvas.create_text(x, y, text=value, font=self.font(size, weight), fill=fill, anchor=anchor,
                                       tags=tags, **kwargs)

    def eyebrow(self, x, y, value, fill=MUTED, anchor="w") -> int:
        """Small label with wide tracking (tkinter has no letter-spacing, so spaces stand in)."""
        return self.text(x, y, " ".join(value), size=8, weight="medium", fill=fill, anchor=anchor)

    def clickable(self, tag: str, command, normal: str, hover: str) -> None:
        """Wires click and hover for every canvas item carrying `tag`; `tag:bg` is its outline."""
        canvas = self.canvas
        canvas.tag_bind(tag, "<Button-1>", lambda _event: command())
        canvas.tag_bind(tag, "<Enter>", lambda _event: (canvas.itemconfigure(tag + ":bg", outline=hover),
                                                         canvas.configure(cursor="hand2")))
        canvas.tag_bind(tag, "<Leave>", lambda _event: (canvas.itemconfigure(tag + ":bg", outline=normal),
                                                         canvas.configure(cursor="")))

    def pill(self, x1, y1, x2, y2, label, tag, command, fill=ROW, outline=EDGE, color=TEXT, size=10) -> None:
        radius = (y2 - y1) / 2
        self.rounded(x1, y1, x2, y2, radius, fill=fill, outline=outline, width=1, tags=("dyn", tag, tag + ":bg"))
        self.text((x1 + x2) / 2, (y1 + y2) / 2, label, size=size, weight="medium", fill=color, anchor="center",
                  tags=("dyn", tag))
        self.clickable(tag, command, outline, mix(outline, TEXT, 0.45))

    def panel(self, x, y, w, h, title, ambient) -> None:
        self.rounded(x, y, x + w, y + h, 22, fill=mix(PANEL, ambient, 0.045), outline=EDGE, width=1, tags=("dyn",))
        # Top-lit edge, as on a sheet of glass.
        self.canvas.create_line(x + 26, y + 1, x + w - 26, y + 1, fill=mix(EDGE, TEXT, 0.22), tags=("dyn",))
        self.eyebrow(x + 24, y + 28, title)

    def render(self) -> None:
        self.render_pending = False
        if self.closing:
            return
        canvas = self.canvas
        w, h = canvas.winfo_width(), canvas.winfo_height()
        if w < 50 or h < 50:
            return
        canvas.delete("dyn")
        ambient = self.ambient()

        self._draw_background(w, h, ambient)
        pad = 38
        self._draw_header(w, pad, ambient)

        top = 124
        log_height = 172
        log_top = h - 30 - log_height
        height = log_top - 18 - top
        inner = w - 2 * pad
        gap = 18
        left_w = round(inner * 0.29)
        right_w = round(inner * 0.27)
        mid_w = inner - left_w - right_w - 2 * gap
        self._draw_scenarios(pad, top, left_w, height, ambient)
        self._draw_faults(pad + left_w + gap, top, mid_w, height, ambient)
        self._draw_state(pad + left_w + gap + mid_w + gap, top, right_w, height, ambient)
        self._draw_log(pad, log_top, inner, log_height, ambient)
        canvas.tag_raise("input")

    def _draw_background(self, w, h, ambient) -> None:
        canvas = self.canvas
        for y in range(0, h, 3):                       # piano-black vertical gradient
            t = y / h
            canvas.create_rectangle(0, y, w, y + 3, width=0, tags=("dyn",),
                                    fill=mix("#0b0e13", "#030405", t) if t > 0.25 else mix("#0d1016", "#0b0e13", t * 4))
        # Ambient light pooling from the lower left: concentric discs fading into the ground.
        cx, cy, steps = w * 0.16, h * 1.08, 44
        for i in range(steps):
            t = i / (steps - 1)
            radius = w * 0.62 * (1 - t * 0.9)
            ground = mix("#0b0e13", "#030405", min(1.0, max(0.0, (cy - radius * 0.5) / h)))
            canvas.create_oval(cx - radius, cy - radius * 0.8, cx + radius, cy + radius * 0.8, width=0,
                               fill=mix(ground, ambient, 0.30 * t * t), tags=("dyn",))
        # Light strip along the bottom edge.
        segments = 80
        for i in range(segments):
            t = i / (segments - 1)
            strength = max(0.0, 1 - abs(t - 0.5) * 2) ** 0.6
            canvas.create_rectangle(w * i / segments, h - 3, w * (i + 1) / segments + 1, h, width=0,
                                    fill=mix("#030405", ambient, strength), tags=("dyn",))

    def _draw_header(self, w, pad, ambient) -> None:
        self.text(pad, 48, " ".join("CARDOCTOR"), size=19, weight="light")
        self.eyebrow(pad + 2, 82, "DEMO CONSOLE")
        self.text(pad + 162, 82, "시연 콘솔", size=9, fill=FAINT)

        x = w - pad
        self.pill(x - 112, 34, x, 72, "화면 캡처", "btn_capture", self.capture)
        x -= 124
        self.pill(x - 100, 34, x, 72, "앱 실행", "btn_launch", self.launch_app)
        x -= 118
        if self.online is True:
            color, label = (OK, "가상 콘솔 연결됨") if self.ready else (AMBER, "연결됨 · 세션 준비 중")
        elif self.online is False:
            color, label = RED, "연결 안 됨"
        else:
            color, label = AMBER, "연결 중…"
        width = tkfont.Font(family=self.fonts["regular"], size=10).measure(label) + 46
        self.rounded(x - width, 34, x, 72, 19, fill=mix(INK, color, 0.10), outline=mix(INK, color, 0.45), width=1,
                     tags=("dyn",))
        self.canvas.create_oval(x - width + 16, 49, x - width + 24, 57, fill=color, width=0, tags=("dyn",))
        self.text(x - width + 32, 53, label, size=10, fill=BODY)

        self.canvas.create_line(pad, 104, w - pad, 104, fill=mix(INK, TEXT, 0.10), tags=("dyn",))

    def _scenario_active(self, types, rain) -> bool:
        return self.online is True and set(self.lit) == set(types) and (self.wiper_mode != "Off") == rain

    def _draw_scenarios(self, x, y, w, h, ambient) -> None:
        self.panel(x, y, w, h, "SCENARIOS", ambient)
        row_h, gap = 48, 6
        top = y + 48
        for index, (name, description, types, rain, grade) in enumerate(SCENARIOS):
            ry = top + index * (row_h + gap)
            if ry + row_h > y + h - 66:
                break
            tag = f"scenario{index}"
            active = self._scenario_active(types, rain)
            color = RED if grade in ("3", "4") else AMBER
            outline = color if active else EDGE
            self.rounded(x + 16, ry, x + w - 16, ry + row_h, 16, width=1, outline=outline,
                         fill=mix(ROW, color, 0.16) if active else ROW, tags=("dyn", tag, tag + ":bg"))
            if active:  # grade-coloured edge light
                self.canvas.create_line(x + 19, ry + 12, x + 19, ry + row_h - 12, fill=color, width=3,
                                        capstyle="round", tags=("dyn", tag))
            self.text(x + 36, ry + 16, name, size=11, weight="medium", tags=("dyn", tag))
            self.text(x + 36, ry + 34, description, size=9, fill=MUTED, tags=("dyn", tag))
            # Expected grade, in a small dial ring.
            cx, cy, radius = x + w - 44, ry + row_h / 2, 15
            self.canvas.create_oval(cx - radius, cy - radius, cx + radius, cy + radius, width=1.5,
                                    outline=color if active else mix(ROW, color, 0.55), tags=("dyn", tag))
            self.text(cx, cy, grade, size=10 if len(grade) > 1 else 12, weight="light",
                      fill=color if active else mix(ROW, color, 0.85), anchor="center", tags=("dyn", tag))
            self.clickable(tag, lambda n=name, t=types, r=rain: self.run_scenario(n, t, r), outline,
                           mix(outline, TEXT, 0.45))
        self.pill(x + 16, y + h - 58, x + w - 16, y + h - 18, "모두 해제", "btn_clear", self.clear_all,
                  fill=mix(ROW, ACCENT, 0.12), outline=mix(ROW, ACCENT, 0.5), color=ACCENT, size=11)

    def _draw_faults(self, x, y, w, h, ambient) -> None:
        self.panel(x, y, w, h, "FAULT INJECTION", ambient)
        self.text(x + w - 24, y + 28, "불이 켜진 항목 = 차량이 실제로 보고하는 상태", size=9, fill=FAINT, anchor="e")
        columns, gap, row_h = 2, 10, 58
        cell_w = (w - 32 - gap * (columns - 1)) / columns
        top = y + 50
        for index, (label, console_type, color_name, effect) in enumerate(FAULTS):
            cx = x + 16 + (index % columns) * (cell_w + gap)
            cy = top + (index // columns) * (row_h + gap)
            tag = f"fault{index}"
            if console_type is None:
                on, color = self.wiper_mode != "Off", ACCENT
            else:
                on = console_type in self.lit
                color = LAMP.get(self.lit.get(console_type, {}).get("color") if on else color_name, RED)
            outline = color if on else EDGE
            if on:  # soft halo behind a lit switch
                for grow, strength in ((5, 0.10), (3, 0.20)):
                    self.rounded(cx - grow, cy - grow, cx + cell_w + grow, cy + row_h + grow, 16 + grow, width=0,
                                 fill=mix(mix(PANEL, ambient, 0.045), color, strength), tags=("dyn",))
            self.rounded(cx, cy, cx + cell_w, cy + row_h, 16, width=1, outline=outline,
                         fill=mix(ROW, color, 0.20) if on else ROW, tags=("dyn", tag, tag + ":bg"))
            dot_x, dot_y = cx + 24, cy + row_h / 2
            if on:
                self.canvas.create_oval(dot_x - 9, dot_y - 9, dot_x + 9, dot_y + 9, width=0,
                                        fill=mix(ROW, color, 0.45), tags=("dyn", tag))
            self.canvas.create_oval(dot_x - 5, dot_y - 5, dot_x + 5, dot_y + 5, width=0 if on else 1.5,
                                    fill=color if on else "", outline=mix(ROW, TEXT, 0.35), tags=("dyn", tag))
            self.text(cx + 44, cy + 20, label, size=11, weight="medium", tags=("dyn", tag))
            self.text(cx + 44, cy + 40, effect, size=9, fill=mix(MUTED, color, 0.35) if on else MUTED, tags=("dyn", tag))
            self.clickable(tag, lambda t=console_type: self.toggle(t), outline, mix(outline, TEXT, 0.45))

        # Any other telltale the console knows about.
        base = y + h - 64
        self.canvas.create_line(x + 24, base - 16, x + w - 24, base - 16, fill=mix(PANEL, TEXT, 0.07), tags=("dyn",))
        self.eyebrow(x + 24, base + 2, "OTHER TELLTALES")
        row_y = base + 30
        self.canvas.coords("input_type", x + 24, row_y)
        self.canvas.coords("input_color", x + 24 + 196, row_y)
        self.canvas.coords("input_state", x + 24 + 196 + 96, row_y)
        self.pill(x + w - 96, row_y - 17, x + w - 20, row_y + 17, "적용", "btn_apply", self.apply_custom)

    def _draw_state(self, x, y, w, h, ambient) -> None:
        self.panel(x, y, w, h, "VEHICLE STATE", ambient)
        canvas = self.canvas
        count = len(self.lit)
        cx, cy, radius = x + 78, y + 112, 46
        canvas.create_arc(cx - radius, cy - radius, cx + radius, cy + radius, start=-45, extent=270, style="arc",
                          width=5, outline=mix(PANEL, TEXT, 0.10), tags=("dyn",))
        fraction = 1.0 if count == 0 and self.online else min(count, 6) / 6
        if self.online is True and fraction > 0:
            for width, strength in ((11, 0.22), (5, 1.0)):   # glow, then the arc itself
                canvas.create_arc(cx - radius, cy - radius, cx + radius, cy + radius, start=225, extent=-270 * fraction,
                                  style="arc", width=width, outline=mix(mix(PANEL, ambient, 0.045), ambient, strength),
                                  tags=("dyn",))
        number = "–" if self.online is not True else ("OK" if count == 0 else str(count))
        self.text(cx, cy - 4, number, size=17 if number == "OK" else 26, weight="light", fill=ambient, anchor="center")
        self.eyebrow(cx, cy + 22, "ACTIVE" if count else "STATUS", anchor="center")

        tx = x + 146
        if self.online is not True:
            headline, detail = "연결 대기", "에뮬레이터를 찾는 중입니다"
        elif count == 0:
            headline, detail = "정상", "켜진 경고등이 없습니다"
        else:
            headline, detail = f"경고등 {count}개", "차량이 보고하는 상태입니다"
        self.text(tx, y + 96, headline, size=17, weight="light")
        self.text(tx, y + 124, detail, size=9, fill=MUTED)
        wiper_on = self.wiper_mode != "Off"
        self.text(tx, y + 146, f"앞 와이퍼  {self.wiper_mode}", size=9, fill=ACCENT if wiper_on else FAINT)

        canvas.create_line(x + 24, y + 184, x + w - 24, y + 184, fill=mix(PANEL, TEXT, 0.07), tags=("dyn",))
        self.eyebrow(x + 24, y + 206, "LIT TELLTALES")
        row_y = y + 232
        for name, value in sorted(self.lit.items()):
            if row_y + 30 > y + h - 16:
                self.text(x + 24, row_y + 4, "…", size=11, fill=MUTED)
                break
            color = LAMP.get(value.get("color"), RED)
            self.rounded(x + 16, row_y - 14, x + w - 16, row_y + 16, 15, fill=mix(ROW, color, 0.10),
                         outline=mix(ROW, color, 0.35), width=1, tags=("dyn",))
            canvas.create_oval(x + 30, row_y - 3, x + 38, row_y + 5, fill=color, width=0, tags=("dyn",))
            self.text(x + 48, row_y + 1, name, size=10, weight="medium")
            self.text(x + w - 30, row_y + 1, f"{value.get('state')} · {value.get('color')}", size=9, fill=MUTED, anchor="e")
            row_y += 38
        if not self.lit:
            self.text(x + 24, row_y, "없음", size=10, fill=FAINT)

    def _draw_log(self, x, y, w, h, ambient) -> None:
        self.panel(x, y, w, h, "LIVE LOG", ambient)
        self.text(x + w - 24, y + 28, "낮/밤 · 도로 조건은 앱 화면의 칩으로 바꿉니다", size=9, fill=FAINT, anchor="e")
        kinds = {"inject": ("주입", ACCENT), "vehicle": ("차량", OK), "info": ("정보", MUTED), "error": ("오류", RED)}
        line_h = 22
        rows = max(1, (h - 62) // line_h)
        ly = y + 56
        for moment, kind, message in reversed(self.entries[-rows:]):   # newest first
            tag, color = kinds.get(kind, ("정보", MUTED))
            self.text(x + 24, ly, moment, size=9, fill=FAINT)
            self.text(x + 96, ly, tag, size=9, weight="semi", fill=color)
            self.text(x + 138, ly, message, size=10, fill=BODY)
            ly += line_h

    def _make_inputs(self) -> None:
        """The three real widgets on the page: pickers for an arbitrary telltale."""
        style = ttk.Style(self)
        style.theme_use("clam")
        style.configure("Dark.TCombobox", fieldbackground=ROW, background=ROW, foreground=TEXT, arrowcolor=MUTED,
                        bordercolor=EDGE, lightcolor=ROW, darkcolor=ROW, padding=6)
        style.map("Dark.TCombobox", fieldbackground=[("readonly", ROW)], foreground=[("readonly", TEXT)],
                  bordercolor=[("focus", ACCENT)])
        self.option_add("*TCombobox*Listbox.background", ROW)
        self.option_add("*TCombobox*Listbox.foreground", TEXT)
        self.option_add("*TCombobox*Listbox.selectBackground", mix(ROW, ACCENT, 0.3))
        self.option_add("*TCombobox*Listbox.font", self.font(10))
        self.type_var = tk.StringVar()
        self.color_var = tk.StringVar(value="Red")
        self.state_var = tk.StringVar(value="On")
        self.type_box = ttk.Combobox(self, textvariable=self.type_var, state="readonly", width=20,
                                     font=self.font(10), style="Dark.TCombobox")
        color_box = ttk.Combobox(self, textvariable=self.color_var, state="readonly", width=8, font=self.font(10),
                                 style="Dark.TCombobox", values=["Red", "Yellow", "Green", "Blue", "White", "Gray"])
        state_box = ttk.Combobox(self, textvariable=self.state_var, state="readonly", width=6, font=self.font(10),
                                 style="Dark.TCombobox", values=["On", "Blink", "Off"])
        for tag, widget in (("input_type", self.type_box), ("input_color", color_box), ("input_state", state_box)):
            self.canvas.create_window(-500, -500, window=widget, anchor="w", tags=("input", tag))

    def _close(self) -> None:
        self.closing = True
        self.destroy()


def main() -> None:
    app = DemoConsoleApp()
    if "--smoke" in sys.argv:  # self-test: run briefly, save a picture of the window, exit (read-only)
        output = sys.argv[sys.argv.index("--smoke") + 1]

        def finish() -> None:
            from PIL import ImageGrab
            app.update()
            x, y = app.winfo_rootx(), app.winfo_rooty()
            ImageGrab.grab((x, y, x + app.winfo_width(), y + app.winfo_height()), all_screens=True).save(output)
            app._close()

        app.attributes("-topmost", True)
        app.after(5000, finish)
    app.mainloop()


if __name__ == "__main__":
    main()
