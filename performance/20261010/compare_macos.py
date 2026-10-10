#!/usr/bin/env python3
"""Summarize measure_macos.py JSONL: per-label medians/ranges and pairwise wall deltas."""
import argparse
import collections
import itertools
import json
from pathlib import Path
import statistics

FIELDS = ['label', 'round', 'apk', 'apk_sha256', 'wall_s', 'user_s', 'sys_s',
          'rss_kib', 'exit', 'files', 'java_path', 'jvm_args', 'git_rev', 'timestamp']


def load(path):
    rows = []
    with Path(path).open() as stream:
        for line in stream:
            line = line.strip()
            if line:
                rows.append(json.loads(line))
    return rows


def check(row):
    missing = [field for field in FIELDS if field not in row]
    if missing:
        raise SystemExit(f'missing fields {missing} in record: {row}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('jsonl', type=Path, help='JSONL file written by measure_macos.py')
    args = parser.parse_args()
    rows = load(args.jsonl)
    if not rows:
        raise SystemExit('no records')
    for row in rows:
        check(row)

    groups = collections.defaultdict(list)
    for row in rows:
        groups[row['label']].append(row)

    print(f'records: {len(rows)} from {args.jsonl}')
    print()
    print(f'{"label":<16} {"n":>2} {"wall_s median":>14} {"wall_s min-max":>24} '
          f'{"rss_kib median":>15} {"rss_kib min-max":>18} {"exit":>10} {"files":>8}')
    for label, items in sorted(groups.items()):
        wall = [row['wall_s'] for row in items]
        rss = [row['rss_kib'] for row in items]
        exits = ','.join(str(row['exit']) for row in items)
        print(f'{label:<16} {len(items):>2} {statistics.median(wall):>14.3f} '
              f'{min(wall):>10.3f}-{max(wall):<13.3f} {statistics.median(rss):>15.0f} '
              f'{min(rss):>8.0f}-{max(rss):<9.0f} {exits:>10} {items[0]["files"]:>8}')

    print()
    print('pairwise wall_s deltas (median vs median):')
    for left, right in itertools.combinations(sorted(groups), 2):
        a = statistics.median(row['wall_s'] for row in groups[left])
        b = statistics.median(row['wall_s'] for row in groups[right])
        delta = b - a
        percent = 100 * delta / a if a else float('nan')
        print(f'  {left} -> {right}: {delta:+.3f}s ({percent:+.1f}%)')

    jvm = {row['jvm_args'] for row in rows}
    java = {row['java_path'] for row in rows}
    if len(jvm) > 1 or len(java) > 1:
        print()
        print(f'WARNING: mixed java binaries {sorted(java)} or JVM args {sorted(jvm)}')


if __name__ == '__main__':
    main()
