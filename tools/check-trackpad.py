#!/usr/bin/python3
"""After a confirmed hands-off window, measure phone trackpad motion through KWin.

Requires the fixed app already streaming in trackpad + remote desktop mouse mode.
This deliberately drives the phone touchscreen. It never installs or launches an
app, changes orientation, restarts a service, or selects another ADB device.
Run once in each orientation and at each desktop scale, reconnecting manually.
"""
import argparse
import json
from pathlib import Path
import queue
import re
import subprocess
import tempfile
import threading
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="<phone-ip>:<port> of the phone, from adb mdns services")
    parser.add_argument("--width", required=True, type=int, help="Current fullscreen stream/view width")
    parser.add_argument("--height", required=True, type=int, help="Current fullscreen stream/view height")
    parser.add_argument("--scale", required=True, type=float, help="Actual KWin output scale (for example 2.5)")
    parser.add_argument("--hands-off-confirmed", action="store_true", help="Run only after the user confirms the phone is hands-off")
    args = parser.parse_args()
    if not args.hands_off_confirmed:
        parser.error("Explicit --hands-off-confirmed is required: this sends touchscreen gestures")
    if not re.fullmatch(r"[0-9A-Za-z.\-]+:[0-9]+", args.serial):
        parser.error("Expected --serial <phone-ip>:<port>")
    if not 1 <= int(args.serial.rsplit(":", 1)[1]) <= 65535:
        parser.error("Invalid ADB port")
    if min(args.width, args.height) < 600 or not 1 <= args.scale <= 4:
        parser.error("Expected a fullscreen view of at least 600px per axis and scale 1–4")

    import dbus
    import dbus.service
    from dbus.mainloop.glib import DBusGMainLoop
    from gi.repository import GLib

    def run(*command):
        return subprocess.run(command, check=True, capture_output=True, text=True, timeout=15).stdout.strip()

    adb = str(Path.home() / "Android/Sdk/platform-tools/adb")
    if run(adb, "-s", args.serial, "get-state") != "device":
        raise RuntimeError("The selected phone is not available")
    # Refuse to swipe a different application accidentally. No UI dump is saved.
    focus = run(adb, "-s", args.serial, "shell", "dumpsys", "window", "windows")
    if not any("mCurrentFocus=" in line and "com.limelight.puig/" in line and
               ("/com.limelight.Game" in line or "/.Game" in line) for line in focus.splitlines()):
        raise RuntimeError("Moonlight Puig's stream activity is not focused; open the stream manually")
    DBusGMainLoop(set_as_default=True)
    bus = dbus.SessionBus()
    positions = queue.Queue()

    class Reporter(dbus.service.Object):
        def __init__(self):
            self.name = dbus.service.BusName("org.sunshine.TrackpadMeasurement", bus, do_not_queue=True)
            super().__init__(bus, "/TrackpadMeasurement")

        @dbus.service.method("org.sunshine.TrackpadMeasurement", in_signature="dd", out_signature="")
        def Position(self, x, y):
            positions.put((float(x), float(y)))

    reporter = Reporter()
    loop = GLib.MainLoop()
    dispatch = threading.Thread(target=loop.run)
    dispatch.start()
    name = "sunshine-trackpad-measurement"
    try:
        with tempfile.TemporaryDirectory(prefix="trackpad-check-") as directory:
            script = Path(directory) / "cursor.js"
            script.write_text('callDBus("org.sunshine.TrackpadMeasurement","/TrackpadMeasurement","org.sunshine.TrackpadMeasurement","Position",workspace.cursorPos.x,workspace.cursorPos.y);')

            def cursor():
                index = run("qdbus6", "org.kde.KWin", "/Scripting", "org.kde.kwin.Scripting.loadScript", str(script), name)
                try:
                    run("qdbus6", "org.kde.KWin", "/Scripting/Script" + index, "org.kde.kwin.Script.run")
                    return positions.get(timeout=5)
                finally:
                    run("qdbus6", "org.kde.KWin", "/Scripting", "org.kde.kwin.Scripting.unloadScript", name)

            x, y = args.width // 3, args.height // 3
            measurements = []
            for duration in (80, 500):
                for dx, dy in ((100, 0), (-100, 0), (0, 100), (0, -100), (100, 100), (-100, -100)):
                    before = cursor()
                    run(adb, "-s", args.serial, "shell", "input", "swipe", str(x), str(y), str(x + dx), str(y + dy), str(duration))
                    time.sleep(.25)
                    after = cursor()
                    delta = tuple(b - a for a, b in zip(before, after))
                    expected = (dx / args.scale, dy / args.scale)
                    passed = all(abs(actual - target) <= 2 for actual, target in zip(delta, expected))
                    result = {"finger_delta_pixels": [dx, dy], "duration_ms": duration,
                              "cursor_delta_logical": delta, "expected_logical": expected, "pass": passed}
                    measurements.append(result)
                    print(json.dumps(result), flush=True)
            if not all(result["pass"] for result in measurements):
                raise RuntimeError("Motion differs from 1:1; check cursor edge clipping, view dimensions and actual scale")
    finally:
        loop.quit()
        dispatch.join(timeout=5)


if __name__ == "__main__":
    main()
