"""Build diagnostic classes that compare old and new decisions on identical live inputs.

The resulting distribution is for equivalence checks only, never timing comparisons.
"""
import argparse
from pathlib import Path
import shutil
import subprocess


def replace_once(source, old, new):
    assert source.count(old) == 1, old
    return source.replace(old, new, 1)


def section(source, start, end):
    first = source.index(start)
    return source[first:source.index(end, first)]


def counters(source, declaration, label, names):
    fields = ''.join(f'private static final java.util.concurrent.atomic.LongAdder {n} = new java.util.concurrent.atomic.LongAdder();\n'
                     for n in names)
    message = ' + " " + '.join(f'"{n}=" + {n}.sum()' for n in names)
    hook = f'static {{ Runtime.getRuntime().addShutdownHook(new Thread(() -> System.err.println("{label} " + {message}))); }}\n'
    return replace_once(source, declaration, declaration + '\n' + fields + hook)


def old_source(root, commit, relative):
    return subprocess.check_output(['git', 'show', f'{commit}:{relative}'], cwd=root, text=True)


def block_utils(source, old):
    start = '\tpublic static BlockNode getBottomBlock(List<BlockNode> blocks, boolean clean) {'
    end = '\tpublic static boolean isOnlyOnePathExists'
    original = section(old, start, end)
    current = section(source, start, end)
    renamed = []
    for body, suffix in [(current, 'NEW'), (original, 'OLD')]:
        body = body.replace(start, f'private static BlockNode bottom{suffix}(List<BlockNode> blocks, boolean clean) {{', 1)
        body = replace_once(body, '!isPathExists(from, bottomCandidate)',
                            f'!countedPath(from, bottomCandidate, true, BOTTOM_{suffix})')
        body = replace_once(body, '!isAnyPathExists(from, bottomCandidate)',
                            f'!countedPath(from, bottomCandidate, false, BOTTOM_{suffix})')
        renamed.append(body)
    wrapper = '''public static BlockNode getBottomBlock(List<BlockNode> blocks, boolean clean) {
        BlockNode actual = bottomNEW(blocks, clean);
        BlockNode expected = bottomOLD(blocks, clean);
        BOTTOM_CALLS.increment();
        if (actual != expected) { BOTTOM_MISMATCH.increment(); }
        return actual;
    }
    private static boolean countedPath(BlockNode from, BlockNode to, boolean clean,
            java.util.concurrent.atomic.LongAdder count) {
        count.increment();
        return clean ? isPathExists(from, to) : isAnyPathExists(from, to);
    }
'''
    source = replace_once(source, current, wrapper + '\n'.join(renamed))
    return counters(source, 'public class BlockUtils {', 'BOTTOM_SHADOW',
                    ['BOTTOM_CALLS', 'BOTTOM_OLD', 'BOTTOM_NEW', 'BOTTOM_MISMATCH'])


def name_gen(source, old):
    start = '\tprivate String getUniqueVarName(String name) {'
    end = '\tprivate String makeArgName'
    current = section(source, start, end)
    original = section(old, start, end).replace('getUniqueVarName', 'referenceName').replace('varNames', 'referenceNames')
    actual = current.replace('getUniqueVarName', 'actualName', 1)
    wrapper = '''private String getUniqueVarName(String name) {
        String actual = actualName(name);
        String expected = referenceName(name);
        NAME_CALLS.increment();
        if (!actual.equals(expected)) { NAME_MISMATCH.increment(); }
        return actual;
    }
'''
    source = replace_once(source, current, wrapper + actual + original)
    source = replace_once(source, 'public class NameGen {',
                          'public class NameGen {\nprivate final Set<String> referenceNames = new HashSet<>();')
    source = replace_once(source, '\t\taddNamesUsedInClass();',
                          '\t\taddNamesUsedInClass();\nreferenceNames.addAll(varNames);\nreferenceNames.addAll(rootPkgs);')
    source = replace_once(source, 'public void inheritUsedNames(NameGen otherNameGen) {',
                          'public void inheritUsedNames(NameGen otherNameGen) {\nreferenceNames.addAll(otherNameGen.referenceNames);')
    source = replace_once(source, '\t\tString name = "loop" + attr.getLoop().getId();',
                          '\t\tString name = "loop" + attr.getLoop().getId();\nreferenceNames.add(name);')
    return counters(source, 'public class NameGen {', 'NAME_SHADOW', ['NAME_CALLS', 'NAME_MISMATCH'])


