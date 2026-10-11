#!/usr/bin/env python3
"""Summarize existing JFR JSON; optional PROCESS joins are approximate, not CPU timers.

Example: summarize_type_profile.py profile.events.json --jvm-info jvm-info.json \
    --timeline dependency-profile/timeline.tsv --status dependency-profile/status.tsv
Create jvm-info.json with: jfr print --json --events jdk.JVMInformation profile.jfr
"""
import argparse
import bisect
import collections
import csv
import datetime
import heapq
import importlib.util
import itertools
import json
from pathlib import Path
import re


PARSER = Path(__file__).resolve().parents[1] / '20261010' / 'summarize_profile.py'
SPEC = importlib.util.spec_from_file_location('summarize_profile', PARSER)
PROFILE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PROFILE)
TYPE = 'jadx.core.dex.visitors.typeinference.'
FIX = TYPE + 'FixTypesVisitor.'
SEARCH = TYPE + 'TypeSearch.'
RESOLVERS = {
    'applyFieldType', 'tryRestoreTypeVarCasts', 'tryInsertCasts', 'tryDeduceTypes',
    'trySplitConstInsns', 'tryToFixIncompatiblePrimitives', 'tryToForceImmutableTypes',
    'tryInsertAdditionalMove', 'runMultiVariableSearch', 'tryRemoveGenerics',
}
SEARCH_STAGES = {
    'fillTypeCandidates', 'collectConstraints', 'resolveIndependentVariables',
    'search', 'fullCheck', 'applyResolvedVars',
}
OFFSETS_MS = (-5, -1, 0, 1, 5)


def epoch_ns(value):
    match = re.fullmatch(r'(.*T\d\d:\d\d:\d\d)(?:\.(\d{1,9}))?(Z|[+-]\d\d:\d\d)', value)
    if not match:
        raise ValueError('Unsupported JFR timestamp: ' + value)
    date = datetime.datetime.fromisoformat(match[1] + match[3].replace('Z', '+00:00'))
    return int(date.timestamp()) * 1_000_000_000 + int((match[2] or '').ljust(9, '0'))


def buckets(frames):
    """Each sample belongs to at most one resolver and one nested search stage."""
    if FIX + 'visit' not in frames:
        return None, None
    inside = frames[:frames.index(FIX + 'visit')]
    resolver = next((f[len(FIX):] for f in reversed(inside)
                     if f.startswith(FIX) and f[len(FIX):] in RESOLVERS), 'other_or_unknown')
    stage = None
    if SEARCH + 'run' in inside:
        search_frames = inside[:inside.index(SEARCH + 'run')]
        stage = next((f[len(SEARCH):] for f in reversed(search_frames)
                      if f.startswith(SEARCH) and f[len(SEARCH):] in SEARCH_STAGES), 'other_or_unknown')
    return resolver, stage


def process_index(rows):
    """Flatten PROCESS/METHOD_RECOVERY intervals into disjoint per-thread segments."""
    events = collections.defaultdict(list)
    seen = set()
    for row in rows:
        if not row['stage'].startswith('PROCESS/') and row['stage'] != 'METHOD_RECOVERY':
            continue
        ident, thread = int(row['id']), int(row['thread_id'])
        start, end = int(row['start_ns']), int(row['end_ns'])
        ready = start + int(row['lock_acquire_ns'])
        if ident in seen or not start <= ready <= end:
            raise ValueError('Invalid PROCESS interval: ' + str(row))
        seen.add(ident)
        if start == end:
            continue
        if row['stage'] == 'METHOD_RECOVERY':
            phase = 'recovery_method' if row['recovery'] == 'true' else 'unknown_method_recovery'
        elif row['recovery'] == 'false':
            phase = 'first'
        elif row['recovery'] == 'true':
            phase = {'METADATA_DEPENDENCY': 'recovery_metadata', 'LEGACY': 'recovery_legacy'}.get(
                row['stage'].split('/')[1], 'unknown_request_kind')
        else:
            phase = 'unknown_recovery'
        scope = (ready, phase, ident, end)
        events[thread].extend([(start, 1, ident, scope), (ready, 0, ident, None), (end, -1, ident, None)])
    index = {}
    for thread, points in events.items():
        points.sort()
        active, heap, starts, segments = set(), [], [], []
        for time, changes in itertools.groupby(points, key=lambda point: point[0]):
            if starts:
                segments[-1][0] = time
            for _, kind, ident, scope in changes:
                if kind == 1:
                    while heap and heap[0][2][2] not in active:
                        heapq.heappop(heap)
                    if heap and scope[3] > heap[0][2][3]:
                        raise ValueError('Crossing process intervals on thread ' + str(thread))
                    active.add(ident)
                    heapq.heappush(heap, (-time, -ident, scope))
                elif kind == -1:
                    active.discard(ident)
            while heap and heap[0][2][2] not in active:
                heapq.heappop(heap)
            if heap:
                ready, phase, ident, _ = heap[0][2]
                result = (phase if time >= ready else 'unknown_before_lock', ident)
            else:
                result = ('unknown_outside_process', None)
            starts.append(time)
            segments.append([time, result])
        index[thread] = (starts, segments)
    return index


