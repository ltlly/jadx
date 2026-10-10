#!/usr/bin/env python3
"""Compare two jadx full-decompile output trees (macOS port of audit.py + quality.py).

Subcommands:
  diff      Compare two run output dirs (sources/ + resources/): file-set diff,
            byte-hash diff, comment-only vs Java-token changes, jadx in-file
            ERROR-line changes, omitted-method bodies, marker-line sets, and
            optional run-log ERROR/WARN line-set comparison.
  baseline  Three identical-build runs: pairwise diff summaries plus the union
            set of differing files (the run's own non-determinism baseline).
"""
import argparse
import difflib
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).parent))
from quality_macos import log_lines, manifest, read_text_lossy, scan_java, tokens


def pair_report(left_dir, right_dir, left_scan, right_scan, hashes_l, hashes_r, logs, write_diffs, diff_stream):
    """Full diff of two scanned runs, mirroring audit.py's report schema."""
    report = dict(left=str(left_dir), right=str(right_dir),
                  missing=sorted(hashes_l.keys() - hashes_r.keys()),
                  extra=sorted(hashes_r.keys() - hashes_l.keys()))
    changed = sorted(name for name in hashes_l.keys() & hashes_r.keys() if hashes_l[name] != hashes_r[name])
    report['byte_identical'] = len(hashes_l.keys() & hashes_r.keys()) - len(changed)
    report['changed'] = changed
    report['comment_only'] = []
    report['java_tokens_changed'] = []
    report['error_changes'] = {}
    report['markers_changed'] = {}
    report['omitted'] = dict(left_files=len(left_scan[1]), right_files=len(right_scan[1]),
                             newly_omitted=[], restored=[])
    for name in changed:
        aa = read_text_lossy(left_dir / name)
        bb = read_text_lossy(right_dir / name)
        report['comment_only' if tokens(aa) == tokens(bb) else 'java_tokens_changed'].append(name)
        ae = [line for line in left_scan[0].get(name, ()) if line.startswith('JADX ERROR:')]
        be = [line for line in right_scan[0].get(name, ()) if line.startswith('JADX ERROR:')]
        if ae != be:
            report['error_changes'][name] = dict(before=ae, after=be)
        if left_scan[0].get(name, ()) != right_scan[0].get(name, ()):
            report['markers_changed'][name] = dict(before=list(left_scan[0].get(name, ())),
                                                   after=list(right_scan[0].get(name, ())))
        if write_diffs:
            diff_stream.writelines(difflib.unified_diff(aa.splitlines(True), bb.splitlines(True),
                                                        str(left_dir / name), str(right_dir / name)))
    before = {(name, body) for name, bodies in left_scan[1].items() for body in bodies}
    after = {(name, body) for name, bodies in right_scan[1].items() for body in bodies}
    report['omitted']['newly_omitted'] = sorted(f'{name}: {body}' for name, body in after - before)
    report['omitted']['restored'] = sorted(f'{name}: {body}' for name, body in before - after)
    if logs and len(logs) == 2:
        parsed, pairs = log_comparisons(logs)
        raw_a, norm_a = parsed[0][1], parsed[0][2]
        raw_b, norm_b = parsed[1][1], parsed[1][2]
        report['log'] = dict(files=[str(path) for path in logs],
                             raw_sizes=[len(raw_a), len(raw_b)],
                             raw_identical=raw_a == raw_b,
                             raw_new=sorted(raw_b - raw_a), raw_removed=sorted(raw_a - raw_b),
                             normalized_sizes=[len(norm_a), len(norm_b)],
                             normalized_identical=norm_a == norm_b,
                             normalized_new=sorted(norm_b - norm_a),
                             normalized_removed=sorted(norm_a - norm_b))
    return report


def log_comparisons(log_paths):
    """Pairwise raw/normalized ERROR-WARN line-set diffs for consecutive logs."""
    parsed = [(path, *log_lines(path)) for path in log_paths]
    result = []
    for (pa, raw_a, norm_a), (pb, raw_b, norm_b) in zip(parsed, parsed[1:]):
        result.append(dict(left=str(pa), right=str(pb),
                           raw_identical=raw_a == raw_b,
                           raw_new=sorted(raw_b - raw_a), raw_removed=sorted(raw_a - raw_b),
                           normalized_identical=norm_a == norm_b,
                           normalized_new=sorted(norm_b - norm_a),
                           normalized_removed=sorted(norm_a - norm_b)))
    return parsed, result


def load_run(run_dir):
    """Hash + scan one output tree in a single walk over .java files."""
    hashes = manifest(run_dir)
    java_hashes = {name: value for name, value in hashes.items() if name.endswith('.java')}
    markers, omitted = scan_java(run_dir, java_hashes)
    return hashes, markers, omitted


def cmd_diff(args):
    left, right = args.left.resolve(), args.right.resolve()
    hashes_l, markers_l, omitted_l = load_run(left)
    hashes_r, markers_r, omitted_r = load_run(right)
    diff_stream = Path(args.output + '.diff').open('w') if args.output and args.diffs else None
    try:
        report = pair_report(left, right, (markers_l, omitted_l), (markers_r, omitted_r),
                             hashes_l, hashes_r, args.log, bool(diff_stream), diff_stream)
    finally:
        if diff_stream:
            diff_stream.close()
    if args.output:
        Path(args.output + '.json').write_text(json.dumps(report, indent=2))
    print(json.dumps({key: (len(value) if isinstance(value, (list, dict)) else value)
                      for key, value in report.items()}, indent=2))