def loop_maker(source):
    old = '''\t\treturn !BlockUtils.anyPathBlockMatches(mth.getEnterBlock(), exit,
\t\t\t\tblock -> BlockUtils.checkLastInsnType(block, InsnType.SWITCH));'''
    new = '''LOOP_CALLS.increment();
        java.util.Set<BlockNode> reference = BlockUtils.getAllPathsBlocks(mth.getEnterBlock(), exit);
        LOOP_OLD_BLOCKS.add(reference.size());
        boolean expected = reference.stream().anyMatch(block -> BlockUtils.checkLastInsnType(block, InsnType.SWITCH));
        boolean actual = BlockUtils.anyPathBlockMatches(mth.getEnterBlock(), exit, block -> {
            LOOP_NEW_BLOCKS.increment();
            return BlockUtils.checkLastInsnType(block, InsnType.SWITCH);
        });
        if (expected != actual) { LOOP_MISMATCH.increment(); }
        return !actual;'''
    source = replace_once(source, old, new)
    return counters(source, 'final class LoopRegionMaker {', 'LOOP_SHADOW',
                    ['LOOP_CALLS', 'LOOP_OLD_BLOCKS', 'LOOP_NEW_BLOCKS', 'LOOP_MISMATCH'])


def type_search(source, old):
    start = '\tprivate List<ArgType> getWiderTypes'
    end = '\tprivate List<ArgType> expandUnknownType'
    original = section(old, start, end)
    original = original.replace('getWiderTypes', 'oldWiderTypes').replace('getNarrowTypes', 'oldNarrowTypes')
    original = original.replace('Collectors.toList()', 'java.util.stream.Collectors.toList()')
    assert original.count('.map(ArgType::object)') == 2
    original = original.replace('.map(ArgType::object)', '.map(name -> { TYPE_OLD_OBJECTS.increment(); return ArgType.object(name); })')
    assert source.count('.map(ArgType::object)') == 2
    source = source.replace('.map(ArgType::object)', '.map(name -> { TYPE_NEW_OBJECTS.increment(); return ArgType.object(name); })')
    calculation = section(old, '\t\tSet<ArgType> candidateTypes =', '\n\t\tint size = candidateTypes.size();')
    calculation = calculation.replace('candidateTypes', 'expected').replace('getWiderTypes', 'oldWiderTypes').replace('getNarrowTypes', 'oldNarrowTypes')
    checker = '''private void checkCandidateSet(SSAVar ssaVar, Set<ITypeBound> bounds,
            Set<ArgType> assigns, Set<ArgType> uses, Set<ArgType> actual) {
''' + calculation + '''
        TYPE_SETS.increment();
        if (!new ArrayList<>(actual).equals(new ArrayList<>(expected))) { TYPE_MISMATCH.increment(); }
    }
'''
    source = replace_once(source, '\t\taddUsageTypeCandidates(ssaVar, bounds, candidateTypes);',
                          '\t\taddUsageTypeCandidates(ssaVar, bounds, candidateTypes);\ncheckCandidateSet(ssaVar, bounds, assigns, uses, candidateTypes);')
    source = replace_once(source, end, checker + original + end)
    source = replace_once(source, 'private boolean addCandidateType(Set<ITypeBound> bounds, Set<ArgType> collectedTypes, ArgType candidateType) {',
                          '''private boolean addCandidateType(Set<ITypeBound> bounds, Set<ArgType> collectedTypes, ArgType candidateType) {
        TYPE_ADD_BOTH.increment();
        if (collectedTypes.contains(candidateType)) { TYPE_REPEAT_BOTH.increment(); }''')
    return counters(source, 'public class TypeSearch {', 'TYPE_SHADOW',
                    ['TYPE_SETS', 'TYPE_OLD_OBJECTS', 'TYPE_NEW_OBJECTS', 'TYPE_ADD_BOTH', 'TYPE_REPEAT_BOTH', 'TYPE_MISMATCH'])


def variables(source, old):
    name = 'static boolean isAllUseAfter(UsePlace checkPlace, List<UsePlace> usePlaces) {'
    start = source.rfind('\n', 0, source.index(name)) + 1
    end = '\tprivate static boolean isContainerContainsUsePlace'
    current = source[start:source.index(end, start)]
    original = section(old, '\tprivate ' + name, end)
    original = original.replace('private ' + name, 'private ' + name.replace('isAllUseAfter', 'oldUseAfter'), 1)
    original = original.replace('Set<UsePlace>', 'java.util.Set<UsePlace>').replace('new HashSet<>', 'new java.util.HashSet<>')
    original = replace_once(original, 'new java.util.HashSet<>(usePlaces);',
                            'new java.util.HashSet<>(usePlaces);\nVAR_UNIQUE.add(toCheck.size());')
    renamed = []
    for body, suffix in [(current.replace('isAllUseAfter', 'newUseAfter', 1), 'NEW'), (original, 'OLD')]:
        body = replace_once(body, 'isContainerContainsUsePlace(subBlock, usePlace)',
                            f'countedContains(subBlock, usePlace, VAR_{suffix}_CHECKS)')
        renamed.append(body)
    signature = current[:current.index('{') + 1]
    wrapper = signature + '''
        boolean actual = newUseAfter(checkPlace, usePlaces);
        boolean expected = oldUseAfter(checkPlace, usePlaces);
        VAR_CALLS.increment(); VAR_INPUT.add(usePlaces.size());
        if (actual) { VAR_TRUE.increment(); }
        if (actual != expected) { VAR_MISMATCH.increment(); }
        return actual;
    }
    private static boolean countedContains(IContainer subBlock, UsePlace usePlace,
            java.util.concurrent.atomic.LongAdder count) {
        count.increment();
        return isContainerContainsUsePlace(subBlock, usePlace);
    }
'''
    source = replace_once(source, current, wrapper + '\n'.join(renamed))
    return counters(source, 'public class ProcessVariables extends AbstractVisitor {', 'VAR_SHADOW',
                    ['VAR_CALLS', 'VAR_INPUT', 'VAR_UNIQUE', 'VAR_TRUE', 'VAR_OLD_CHECKS', 'VAR_NEW_CHECKS', 'VAR_MISMATCH'])