def locate(index, thread, time):
    starts, segments = index.get(thread, ([], []))
    pos = bisect.bisect_right(starts, time) - 1
    if pos < 0 or time >= segments[pos][0]:
        return 'unknown_outside_process', None
    return segments[pos][1]


def classifications(values, origin, index):
    if origin is None:
        return {offset: ('unknown_no_anchor', None) for offset in OFFSETS_MS}
    thread = (values.get('sampledThread') or {}).get('javaThreadId')
    if thread is None or not values.get('startTime'):
        return {offset: ('unknown_sample_metadata', None) for offset in OFFSETS_MS}
    relative = epoch_ns(values['startTime']) - origin
    return {offset: locate(index, thread, relative + offset * 1_000_000) for offset in OFFSETS_MS}


def load_join(args):
    if not all((args.jvm_info, args.timeline, args.status)):
        return None, {}, 'Join disabled: --jvm-info, --timeline and --status are all required.'
    with args.status.open() as stream:
        status, = list(csv.DictReader(stream, delimiter='\t'))
    if any(int(status[key]) for key in ('active_scopes', 'invalid_scopes', 'dropped_trace_events')):
        return None, {}, 'Join disabled: collector reports active, invalid or dropped scopes.'
    with args.jvm_info.open() as stream:
        starts = {epoch_ns(event['values']['jvmStartTime']) for event in PROFILE.iter_events(stream)
                  if event['type'] == 'jdk.JVMInformation'}
    if len(starts) != 1:
        return None, {}, 'Join disabled: expected exactly one distinct JVM start time.'
    origin = starts.pop() + int(status['origin_uptime_ms']) * 1_000_000
    with args.timeline.open() as stream:
        index = process_index(csv.DictReader(stream, delimiter='\t'))
    return origin, index, 'Approximate join; supply all inputs from the same run. Boundaries are not exact.'


def summarize(events, origin, index):
    counts = collections.Counter()
    resolvers = collections.defaultdict(collections.Counter)
    searches = collections.defaultdict(collections.Counter)
    sensitivity = {offset: collections.Counter() for offset in OFFSETS_MS}
    changed = collections.Counter({str(offset): 0 for offset in OFFSETS_MS})
    allocations, allocation_classes, resolver_allocations = (collections.Counter() for _ in range(3))
    for event in events:
        values, frames = event['values'], PROFILE.methods(event)
        resolver, search = buckets(frames)
        if event['type'] == 'jdk.ObjectAllocationSample':
            if any(frame.startswith(TYPE) for frame in frames):
                weight = values['weight']
                first = next((frame for frame in frames if frame.startswith('jadx.')), '(no jadx frame)')
                allocations[first] += weight
                allocation_classes[values['objectClass']['name']] += weight
                if resolver is not None:
                    resolver_allocations[resolver] += weight
            continue
        if event['type'] != 'jdk.ExecutionSample':
            continue
        counts['execution_samples'] += 1
        truncated = bool((values.get('stackTrace') or {}).get('truncated'))
        counts['truncated_execution_samples'] += truncated
        if resolver is None:
            counts['type_frames_without_fix_visit'] += any(frame.startswith(TYPE) for frame in frames)
            continue
        counts['fix_samples'] += 1
        counts['truncated_fix_samples'] += truncated
        aligned = classifications(values, origin, index)
        for offset, result in aligned.items():
            sensitivity[offset][result[0]] += 1
            if result != aligned[0]:
                changed[str(offset)] += 1
        phase = aligned[0][0]
        if aligned[-1] != aligned[0] or aligned[1] != aligned[0]:
            phase = 'unknown_boundary_1ms'
        for group in ('all', phase):
            resolvers[group][resolver] += 1
            if search is not None:
                searches[group][search] += 1
    return {
        'counts': dict(counts),
        'fix_resolver_samples': dict(resolvers),
        'type_search_stage_samples_with_fix_visit': dict(searches),
        'alignment_sensitivity_fix_samples': sensitivity,
        'scope_or_phase_changes_vs_zero_offset': dict(changed),
        'type_stack_allocation_first_jadx_caller_weights': allocations.most_common(25),
        'type_stack_allocation_class_weights': allocation_classes.most_common(25),
        'fix_resolver_allocation_weights': dict(resolver_allocations),
    }


