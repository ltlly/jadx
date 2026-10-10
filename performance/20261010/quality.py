"""Inventory omitted method bodies, preserving per-method changes across runs."""
import json
from pathlib import Path
import re
import sys

OMITTED = re.compile(r'throw new UnsupportedOperationException\("Method not decompiled: (.*?)"\);')


def inventory(run, hashes, previous_hashes=None, previous=None):
    result = {}
    for name, digest in hashes.items():
        if not name.endswith('.java'):
            continue
        if previous_hashes is not None and previous_hashes.get(name) == digest:
            methods = previous.get(name, [])
        else:
            methods = OMITTED.findall((run / 'output' / name).read_text())
        if methods:
            result[name] = methods
    return result


if __name__ == '__main__':
    assert OMITTED.findall('throw new UnsupportedOperationException("Method not decompiled: A.test():void");') == ['A.test():void']
    assert not OMITTED.findall('throw new UnsupportedOperationException("unsupported");')
    reference = Path(sys.argv[1])
    hashes = json.loads((reference / 'manifest.json').read_text())
    original = inventory(reference, hashes)
    before = {(name, method) for name, methods in original.items() for method in methods}
    (reference / 'omitted-methods.json').write_text(json.dumps(original, indent=2))
    (reference / 'body-audit.json').write_text(json.dumps(dict(reference=str(reference), run=str(reference),
                                                             omitted_bodies=len(before), newly_omitted=[], restored=[]), indent=2))
    print(reference.name, 'omitted method bodies:', len(before))
    for argument in sys.argv[2:]:
        run = Path(argument)
        current_hashes = json.loads((run / 'manifest.json').read_text())
        current = inventory(run, current_hashes, hashes, original)
        after = {(name, method) for name, methods in current.items() for method in methods}
        report = dict(reference=str(reference), run=str(run), omitted_bodies=len(after),
                      newly_omitted=sorted(after-before), restored=sorted(before-after))
        (run / 'omitted-methods.json').write_text(json.dumps(current, indent=2))
        (run / 'body-audit.json').write_text(json.dumps(report, indent=2))
        print(run.name, len(after), 'newly omitted:', len(after-before), 'restored:', len(before-after))
