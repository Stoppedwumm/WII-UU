#!/usr/bin/env python3
"""Generate the browser pixel filters from GameScreen.java, preserving algorithms.

This intentionally handles only the small pure-integer subset used by these
methods. Fail closed on remaining Java constructs; parity tests compare output
against the Java implementation. Browser-specific drawing lives in ui/library.mjs.
"""
from pathlib import Path
import re
source = Path('engine/src/wiiuu/engine/GameScreen.java').read_text()
source = source[source.index('    private static int dist('):source.index('    /** Whether a game picture')]
source = re.sub(r'/\*.*?\*/', '', source, flags=re.S)
source = re.sub(r'//[^\n]*', '', source)
def function(match):
    params = re.sub(r'\b(?:int\[\]|int|boolean)\s+', '', match[2])
    return f'export function {match[1]}({params}) {{'
source = re.sub(r'(?:private )?static (?:int|void) (\w+)\((.*?)\)\s*\{', function, source, flags=re.S)
source = re.sub(r'new int\[\]\{([^}]+)\}', r'[\1]', source)
source = re.sub(r'new int\[([^\]]+)\]', r'new Uint32Array(\1)', source)
source = re.sub(r'\b(?:int\[\]|int|boolean)\s+', 'let ', source)
source = re.sub(r'\bin\b', 'input', source)
source = source.replace('rows(h, y -> {', 'rows(h, y => {')
source = re.sub(r"\byuv(?=\()", "toYuv", source)  # Java separates method and local names.
for name in ('y', 'u', 'v'):
    source = re.sub(rf'let {name} = (.*?);', rf'let {name} = Math.trunc(\1);', source, count=1)
if re.search(r'\b(?:private|static|int|boolean|new int)\b|->', source):
    raise SystemExit('Untranslated Java in filters; update generator and parity tests')
Path('docs/demo/ui/filters.mjs').write_text('// Generated from engine/src/wiiuu/engine/GameScreen.java; do not edit.\n'
    + 'function rows(h, fn) { for (let y = 0; y < h; y++) fn(y); }\n' + source)