def cmd_baseline(args):
    runs = [path.resolve() for path in args.runs]
    logs = args.logs if args.logs else []
    scans, parsed_logs, log_pairs = [], [], []
    for index, run in enumerate(runs):
        hashes, markers, omitted = load_run(run)
        scans.append((run, hashes, markers, omitted))
        print(f'{run.name}: {len(hashes)} files, {len(markers)} with markers, '
              f'{len(omitted)} with omitted bodies', file=sys.stderr)
    marker_totals = [len(markers) for _, _, markers, _ in scans]
    omitted_totals = [len(omitted) for _, _, _, omitted in scans]
    pair_reports = {}
    union_changed = set()
    marker_union = [set(), set(), set()]
    for i in range(len(runs)):
        for j in range(i + 1, len(runs)):
            left, hashes_l, markers_l, omitted_l = scans[i]
            right, hashes_r, markers_r, omitted_r = scans[j]
            missing = sorted(hashes_l.keys() - hashes_r.keys())
            extra = sorted(hashes_r.keys() - hashes_l.keys())
            changed = sorted(name for name in hashes_l.keys() & hashes_r.keys()
                             if hashes_l[name] != hashes_r[name])
            union_changed.update(changed)
            token_changed = []
            marker_changed = []
            error_changed = []
            for name in changed:
                aa = read_text_lossy(left / name)
                bb = read_text_lossy(right / name)
                if tokens(aa) != tokens(bb):
                    token_changed.append(name)
                before, after = markers_l.get(name, ()), markers_r.get(name, ())
                if before != after:
                    marker_changed.append(name)
                    marker_union[i].add(name)
                    marker_union[j].add(name)
                if [l for l in before if l.startswith('JADX ERROR:')] != \
                   [l for l in after if l.startswith('JADX ERROR:')]:
                    error_changed.append(name)
            key = f'{left.name}__{right.name}'
            pair_reports[key] = dict(
                left=str(left), right=str(right),
                missing=missing, extra=extra, changed_count=len(changed),
                changed=changed, comment_only_count=len(changed) - len(token_changed),
                java_tokens_changed=token_changed,
                java_tokens_changed_count=len(token_changed),
                markers_changed=marker_changed, markers_changed_count=len(marker_changed),
                marker_line_changes={name: dict(before=list(markers_l.get(name, ())),
                                                after=list(markers_r.get(name, ())))
                                     for name in marker_changed},
                error_changes=error_changed, error_changes_count=len(error_changed),
                omitted=dict(left_files=len(omitted_l), right_files=len(omitted_r),
                             newly_omitted=sorted(f'{n}: {b}' for n, bodies in omitted_r.items()
                                                  for b in bodies if b not in omitted_l.get(n, ())),
                             restored=sorted(f'{n}: {b}' for n, bodies in omitted_l.items()
                                             for b in bodies if b not in omitted_r.get(n, ()))),
            )
            print(f'{key}: changed={len(changed)} token_changed={len(token_changed)} '
                  f'marker_changed={len(marker_changed)} error_changed={len(error_changed)}',
                  file=sys.stderr)
    if logs:
        parsed_logs, log_pairs = log_comparisons(logs)
    union_markers = set.union(*marker_union) if marker_union else set()
    report = dict(runs=[str(run) for run in runs],
                  java_file_counts=[len(hashes) for _, hashes, _, _ in scans],
                  marker_file_counts=marker_totals, omitted_file_counts=omitted_totals,
                  pairs=pair_reports,
                  union_differing_files=sorted(union_changed),
                  union_differing_count=len(union_changed),
                  union_marker_changed_files=sorted(union_markers),
                  union_marker_changed_count=len(union_markers),
                  logs=dict(files=[str(path) for path in logs], pairwise=log_pairs,
                            stats=[dict(file=str(path), raw_lines=len(raw), normalized_lines=len(norm))
                                   for path, raw, norm in parsed_logs]))
    Path(args.output).write_text(json.dumps(report, indent=2))
    summary = dict(pairs={key: dict(changed=value['changed_count'],
                                    java_tokens_changed=value['java_tokens_changed_count'],
                                    markers_changed=value['markers_changed_count'],
                                    error_changes=value['error_changes_count'],
                                    file_set_missing=len(value['missing']), extra=len(value['extra']))
                          for key, value in pair_reports.items()},
                   union_differing_count=len(union_changed))
    print(json.dumps(summary, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)

    diff = sub.add_parser('diff', help='compare two run output dirs')
    diff.add_argument('left', type=Path)
    diff.add_argument('right', type=Path)
    diff.add_argument('--output', help='write full JSON report to OUTPUT.json (and OUTPUT.diff with --diffs)')
    diff.add_argument('--diffs', action='store_true', help='emit unified text diffs for changed files')
    diff.add_argument('--log', nargs=2, metavar=('LEFT_LOG', 'RIGHT_LOG'), type=Path,
                      help='run stderr/stdout logs to compare ERROR/WARN line sets')
    diff.set_defaults(func=cmd_diff)

    base = sub.add_parser('baseline', help='baseline non-determinism across >=3 identical-build runs')
    base.add_argument('runs', nargs='+', type=Path)
    base.add_argument('--output', required=True, help='baseline JSON output path')
    base.add_argument('--logs', nargs='+', type=Path, help='per-run stderr/stdout logs, same order as runs')
    base.set_defaults(func=cmd_baseline)

    assert tokens('a /* text */ + "/* value */"') == tokens('a+"/* value */"')
    assert tokens('a + +b') != tokens('a++b')
    assert tokens('public class A') != tokens('publicclass A')
    args = parser.parse_args()
    args.func(args)


if __name__ == '__main__':
    main()
