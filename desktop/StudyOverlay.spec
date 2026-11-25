# PyInstaller build for StudyOverlay.exe (one file). Leaves out Qt parts the overlay never loads, so the exe is
# smaller and each start unpacks less: software OpenGL, translations, image/icon plugins, network, PDF, QML.
from PyInstaller.utils.hooks import collect_data_files

DROP = ("opengl32sw.dll", "d3dcompiler", "qt6pdf", "qt6network", "qt6qml", "qt6quick", "qt6opengl", "qt6svg",
        "qt6virtualkeyboard", "/translations/", "plugins/imageformats/", "plugins/iconengines/",
        "plugins/networkinformation/", "plugins/tls/", "plugins/platforminputcontexts/", "plugins/generic/")


def keep(entry):
    name = entry[0].lower().replace("\\", "/")
    return not any(d in name for d in DROP)


a = Analysis(
    ["overlay.py"],
    hiddenimports=["tzdata"],                 # zoneinfo on Windows needs the tzdata package for calendar time zones
    datas=collect_data_files("tzdata"),
    excludes=["tkinter", "unittest", "pydoc", "doctest", "lib2to3", "sqlite3", "requests", "icalendar",
              "recurring_ical_events", "PySide6.QtNetwork", "PySide6.QtQml", "PySide6.QtQuick", "PySide6.QtOpenGL"],
    optimize=2,                               # no docstrings or asserts in the bundled code
)
a.binaries = [b for b in a.binaries if keep(b)]
a.datas = [d for d in a.datas if keep(d)]
pyz = PYZ(a.pure, optimize=2)
exe = EXE(pyz, a.scripts, a.binaries, a.datas, [], name="StudyOverlay", console=False, upx=False, strip=False)
