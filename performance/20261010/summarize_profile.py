import collections
import json
from pathlib import Path
import subprocess
import sys


def iter_events(stream):
    decoder = json.JSONDecoder()
    buffer = ''
    while '"events": [' not in buffer:
        chunk = stream.read(65536)
        if not chunk:
            raise ValueError('Missing JFR events array')
        buffer += chunk
    buffer = buffer.split('"events": [', 1)[1]
    while True:
        buffer = buffer.lstrip(' \r\n\t,')
        if buffer.startswith(']'):
            return
        try:
            event, end = decoder.raw_decode(buffer)
        except json.JSONDecodeError:
            chunk = stream.read(1024 * 1024)
            if not chunk:
                raise ValueError('Incomplete JFR event')
            buffer += chunk
            continue
        yield event
        buffer = buffer[end:]


def methods(event):
    stack = event['values'].get('stackTrace') or {}
    return [f['method']['type']['name'].replace('/', '.') + '.' + f['method']['name']
            for f in stack.get('frames', [])]


def summarize(events):
    own = collections.Counter()
    inclusive = collections.Counter()
    allocations = collections.Counter()
    allocated_at = collections.Counter()
    path_callers = collections.Counter()
    samples = truncated = 0
    for event in events:
        frames = methods(event)
        values = event['values']
        if event['type'] == 'jdk.ExecutionSample':
            samples += 1
            truncated += bool((values.get('stackTrace') or {}).get('truncated'))
            if frames:
                own[frames[0]] += 1
            inclusive.update(set(frames))
            for i, frame in enumerate(frames):
                if frame in ['jadx.core.utils.BlockUtils.isPathExists', 'jadx.core.utils.BlockUtils.isAnyPathExists']:
                    path_callers.update(frames[i+1:i+2])
                    break
        elif event['type'] == 'jdk.ObjectAllocationSample':
            weight = values['weight']
            allocations[values['objectClass']['name']] += weight
            first = next((f for f in frames if f.startswith('jadx.')), '(no jadx frame)')
            allocated_at[first] += weight
    return dict(execution_samples=samples, truncated=truncated,
                self_samples=own.most_common(), inclusive_samples=inclusive.most_common(),
                allocation_sample_weights=allocations.most_common(),
                allocation_jadx_caller_weights=allocated_at.most_common(),
                path_query_callers=path_callers.most_common())


if __name__ == '__main__':
    frame = {'method': {'type': {'name': 'a/b'}, 'name': 'recursive'}}
    check = summarize([{'type': 'jdk.ExecutionSample', 'values': {'stackTrace': {'frames': [frame, frame]}}}])
    assert check['inclusive_samples'] == [('a.b.recursive', 1)]
    recording = Path(sys.argv[1])
    dump = recording.with_suffix('.events.json')
    if not dump.exists():
        with dump.open('w') as output:
            subprocess.run(['/usr/lib/jvm/java-27-openjdk/bin/jfr', 'print', '--json', '--stack-depth', '256',
                            '--events', 'jdk.ExecutionSample,jdk.ObjectAllocationSample', str(recording)], stdout=output, check=True)
    with dump.open() as stream:
        result = summarize(iter_events(stream))
    recording.with_suffix('.summary.json').write_text(json.dumps(result, indent=2))
    print('Execution samples:', result['execution_samples'], 'truncated:', result['truncated'])
    for section in ['self_samples', 'inclusive_samples', 'path_query_callers', 'allocation_jadx_caller_weights']:
        print(section)
        print('\n'.join(f'{n:12} {m}' for m, n in result[section][:25]))
