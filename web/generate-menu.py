#!/usr/bin/env python3
"""Export the desktop catalogue for the browser console menu."""
import json
from pathlib import Path
import re

source = Path("engine/src/wiiuu/core/Systems.java").read_text()
catalogue = source.split("List<GameSystem> ALL = List.of(", 1)[1].split(");", 1)[0]
systems = [
    dict(id=id, name=name, shortName=short, maker=maker, year=int(year), color="#" + color)
    for id, name, short, maker, year, color in re.findall(
        r'sys\("([^"]+)", "([^"]+)", "([^"]+)", "([^"]+)", (\d+), 0x([A-Fa-f0-9]{6})',
        catalogue,
    )
]
if len(systems) != catalogue.count('sys("'):
    raise SystemExit("Could not export every desktop console")
Path("docs/demo/ui/systems.mjs").write_text(
    "// Generated from Systems.java by web/generate-menu.py.\nexport const systems = "
    + json.dumps(systems, indent=2)
    + ";\n"
)
