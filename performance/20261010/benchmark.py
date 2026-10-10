"""Run three serial, interleaved A/B pairs for selected pinned APKs."""
import argparse
import csv
import json
from pathlib import Path
import subprocess
import sys

from measure import digest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('baseline', type=Path)
    parser.add_argument('optimized', type=Path)
    parser.add_argument('corpus', type=Path)
    parser.add_argument('destination', type=Path)
    parser.add_argument('--names', nargs='+', help='Names from corpus.tsv; default: all ten APKs')
    args = parser.parse_args()
    scripts = Path(__file__).resolve().parent
    with (scripts / 'corpus.tsv').open() as stream:
        rows = list(csv.DictReader(stream, delimiter='\t'))
    by_name = {row['name']: row for row in rows}
    assert len(by_name) == len(rows)
    names = args.names or list(by_name)
    if set(names) - by_name.keys():
        parser.error('Unknown APK name')
    destination = args.destination.resolve()
    destination.mkdir(parents=True, exist_ok=True)
    for name in names:
        assert Path(name).name == name
        row = by_name[name]
        apk = args.corpus / (name + '.apk')
        if digest(apk) != row['sha256']:
            raise ValueError('APK checksum differs from corpus.tsv: ' + name)
        runs = []
        for iteration in range(1, 4):
            for label, distribution in [('A', args.baseline), ('B', args.optimized)]:
                run = destination / f'{name}-{label}{iteration}'
                subprocess.run([sys.executable, str(scripts / 'measure.py'), str(distribution),
                                str(apk), str(run)], check=True)
                metrics = json.loads((run / 'metrics.json').read_text())
                metadata = json.loads((run / 'metadata.json').read_text())
                if metadata['apk_sha256'] != row['sha256'] or not metrics['java_files'] or metrics['exit'] not in (0, 3):
                    raise RuntimeError('Invalid performance run; retained for inspection: ' + str(run))
                runs.append(run)
            subprocess.run([sys.executable, str(scripts / 'audit.py'),
                            str(destination / f'{name}-A{iteration}'),
                            str(destination / f'{name}-B{iteration}'),
                            str(destination / f'{name}-audit-{iteration}')], check=True)
        subprocess.run([sys.executable, str(scripts / 'quality.py')] + [str(run) for run in runs], check=True)
        for label in 'AB':
            for iteration in (2, 3):
                subprocess.run([sys.executable, str(scripts / 'audit.py'),
                                str(destination / f'{name}-{label}1'),
                                str(destination / f'{name}-{label}{iteration}'),
                                str(destination / f'{name}-{label}-repeat-{iteration}')], check=True)


if __name__ == '__main__':
    main()
