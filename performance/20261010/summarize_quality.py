"""Summarize six-run quality evidence; retain anomalies for manual review."""
import argparse
import csv
import hashlib
import json
from pathlib import Path
import re

from audit import tokens


def read(path):
    return json.loads(path.read_text())


def interval(values):
    return f'{min(values)}..{max(values)}'


def omitted(run):
    return {(name, method) for name, methods in read(run / 'omitted-methods.json').items()
            for method in methods}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('runs', type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--names', nargs='+', help='Review selected complete APK groups; default: all ten')
    args = parser.parse_args()
    with Path(__file__).with_name('corpus.tsv').open() as stream:
        names = [row['name'] for row in csv.DictReader(stream, delimiter='\t')]
    if args.names:
        assert set(args.names) <= set(names), 'Unknown APK name'
        names = args.names
    rows, details = [], {}
    for name in names:
        runs = {label: [args.runs / f'{name}-{label}{i}' for i in range(1, 4)] for label in 'AB'}
        metrics = {label: [read(run / 'metrics.json') for run in runs[label]] for label in 'AB'}
        bodies = {label: [omitted(run) for run in runs[label]] for label in 'AB'}
        pairs = [read(args.runs / f'{name}-audit-{i}.json') for i in range(1, 4)]
        repeats = {label: [read(args.runs / f'{name}-{label}-repeat-{i}.json') for i in (2, 3)]
                   for label in 'AB'}
        row = dict(apk=name)
        for label in 'AB':
            row[f'{label}_exit'] = ','.join(str(metric['exit']) for metric in metrics[label])
            counts = []
            for run, metric in zip(runs[label], metrics[label]):
                found = re.findall(r'finished with errors, count: (\d+)', (run / 'stdout.txt').read_text())
                assert found or metric['exit'] == 0, f'Missing error summary: {run}'
                counts.append(int(found[-1]) if found else 0)
            row[f'{label}_errors'] = interval(counts)
            row[f'{label}_java_files'] = interval([metric['java_files'] for metric in metrics[label]])
            row[f'{label}_omitted'] = interval([len(body) for body in bodies[label]])
            row[f'{label}_repeat_token_files'] = interval([len(audit['java_tokens_changed'])
                                                          for audit in repeats[label]])
        for key in ['missing', 'extra', 'changed', 'java_tokens_changed', 'error_changes']:
            row[f'pair_{key}'] = interval([len(audit[key]) for audit in pairs])
        stable_omitted = set.intersection(*bodies['B']) - set.union(*bodies['A'])
        any_omitted = set.union(*bodies['B']) - set.union(*bodies['A'])
        row['B_stable_new_omitted'] = len(stable_omitted)
        row['B_any_new_omitted'] = len(any_omitted)
        candidates = set.union(*(set(audit['changed']) for audit in pairs))
        novel_tokens, stable_tokens, novel_errors, novel_warnings = [], [], {}, {}
        for filename in sorted(candidates):
            variants, errors, warnings = {}, {}, {}
            for label in 'AB':
                texts = [(run / 'output' / filename).read_text() for run in runs[label]]
                variants[label] = {hashlib.sha256(repr(tokens(text)).encode()).hexdigest() for text in texts}
                errors[label] = {tuple(re.findall(r'JADX ERROR:[^\n]*', text)) for text in texts}
                warnings[label] = {tuple(re.findall(r'JADX WARN:[^\n]*', text)) for text in texts}
            if variants['B'] - variants['A']:
                novel_tokens.append(filename)
            if len(variants['A']) == len(variants['B']) == 1 and variants['A'] != variants['B']:
                stable_tokens.append(filename)
            extra_errors = errors['B'] - errors['A']
            if extra_errors:
                novel_errors[filename] = dict(A=sorted(errors['A']), B=sorted(errors['B']))
            if warnings['B'] - warnings['A']:
                novel_warnings[filename] = dict(A=sorted(warnings['A']), B=sorted(warnings['B']))
        row['B_novel_token_files'] = len(novel_tokens)
        row['stable_different_token_files'] = len(stable_tokens)
        row['B_novel_error_variants'] = len(novel_errors)
        row['B_novel_warning_variants'] = len(novel_warnings)
        rows.append(row)
        details[name] = dict(stable_new_omitted=sorted(stable_omitted),
                             any_new_omitted=sorted(any_omitted),
                             novel_token_files=novel_tokens, stable_different_token_files=stable_tokens,
                             novel_error_variants=novel_errors, novel_warning_variants=novel_warnings)
    with args.output.open('w') as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]), delimiter='\t', lineterminator='\n')
        writer.writeheader()
        writer.writerows(rows)
    args.output.with_suffix('.details.json').write_text(json.dumps(details, indent=2))
    print(f'Exported quality evidence for {len(rows)} APKs; novel variants require review, not automatic acceptance')


if __name__ == '__main__':
    main()
