"""Small checks for the benchmark's hashing, diff triage and streamed JFR parser."""
import io
import json
from pathlib import Path
import tempfile

from audit import audit, tokens
from measure import manifest
from quality import OMITTED
from summarize_profile import iter_events, summarize

with tempfile.TemporaryDirectory() as directory:
    root = Path(directory)
    (root / 'nested').mkdir()
    (root / 'nested/hello.java').write_bytes(b'abc')
    assert manifest(root) == {'nested/hello.java': 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad'}
    for version, text in [('A', 'int a;'), ('B', 'int b;')]:
        run = root / version
        (run / 'output').mkdir(parents=True)
        (run / 'output/Test.java').write_text(text)
        (run / 'manifest.json').write_text(json.dumps(manifest(run / 'output')))
    destination = root / 'app-1.2-audit-1'
    audit(root / 'A', root / 'B', destination)
    report = json.loads((root / 'app-1.2-audit-1.json').read_text())
    assert report['java_tokens_changed'] == ['Test.java']
    assert not report['missing'] and not report['extra']
    assert (root / 'app-1.2-audit-1.diff').read_text().endswith('+int b;')

assert tokens('a /* ignored */ + "/* literal */"') == tokens('a+"/* literal */"')
assert tokens('a + + b') != tokens('a++b')
assert tokens('int a') != tokens('inta')
assert OMITTED.findall('throw new UnsupportedOperationException("Method not decompiled: A.test():void");') == ['A.test():void']

frame = {'method': {'type': {'name': 'a/b'}, 'name': 'recursive'}}
event = {'type': 'jdk.ExecutionSample', 'values': {'stackTrace': {'frames': [frame, frame]}}}
document = json.dumps({'recording': {'events': [event, event]}})
result = summarize(iter_events(io.StringIO(document)))
assert result['execution_samples'] == 2
assert result['self_samples'] == [('a.b.recursive', 2)]
assert result['inclusive_samples'] == [('a.b.recursive', 2)]
print('Benchmark utility checks passed')
