"""Runs on the Windows build machine before packaging: the Windows-only parts must work there.

- the overlay starts click-through (WS_EX_TRANSPARENT set),
- holding Ctrl (interactive) clears it and letting go sets it again,
- the full-screen check runs without errors.
"""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import overlay as o  # noqa: E402
from PySide6.QtWidgets import QApplication  # noqa: E402

app = QApplication([])
st = o.State(demo=True)
ov = o.Overlay(st)
ov.place()
ov.show()
app.processEvents()
u = o._user32()["u"]
style = lambda: u.GetWindowLongPtrW(int(ov.winId()), -20)

assert style() & 0x20, f"not click-through at start: {style():#x}"
ov.set_interactive(True)
app.processEvents()
assert not style() & 0x20, f"still click-through while interactive: {style():#x}"
ov.set_interactive(False)
app.processEvents()
assert style() & 0x20, f"not click-through after Ctrl: {style():#x}"
ov.set_move(True)
app.processEvents()
assert not style() & 0x20, "click-through while moving"
ov.set_move(False)
app.processEvents()
assert style() & 0x20, "not click-through after moving"
ov.keep_on_top()
app.processEvents()
assert style() & 0x8, f"not topmost after keep_on_top: {style():#x}"   # WS_EX_TOPMOST
assert style() & 0x20, "keep_on_top broke click-through"
print("fullscreen:", o._fullscreen_check(), "ctrl:", o.ctrl_down())
print("win_check ok")
