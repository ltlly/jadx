"""Portable quality primitives for macOS: streaming hashes, Java tokens, marker extraction.

Port of the Linux tooling (audit.py tokens / quality.py OMITTED) using pure stdlib,
memory-conscious over 70k-file output trees.
"""
import hashlib
import re
from pathlib import Path

# Same tokenization as performance/20261010/audit.py (regex tokenizer, no javalang)
TOKEN = re.compile(r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*.*?\*/|[\w$]+|>>>=|>>>|>>=|<<=|>>|<<|->|::|==|!=|<=|>=|&&|\|\||\+\+|--|[+*/%&|^=-]=|[^\s]', re.S)
# Same omitted-body marker as performance/20261010/quality.py
OMITTED = re.compile(r'throw new UnsupportedOperationException\("Method not decompiled: (.*?)"\);')
# In-file placeholder/diagnostic markers inserted by jadx
MARKER = re.compile(r'(JADX (?:ERROR|WARN):[^\n]*|Method dump skipped[^\n]*|Code decompiled incorrectly[^\n]*)')


def tokens(text):
    return tuple(t for t in TOKEN.findall(text) if not t.startswith(('/*', '//')))


def digest(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


def manifest(root):
    """Map every file under root to its sha256, keyed by relative POSIX path."""
    root = Path(root)
    result = {}
    for path in sorted(root.rglob('*')):
        if path.is_file():
            result[path.relative_to(root).as_posix()] = digest(path)
    return result


def scan_java(root, hashes=None):
    """One pass over .java files: in-file jadx markers and omitted method bodies.

    Returns (markers, omitted) where markers maps file -> sorted tuple of marker
    lines and omitted maps file -> sorted tuple of 'Method not decompiled' signatures.
    Only files with content are present. Reads each file once, text mode, lossy decode.
    """
    root = Path(root)
    markers, omitted = {}, {}
    for path in sorted(root.rglob('*')):
        if not path.is_file() or path.suffix != '.java':
            continue
        name = path.relative_to(root).as_posix()
        if hashes is not None and name not in hashes:
            continue
        text = path.read_text(errors='replace')
        found = tuple(sorted(MARKER.findall(text)))
        if found:
            markers[name] = found
        bodies = tuple(sorted(OMITTED.findall(text)))
        if bodies:
            omitted[name] = bodies
    return markers, omitted


def read_text_lossy(path):
    return Path(path).read_text(errors='replace')


def log_lines(path):
    """Extract (raw, normalized) ERROR/WARN lines from a jadx run log.

    Normalization strips the level prefix and replaces digit runs with '#',
    absorbing progress counters, error counts and timing noise that vary per run.
    """
    raw, normalized = set(), set()
    with Path(path).open(errors='replace') as stream:
        for line in stream:
            for match in re.finditer(r'(ERROR|WARN)\s+-\s+([^\n]*)', line):
                text = match.group(2).rstrip()
                raw.add(f'{match.group(1)} - {text}')
                normalized.add(f'{match.group(1)} - {re.sub(r"\d+", "#", text)}')
    return raw, normalized
