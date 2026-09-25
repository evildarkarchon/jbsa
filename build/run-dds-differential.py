"""Record issue #40 public-CLI/oracle DDS differentials and development timings.

This is development evidence, not an approved CV1 golden or performance-v1
qualification. The immutable CV1 and performance gates remain separate.
"""

import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import time


ROOT = Path(__file__).resolve().parent.parent


def load_scanner():
    """Load the independent scanner without importing product implementation code."""
    spec = importlib.util.spec_from_file_location('dds_scanner', ROOT / 'build/validate-dds-wire.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def digest(path):
    """Hash a file for an immutable observation identity."""
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def execute(command, output, label):
    """Retain exact command, streams, exit and monotonic development duration."""
    started = time.perf_counter_ns()
    result = subprocess.run(command, cwd=output, capture_output=True, timeout=300)
    elapsed = time.perf_counter_ns() - started
    (output / (label + '.stdout')).write_bytes(result.stdout)
    (output / (label + '.stderr')).write_bytes(result.stderr)
    observation = {'command': command, 'exit': result.returncode, 'elapsed_ns': elapsed,
                   'stdout_sha256': hashlib.sha256(result.stdout).hexdigest(),
                   'stderr_sha256': hashlib.sha256(result.stderr).hexdigest()}
    if result.returncode:
        raise RuntimeError(f'{label} exited {result.returncode}; inspect retained streams')
    return observation


def scan_tree(directory, scanner):
    """Return canonical metadata, exact DDS and opaque payload identities per name."""
    records = {}
    for path in sorted(directory.rglob('*.dds')):
        records[path.relative_to(directory).as_posix().lower()] = {
            'dds_sha256': digest(path), 'projection': scanner.inspect_dds(path.read_bytes())}
    return records


def main():
    """Run both differential directions against fresh generated source and retain discrepancies."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--texdiag', type=Path, help='Optional pinned-by-observation DirectXTex texdiag executable')
    args = parser.parse_args()
    output = args.output.resolve()
    if not output.is_relative_to(ROOT / 'target'):
        raise ValueError('Evidence must be beneath repository target')
    output.mkdir(parents=True, exist_ok=False)
    oracle_work = output / 'oracle-work'
    oracle_work.mkdir()
    scanner = load_scanner()
    java = Path(os.environ['JAVA_HOME']) / 'bin/java.exe' if os.environ.get('JAVA_HOME') else Path(shutil.which('java'))
    jars = []
    for module in ('jbsa', 'jbsa-cli'):
        candidates = [p for p in (ROOT / module / 'target').glob(module + '-*.jar')
                      if not p.name.endswith(('-sources.jar', '-javadoc.jar'))]
        if len(candidates) != 1:
            raise ValueError(f'Exactly one packaged {module} artifact is required')
        jars.extend(candidates)
    cli = [str(java), '--enable-native-access=ALL-UNNAMED', '-cp', os.pathsep.join(map(str, jars)),
           'io.github.evildarkarchon.jbsa.cli.Main']
    report = {'kind': 'issue40-development-differential', 'automated_conformance': False,
              'performance_qualification': False, 'artifacts': {str(p): digest(p) for p in jars},
              'java': {'path': str(java), 'sha256': digest(java)},
              'scanner_sha256': digest(ROOT / 'build/validate-dds-wire.py'),
              'observations': {}, 'errors': []}
    try:
        execute([shutil.which('python'), str(ROOT / 'build/generate-dds-fixtures.py'),
                 str(output / 'fixtures')], output, 'generate')
        source = output / 'fixtures/source'
        source_tree = scan_tree(source, scanner)
        report['source'] = source_tree
        oracle = output / 'oracle.ba2'
        candidate = output / 'candidate.ba2'
        for operation, input_path, output_path, label in (
                ('pack', source, oracle, 'oracle-pack'),):
            command = [shutil.which('pwsh'), '-NoProfile', '-File', str(ROOT / 'build/run-dds-oracle.ps1'),
                       '-Operation', operation, '-InputPath', str(input_path), '-OutputPath', str(output_path),
                       '-WorkingDirectory', str(oracle_work), '-EvidenceDirectory', str(output / label)]
            report['observations'][label] = execute(command, output, label)
        report['observations']['candidate-pack'] = execute(cli + ['pack', str(source), str(candidate),
            '-fo4dds', '-split:0', '-share:no', '-mt:no'], output, 'candidate-pack')
        report['candidate_projection'] = scanner.inspect_archive(candidate.read_bytes())
        report['output_bytes'] = {'candidate': candidate.stat().st_size, 'oracle': oracle.stat().st_size}
        candidate_decoded = output / 'candidate-decoded'
        oracle_decoded = output / 'oracle-decoded'
        candidate_decoded.mkdir()
        oracle_decoded.mkdir()
        report['observations']['candidate-unpack'] = execute(cli + ['unpack', str(oracle),
            str(candidate_decoded), '-mt:no'], output, 'candidate-unpack')
        report['observations']['oracle-unpack'] = execute([
            shutil.which('pwsh'), '-NoProfile', '-File', str(ROOT / 'build/run-dds-oracle.ps1'),
            '-Operation', 'unpack', '-InputPath', str(candidate), '-OutputPath', str(oracle_decoded),
            '-WorkingDirectory', str(oracle_work), '-EvidenceDirectory', str(output / 'oracle-unpack')],
            output, 'oracle-unpack')
        trees = {}
        for label, directory in (('jbsa-decodes-oracle', candidate_decoded), ('oracle-decodes-jbsa', oracle_decoded)):
            try:
                trees[label] = scan_tree(directory, scanner)
            except ValueError as error:
                report['errors'].append({'direction': label, 'error': str(error)})
                continue
            actual = trees[label]
            if actual.keys() != source_tree.keys():
                report['errors'].append({'direction': label, 'error': 'File tree differs'})
            for name in actual.keys() & source_tree.keys():
                if actual[name]['projection'] != source_tree[name]['projection']:
                    report['errors'].append({'direction': label, 'file': name, 'error': 'Payload or metadata differs'})
        report['decoded_trees'] = trees
        if len(trees) == 2:
            report['reconstruction_bytes_equal'] = trees['jbsa-decodes-oracle'] == trees['oracle-decodes-jbsa']
            if not report['reconstruction_bytes_equal']:
                report['reference_differences'] = []
                for name in trees['jbsa-decodes-oracle'].keys() & trees['oracle-decodes-jbsa'].keys():
                    left, right = trees['jbsa-decodes-oracle'][name], trees['oracle-decodes-jbsa'][name]
                    if left == right:
                        continue
                    candidate_bytes = (candidate_decoded / name).read_bytes()
                    oracle_bytes = (oracle_decoded / name).read_bytes()
                    projection = left['projection']
                    fmt, w, h = projection['format'], projection['width'], projection['height']
                    block = 8 if fmt in scanner.BC8 else 16
                    rounded = ((w + 3) // 4) * ((h + 3) // 4) * block
                    shortcut = w * h * block // 16
                    # DDS-009 explicitly replaced the oracle's non-block-rounded top
                    # level LINEARSIZE. Accept only this exact documented discrepancy.
                    explained = (fmt in scanner.BC8 | scanner.BC16 and
                                 left['projection'] == right['projection'] and
                                 candidate_bytes[:20] == oracle_bytes[:20] and
                                 candidate_bytes[24:] == oracle_bytes[24:] and
                                 struct.unpack_from('<I', candidate_bytes, 20)[0] == rounded and
                                 struct.unpack_from('<I', oracle_bytes, 20)[0] == shortcut)
                    difference = {'file': name, 'requirement': 'JBSA-DDS-009',
                                  'candidate_linear_size': struct.unpack_from('<I', candidate_bytes, 20)[0],
                                  'oracle_linear_size': struct.unpack_from('<I', oracle_bytes, 20)[0],
                                  'explained_by_accepted_specification': explained}
                    report['reference_differences'].append(difference)
                    if not explained:
                        report['errors'].append({'file': name, 'error': 'Unexplained canonical reconstruction difference'})
        if args.texdiag:
            texdiag = args.texdiag.resolve()
            report['directxtex'] = {'path': str(texdiag), 'sha256': digest(texdiag), 'observations': []}
            for index, path in enumerate(sorted(candidate_decoded.rglob('*.dds'))):
                report['directxtex']['observations'].append(execute(
                    [str(texdiag), 'info', str(path)], output, f'texdiag-{index}'))
        else:
            report['directxtex'] = {'result': 'UNAVAILABLE'}
    except (ValueError, RuntimeError, OSError, subprocess.TimeoutExpired) as error:
        report['errors'].append({'error': str(error)})
    report['result'] = 'FAIL' if report['errors'] else 'PASS'
    (output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({'result': report['result'], 'report': str(output / 'report.json')}))
    return bool(report['errors'])


if __name__ == '__main__':
    raise SystemExit(main())
