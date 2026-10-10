"""Export completed measurements and three-pair statistics as TSV files."""
import argparse
import collections
import csv
import json
from pathlib import Path
import re
import statistics


def stats(values):
    return statistics.median(values), min(values), max(values)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('roots', nargs='+', type=Path, help='Directories containing run directories, without recursive output scans')
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    rows = []
    groups = collections.defaultdict(lambda: collections.defaultdict(list))
    for root in args.roots:
        for file in sorted(root.glob('*/metrics.json')):
            run = file.parent
            metric = json.loads(file.read_text())
            metadata = json.loads((run / 'metadata.json').read_text())
            command = metadata['command']
            distribution = Path(command[command.index('-cp') + 1]).parent.parent.name
            errors = re.findall(r'finished with errors, count: (\d+)', (run / 'stdout.txt').read_text(errors='replace'))
            body = run / 'body-audit.json'
            omitted = json.loads(body.read_text())['omitted_bodies'] if body.exists() else ''
            kind = 'profile' if metadata['profiled'] else 'shadow' if 'shadow' in distribution else 'benchmark'
            row = dict(run=str(run), distribution=distribution, apk=Path(command[-1]).name,
                       apk_sha256=metadata['apk_sha256'], started=metadata['started'], kind=kind,
                       wall_s=metric['wall_s'], user_s=metric['user_s'], sys_s=metric['sys_s'],
                       cpu_s=round(metric['user_s'] + metric['sys_s'], 2), rss_kib=int(metric['rss_kib']),
                       exit=metric['exit'], errors=int(errors[-1]) if errors else 0 if metric['exit'] == 0 else '',
                       files=metric['files'], java_files=metric['java_files'], omitted_bodies=omitted)
            rows.append(row)
            match = re.fullmatch(r'(.+)-([AB])([1-3])', run.name)
            if kind == 'benchmark' and match:
                groups[(str(root), match[1])][match[2]].append(row)
    with args.output.open('w') as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]), delimiter='\t', lineterminator='\n')
        writer.writeheader()
        writer.writerows(rows)
    summary = []
    for (root, name), versions in sorted(groups.items()):
        assert len(versions['A']) == len(versions['B']) == 3, name
        row = dict(root=root, cohort=name, baseline=versions['A'][0]['distribution'], optimized=versions['B'][0]['distribution'])
        for label in 'AB':
            for measure in ['wall_s', 'cpu_s', 'rss_kib']:
                values = [item[measure] for item in versions[label]]
                for suffix, value in zip(['median', 'min', 'max'], stats(values)):
                    row[f'{label}_{measure}_{suffix}'] = value
        row['wall_reduction_percent'] = 100 * (1 - row['B_wall_s_median'] / row['A_wall_s_median'])
        row['cpu_reduction_percent'] = 100 * (1 - row['B_cpu_s_median'] / row['A_cpu_s_median'])
        row['rss_change_percent'] = 100 * (row['B_rss_kib_median'] / row['A_rss_kib_median'] - 1)
        summary.append(row)
    with args.output.with_suffix('.summary.tsv').open('w') as stream:
        writer = csv.DictWriter(stream, fieldnames=list(summary[0]), delimiter='\t', lineterminator='\n')
        writer.writeheader()
        writer.writerows(summary)
    print(f'Exported {len(rows)} runs and {len(summary)} complete A/B cohorts')


if __name__ == '__main__':
    assert stats([3, 1, 2]) == (2, 1, 3)
    main()
