#!/usr/bin/env python3
"""Measure one complete CLI run; hashing and output auditing are outside the timer."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import time


def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


def manifest(root):
    return {str(p.relative_to(root)): digest(p) for p in sorted(root.rglob('*')) if p.is_file()}


def cpu_snapshot():
    result = {}
    for path in Path('/proc').glob('[0-9]*/stat'):
        try:
            text = path.read_text()
            tail = text.rsplit(')', 1)[1].split()
            result[path.parent.name] = (text.split('(', 1)[1].rsplit(')', 1)[0], int(tail[11]) + int(tail[12]))
        except (OSError, ValueError):
            pass
    return time.monotonic(), result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('distribution', type=Path)
    parser.add_argument('apk', type=Path)
    parser.add_argument('run', type=Path)
    parser.add_argument('--profile', action='store_true')
    parser.add_argument('--dependency-profile', action='store_true',
                        help='Collect dependency processing diagnostics, not a formal performance run')
    args = parser.parse_args()
    run = args.run.resolve()
    run.mkdir(parents=True, exist_ok=False)
    java = '/usr/lib/jvm/java-27-openjdk/bin/java'
    command = [java, '-XX:+IgnoreUnrecognizedVMOptions', '-Xms256m', '-Xmx16g',
               '-XX:ParallelGCThreads=3', '-Djdk.util.zip.disableZip64ExtraFieldValidation=true',
               '--enable-native-access=ALL-UNNAMED', '-Dfile.encoding=UTF-8',
               '-Duser.language=en', '-Duser.country=US']
    if args.dependency_profile:
        command += ['-Djadx.deps.profile=true',
                    f'-Djadx.deps.profile.output={run}/dependency-profile']
    if args.profile:
        command += ['-XX:FlightRecorderOptions=stackdepth=256',
                    f'-XX:StartFlightRecording=settings=profile,filename={run}/profile.jfr,dumponexit=true']
    command += ['-cp', str(args.distribution.resolve() / 'lib' / '*'), 'jadx.cli.JadxCLI',
                '--no-res', '--threads-count', '8', '-d', str(run / 'output'), str(args.apk.resolve())]
    environment = os.environ.copy()
    for key in ['JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS', 'JAVA_OPTS', 'JADX_OPTS']:
        environment.pop(key, None)
    metadata = {'command': command, 'apk_sha256': digest(args.apk),
                'distribution': manifest(args.distribution), 'profiled': args.profile,
                'dependency_profiled': args.dependency_profile,
                'jdk': subprocess.run([java, '-version'], capture_output=True, text=True).stderr,
                'started': time.strftime('%Y-%m-%dT%H:%M:%S%z')}
    (run / 'metadata.json').write_text(json.dumps(metadata, indent=2))
    with (run / 'host-before.txt').open('w') as f:
        subprocess.run(['ps', '-eo', 'pid,pcpu,rss,args', '--sort=-pcpu'], stdout=f)
    with (run / 'stdout.txt').open('w') as stdout, (run / 'stderr.txt').open('w') as stderr:
        process = subprocess.Popen(['/usr/bin/time', '-f', '%e %U %S %M %P %x',
                                    '-o', str(run / 'time.txt')] + command,
                                   stdout=stdout, stderr=stderr, env=environment)
        previous_time, previous = cpu_snapshot()
        with (run / 'host-cpu.jsonl').open('w') as host:
            while True:
                try:
                    exit_code = process.wait(timeout=5)
                    break
                except subprocess.TimeoutExpired:
                    now, current = cpu_snapshot()
                    ticks = os.sysconf('SC_CLK_TCK')
                    busy = [(pid, name, round(100 * (cpu - previous.get(pid, (name, cpu))[1]) / ticks / (now - previous_time), 1))
                            for pid, (name, cpu) in current.items()]
                    host.write(json.dumps(sorted(busy, key=lambda x: x[2], reverse=True)[:8]) + '\n')
                    host.flush()
                    previous_time, previous = now, current
    values = (run / 'time.txt').read_text().splitlines()[-1].split()
    metrics = dict(zip(['wall_s', 'user_s', 'sys_s', 'rss_kib'], map(float, values[:4])))
    metrics.update(cpu_percent=values[4], exit=exit_code)
    files = manifest(run / 'output')
    (run / 'manifest.json').write_text(json.dumps(files, indent=2))
    diagnostics = []
    for name in ['stdout.txt', 'stderr.txt']:
        text = (run / name).read_text(errors='replace')
        diagnostics.extend(re.findall(r'^.*(?:ERROR|WARN|errors?:|warnings?:).*$', text, re.M))
    (run / 'diagnostics.txt').write_text('\n'.join(sorted(diagnostics)))
    metrics['files'] = len(files)
    metrics['java_files'] = sum(name.endswith('.java') for name in files)
    (run / 'metrics.json').write_text(json.dumps(metrics, indent=2))
    print(json.dumps({'run': str(run), **metrics}), flush=True)


if __name__ == '__main__':
    main()
