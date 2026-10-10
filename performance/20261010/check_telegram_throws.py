"""Check declaration compatibility only; this does not compile the APK bodies."""
from pathlib import Path
import hashlib
import json
import re
import subprocess
import sys

assert len(sys.argv) == 5, 'baseline-run candidate-run javac destination required'
reference, candidate, java, dest = (Path(value).resolve() for value in sys.argv[1:])
assert not dest.exists()


def declaration(text):
    matches = re.findall(r'public (?:final )?void b\(\)(?: throws [^{]+)?\s*\{', text)
    assert len(matches) == 1, matches
    return matches[0].strip()


assert declaration('public void b() {}') == 'public void b() {'
assert declaration('public final void b() throws la.c {}') == 'public final void b() throws la.c {'
results = []
for label, input_run in [('A3', reference), ('B3', candidate)]:
    sources = input_run / 'output/sources'
    paths = {name: sources / name for name in ['ga/l.java', 'la/a.java', 'la/c.java']}
    texts = {name: path.read_text() for name, path in paths.items()}
    parent = declaration(texts['la/a.java'])
    child = declaration(texts['ga/l.java'])
    assert parent == 'public void b() {'
    assert re.search(r'public final class c extends IOException', texts['la/c.java'])
    assert child == ('public final void b() {' if label == 'A3' else 'public final void b() throws la.c {')
    run = dest / label
    (run / 'src/la').mkdir(parents=True)
    (run / 'src/ga').mkdir()
    (run / 'classes').mkdir()
    (run / 'src/la/a.java').write_text('package la; public class a { ' + parent + '} }\n')
    (run / 'src/la/c.java').write_text('package la; public final class c extends java.io.IOException {}\n')
    (run / 'src/ga/l.java').write_text('package ga; public final class l extends la.a { @Override ' + child + '} }\n')
    command = [str(java), '-J-Duser.language=en', '-J-Duser.country=US', '--release', '11',
               '-d', str(run / 'classes')] + [str(p) for p in sorted((run / 'src').rglob('*.java'))]
    result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    (run / 'javac.txt').write_text(result.stdout)
    assert result.returncode == (0 if label == 'A3' else 1), result.stdout
    if label == 'B3':
        assert 'overridden method does not throw c' in result.stdout, result.stdout
    results.append(dict(run=label, parent=parent, child=child, exit=result.returncode, command=command,
                        evidence={name: dict(path=str(paths[name]),
                                             sha256=hashlib.sha256(texts[name].encode()).hexdigest()) for name in paths},
                        compiler_output=result.stdout))
report = dict(scope='Extracted declarations and hierarchy only; APK bodies are not compiled', results=results)
(dest / 'result.json').write_text(json.dumps(report, indent=2))
print(json.dumps(results, indent=2))
