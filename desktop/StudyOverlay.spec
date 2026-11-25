# PyInstaller build for StudyOverlay.exe (one file). Leaves out Qt parts the overlay never loads, so the exe is
# smaller and each start unpacks less: software OpenGL, translations, image/icon plugins, network, PDF, QML.
import os

from PyInstaller.utils.hooks import collect_data_files

DROP = ("opengl32sw.dll", "d3dcompiler", "qt6pdf", "qt6network", "qt6qml", "qt6quick", "qt6opengl", "qt6svg",
        "qt6virtualkeyboard", "/translations/", "plugins/imageformats/", "plugins/iconengines/",
        "plugins/networkinformation/", "plugins/tls/", "plugins/platforminputcontexts/", "plugins/generic/")


def keep(entry):
    name = entry[0].lower().replace("\\", "/")
    return not any(d in name for d in DROP)


a = Analysis(
    ["run.py"],
    # zoneinfo on Windows needs the tzdata package; shiboken's start-up code needs __future__
    hiddenimports=["tzdata", "__future__"],
    datas=collect_data_files("tzdata"),
    # standard-library modules stay: PySide6 imports some of them at start-up (build 11 lost __future__ that way)
    excludes=["tkinter", "requests", "icalendar", "recurring_ical_events",
              "PySide6.QtNetwork", "PySide6.QtQml", "PySide6.QtQuick", "PySide6.QtOpenGL"],
)
a.binaries = [b for b in a.binaries if keep(b)]
a.datas = [d for d in a.datas if keep(d)]
pyz = PYZ(a.pure)
exe = EXE(pyz, a.scripts, a.binaries, a.datas, [], name=os.environ.get("EXE_NAME", "StudyOverlay"), console=os.environ.get("CONSOLE") == "1", upx=False, strip=False)