def self_check():
    def row(ident, start, ready, end, recovery='false'):
        return dict(id=ident, thread_id=7, stage='PROCESS/METADATA_DEPENDENCY/false',
                    start_ns=start, end_ns=end, lock_acquire_ns=ready - start, recovery=recovery)
    index = process_index([row(1, 0, 10, 100), row(2, 20, 25, 40, 'true')])
    assert locate(index, 7, 9) == ('unknown_before_lock', 1)
    assert locate(index, 7, 10) == ('first', 1)
    assert locate(index, 7, 20) == ('unknown_before_lock', 2)
    assert locate(index, 7, 25) == ('recovery_metadata', 2)
    assert locate(index, 7, 40) == ('first', 1)
    assert locate(index, 7, 100) == ('unknown_outside_process', None)
    assert classifications({}, None, index)[0] == ('unknown_no_anchor', None)
    scaled = process_index([row(1, 0, 0, 100_000_000), row(2, 20_000_000, 20_000_000, 40_000_000, 'true')])
    aligned = classifications({'sampledThread': {'javaThreadId': 7},
                               'startTime': '1970-01-01T00:00:00.020500000Z'}, 0, scaled)
    assert aligned[0] == ('recovery_metadata', 2)
    assert aligned[-1] == aligned[-5] == ('first', 1)
    assert aligned[1] == aligned[5] == aligned[0]
    method = row(3, 50, 50, 60, 'true')
    method['stage'] = 'METHOD_RECOVERY'
    assert locate(process_index([row(1, 0, 0, 100), method]), 7, 55) == ('recovery_method', 3)
    frames = [SEARCH + 'fullCheck', SEARCH + 'search', SEARCH + 'run',
              FIX + 'runMultiVariableSearch', FIX + 'visit']
    assert buckets(frames) == ('runMultiVariableSearch', 'search')
    assert buckets(frames[2:]) == ('runMultiVariableSearch', 'other_or_unknown')
    assert buckets(frames[:-1]) == (None, None)
    assert buckets([FIX + 'tryDeduceTypes', FIX + 'tryInsertAdditionalMove', FIX + 'visit']) == (
        'tryInsertAdditionalMove', None)
    assert epoch_ns('2026-10-11T00:00:00.000000123Z') % 1_000_000_000 == 123


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('events', type=Path, help='Existing profile.events.json; no JVM is launched')
    parser.add_argument('--jvm-info', type=Path)
    parser.add_argument('--timeline', type=Path)
    parser.add_argument('--status', type=Path)
    parser.add_argument('--output', type=Path, help='New JSON output file; defaults to stdout')
    args = parser.parse_args()
    self_check()
    origin, index, join_note = load_join(args)
    with args.events.open() as stream:
        result = summarize(PROFILE.iter_events(stream), origin, index)
    result['inputs'] = {key: str(value.resolve()) for key, value in vars(args).items()
                       if value is not None and key != 'output'}
    result['notes'] = [
        join_note,
        'Resolver/search buckets are exclusive within each group; all includes every phase.',
        'Search stages only cover stacks with both TypeSearch.run and FixTypesVisitor.visit.',
        'Phase buckets exclude samples crossing a scope/phase boundary at +/-1ms; sensitivity uses raw offsets.',
        'Offsets are added to the sample time relative to the estimated collector origin.',
        'first means recovery=false for the innermost PROCESS; LEGACY and METHOD_RECOVERY are separate.',
        'Clock anchors have millisecond precision and were not read atomically; sensitivity is not an error bound.',
        'Execution counts are samples, allocation values are sampled byte weights, neither is exact CPU time.',
        'These samples cannot retrospectively split the historical 39.230 CPU seconds from another run.',
    ]
    output = json.dumps(result, indent=2, sort_keys=True) + '\n'
    if args.output:
        with args.output.open('x') as stream:
            stream.write(output)
    else:
        print(output, end='')


if __name__ == '__main__':
    main()
