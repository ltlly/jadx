"""Retain exact output differences and separate comments from Java token changes."""
import difflib
import json
from pathlib import Path
import re
import sys

TOKEN = re.compile(r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*.*?\*/|[\w$]+|>>>=|>>>|>>=|<<=|>>|<<|->|::|==|!=|<=|>=|&&|\|\||\+\+|--|[+*/%&|^=-]=|[^\s]', re.S)


def tokens(text):
    return tuple(t for t in TOKEN.findall(text) if not t.startswith(('/*', '//')))


def audit(left, right, destination):
    a = json.loads((left / 'manifest.json').read_text())
    b = json.loads((right / 'manifest.json').read_text())
    changed = sorted(name for name in a.keys() & b.keys() if a[name] != b[name])
    report = dict(left=str(left), right=str(right), missing=sorted(a.keys()-b.keys()),
                  extra=sorted(b.keys()-a.keys()), byte_identical=len(a.keys() & b.keys())-len(changed),
                  changed=changed, comment_only=[], java_tokens_changed=[], error_changes={})
    with Path(str(destination) + '.diff').open('w') as diff:
        for name in changed:
            aa = (left / 'output' / name).read_text()
            bb = (right / 'output' / name).read_text()
            bucket = 'comment_only' if tokens(aa) == tokens(bb) else 'java_tokens_changed'
            report[bucket].append(name)
            ae = re.findall(r'JADX ERROR:[^\n]*', aa)
            be = re.findall(r'JADX ERROR:[^\n]*', bb)
            if ae != be:
                report['error_changes'][name] = dict(before=ae, after=be)
            diff.writelines(difflib.unified_diff(aa.splitlines(True), bb.splitlines(True),
                                                str(left / 'output' / name), str(right / 'output' / name)))
    Path(str(destination) + '.json').write_text(json.dumps(report, indent=2))
    print(json.dumps({k: len(v) if isinstance(v, (list, dict)) else v for k, v in report.items()}, indent=2))


if __name__ == '__main__':
    assert tokens('a /* text */ + "/* value */"') == tokens('a+"/* value */"')
    assert tokens('a + +b') != tokens('a++b')
    assert tokens('public class A') != tokens('publicclass A')
    audit(Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3]))
