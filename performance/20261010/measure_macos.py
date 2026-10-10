#!/usr/bin/env python3
"""Measure one jadx CLI decompile run on macOS; JSONL/TSV metrics export."""
import argparse
import csv
import hashlib
import json
import os
from pathlib import Path
import re
import resource
import subprocess
import sys
import time

SCHEMA = ['label', 'round', 'apk', 'apk_sha256', 'wall_s', 'user_s', 'sys_s',
          'rss_kib', 'exit', 'files', 'java_path', 'jvm_args', 'git_rev', 'timestamp']
TSV_COLUMNS = SCHEMA + ['command']


def digest(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


def git_rev():
    try:
        return subprocess.run(['git', 'rev-parse', 'HEAD'], capture_output=True,
                              text=True, check=True).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return ''


def count_java_files(root):
    return sum(1 for p in root.rglob('*') if p.is_file() and p.suffix == '.java')


def parse_time_output(text):
    """Parse '/usr/bin/time -l' stderr; macOS reports max RSS in bytes."""
    user = sys_time = real = None
    rss_kib = None
    match = re.search(r'^\s*([\d.]+)\s+real\s+([\d.]+)\s+user\s+([\d.]+)\s+sys\s*$', text, re.M)
    if match:
        real, user, sys_time = (float(value) for value in match.groups())
    match = re.search(r'^\s*(\d+)\s+maximum resident set size\s*$', text, re.M)
    if match:
        rss_kib = round(int(match[1]) / 1024)
    return real, user, sys_time, rss_kib


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jadx-bin', required=True, type=Path, help='jadx launcher script from an installDist distribution')
    parser.add_argument('--java', required=True, type=Path, help='java binary used for all timed runs')
    parser.add_argument('--jvm-args', required=True, help='JVM arguments quoted, e.g. "-Xmx12g"; identical for every timed run')
    parser.add_argument('--apk', required=True, type=Path)
    parser.add_argument('--out-dir', required=True, type=Path, help='decompile output directory (must not exist or be empty)')
    parser.add_argument('--label', required=True)
    parser.add_argument('--round', required=True, type=int)
    parser.add_argument('--jsonl', required=True, type=Path, help='JSONL metrics output file (appended)')
    parser.add_argument('--tsv', required=True, type=Path, help='summary TSV file (appended; header written when created)')
    parser.add_argument('--log-dir', required=True, type=Path, help='directory for run stdout/stderr capture')
    parser.add_argument('--timeout', type=int, default=3600, help='seconds before the run is killed')
    args = parser.parse_args()

    jadx_bin = args.jadx_bin.resolve()
    java = args.java.resolve()
    apk = args.apk.resolve()
    out_dir = args.out_dir.resolve()
    if not jadx_bin.is_file():
        sys.exit(f'jadx binary not found: {jadx_bin}')
    if not java.is_file():
        sys.exit(f'java binary not found: {java}')
    if not apk.is_file():
        sys.exit(f'APK not found: {apk}')
    if out_dir.exists() and any(out_dir.iterdir()):
        sys.exit(f'output directory not empty: {out_dir}')
    out_dir.mkdir(parents=True, exist_ok=True)

    for name, path in [('jsonl', args.jsonl), ('tsv', args.tsv)]:
        Path(path).resolve().parent.mkdir(parents=True, exist_ok=True)
    log_dir = args.log_dir.resolve()
    log_dir.mkdir(parents=True, exist_ok=True)

    jvm_args = args.jvm_args.split()
    jadx_args = ['--no-res', '--threads-count', '8', '-d', str(out_dir)]
    command = ['/usr/bin/time', '-l', str(jadx_bin)] + jadx_args + [str(apk)]

    environment = {key: value for key, value in os.environ.items()
                   if key not in ('JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS', 'JAVA_OPTS', 'JADX_OPTS')}
    environment['JAVA_OPTS'] = ' '.join(jvm_args)
    environment['JAVA_HOME'] = str(java.parent.parent)  # launcher resolves java from JAVA_HOME

    stdout_path = log_dir / f'{args.label}-r{args.round}-stdout.txt'
    stderr_path = log_dir / f'{args.label}-r{args.round}-stderr.txt'
    started = time.strftime('%Y-%m-%dT%H:%M:%S%z')
    before = resource.getrusage(resource.RUSAGE_CHILDREN).ru_maxrss
    begin = time.monotonic()
    with stdout_path.open('w') as stdout, stderr_path.open('w') as stderr:
        try:
            completed = subprocess.run(command, stdout=stdout, stderr=stderr,
                                       env=environment, timeout=args.timeout)
            exit_code = completed.returncode
            timed_out = False
        except subprocess.TimeoutExpired:
            exit_code = -9
            timed_out = True
    wall_s = time.monotonic() - begin
    time_text = stderr_path.read_text(errors='replace')
    real, user_s, sys_s, rss_kib = parse_time_output(time_text)
    rss_source = 'time-l'
    if rss_kib is None:
        # macOS ru_maxrss is in bytes
        after = resource.getrusage(resource.RUSAGE_CHILDREN).ru_maxrss
        rss_kib = round(max(after, before) / 1024)
        rss_source = 'getrusage'
    if real is None:
        real = wall_s
    if user_s is None:
        user_s = 0.0
    if sys_s is None:
        sys_s = 0.0

    files = sum(1 for _ in out_dir.rglob('*') if _.is_file())
    record = {
        'label': args.label,
        'round': args.round,
        'apk': str(apk),
        'apk_sha256': digest(apk),
        'wall_s': round(wall_s, 3),
        'user_s': round(user_s, 3),
        'sys_s': round(sys_s, 3),
        'rss_kib': rss_kib,
        'exit': exit_code,
        'files': files,
        'java_path': str(java),
        'jvm_args': ' '.join(jvm_args),
        'git_rev': git_rev(),
        'timestamp': started,
        'command': ' '.join(command),
        'rss_source': rss_source,
        'timed_out': timed_out,
    }
    with args.jsonl.open('a') as stream:
        stream.write(json.dumps({key: record[key] for key in SCHEMA}) + '\n')
    exists = args.tsv.exists()
    with args.tsv.open('a', newline='') as stream:
        writer = csv.DictWriter(stream, fieldnames=TSV_COLUMNS, delimiter='\t', lineterminator='\n',
                                extrasaction='ignore')
        if not exists:
            writer.writeheader()
        writer.writerow(record)
    print(json.dumps({key: record[key] for key in SCHEMA}), flush=True)


if __name__ == '__main__':
    main()