def live_vars(source, old):
    start = '\tprivate void processLiveInfo() {'
    end = '\tprivate static BitSet[] initBitSetArray'
    current = section(source, start, end)
    original = section(old, start, end)
    renamed = []
    for body, suffix in [(current, 'NEW'), (original, 'OLD')]:
        body = body.replace('processLiveInfo', 'liveInfo' + suffix, 1)
        body = replace_once(body, '\t\tthis.liveIn = liveInBlocks;',
                            f'LIVE_{suffix}_ROUNDS.add(k); LIVE_{suffix}_SCANS.add((long) k * blocksCount);\n'
                            f'if (k > 10) {{ LIVE_{suffix}_LONG.increment(); }}\n\t\tthis.liveIn = liveInBlocks;')
        renamed.append(body)
    wrapper = '''private void processLiveInfo() {
        liveInfoNEW();
        BitSet[] actual = this.liveIn;
        liveInfoOLD();
        LIVE_METHODS.increment();
        for (int i = 0; i < actual.length; i++) {
            if (!actual[i].equals(this.liveIn[i])) { LIVE_MISMATCH.increment(); break; }
        }
        this.liveIn = actual;
    }
'''
    source = replace_once(source, current, wrapper + '\n'.join(renamed))
    return counters(source, 'public class LiveVarAnalysis {', 'LIVE_SHADOW',
                    ['LIVE_METHODS', 'LIVE_OLD_ROUNDS', 'LIVE_NEW_ROUNDS', 'LIVE_OLD_SCANS', 'LIVE_NEW_SCANS',
                     'LIVE_OLD_LONG', 'LIVE_NEW_LONG', 'LIVE_MISMATCH'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('distribution', type=Path)
    parser.add_argument('destination', type=Path)
    parser.add_argument('jdk', type=Path)
    args = parser.parse_args()
    root = Path(subprocess.check_output(['git', 'rev-parse', '--show-toplevel'], text=True).strip())
    destination = args.destination.resolve()
    shutil.copytree(args.distribution, destination)
    source_dir = destination / 'diagnostic-source'
    source_dir.mkdir()
    files = []
    paths = [('jadx/core/utils/BlockUtils.java', block_utils),
             ('jadx/core/codegen/NameGen.java', name_gen),
             ('jadx/core/dex/visitors/regions/maker/LoopRegionMaker.java', loop_maker),
             ('jadx/core/dex/visitors/typeinference/TypeSearch.java', type_search),
             ('jadx/core/dex/visitors/regions/variables/ProcessVariables.java', variables),
             ('jadx/core/dex/visitors/ssa/LiveVarAnalysis.java', live_vars)]
    for relative, transform in paths:
        tracked = 'jadx-core/src/main/java/' + relative
        current = (root / tracked).read_text()
        if transform == loop_maker:
            result = transform(current)
        else:
            result = transform(current, old_source(root, '4574686', tracked))
        path = source_dir / Path(relative).name
        path.write_text(result)
        files.append(str(path))
    classes = destination / 'diagnostic-classes'
    subprocess.run([str(args.jdk / 'bin/javac'), '--release', '11', '-cp', str(destination / 'lib/*'),
                    '-d', str(classes)] + files, check=True)
    core = list((destination / 'lib').glob('jadx-core-*.jar'))
    assert len(core) == 1
    subprocess.run([str(args.jdk / 'bin/jar'), 'uf', str(core[0]), '-C', str(classes), '.'], check=True)


if __name__ == '__main__':
    assert replace_once('abc', 'b', 'd') == 'adc'
    assert section('aa START bb END cc', 'START', 'END') == 'START bb '
    main()
