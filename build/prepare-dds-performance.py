"""Prepare real DDS PV1 identities without inventing release launchers or attestations.

The incomplete configuration is intentionally not executable until every listed
prerequisite is supplied. Materialized corpora and oracle archives are reusable;
their preparation never counts as a performance sample.
"""

import argparse
import hashlib
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / 'build/performance'))
import catalog
import corpus
import dds_validator
import runner


def binding(path):
    """Bind an existing exact file; absence remains explicit null rather than a fake digest."""
    path = path.resolve()
    if not path.is_file():
        return None
    retained = path.relative_to(ROOT).as_posix() if path.is_relative_to(ROOT) else str(path)
    return {'path': retained, 'sha256': runner.file_digest(path)}


def write_json(path, value):
    """Write one reproducible preparation artifact without silently replacing changed bytes."""
    encoded = catalog.canonical(value) + b'\n'
    if path.exists():
        if path.read_bytes() != encoded:
            raise ValueError('Preparation output already exists with different bytes: ' + str(path))
    else:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(encoded)
    return binding(path)


def single_artifact(module, suffix):
    """Resolve exactly one current reactor artifact without trusting stale alternative versions."""
    matches = [p for p in (ROOT / module / 'target').glob(module + '-*' + suffix)
               if not p.name.endswith(('-sources.jar', '-javadoc.jar'))]
    if len(matches) != 1:
        raise ValueError('Exactly one artifact required for ' + module + suffix)
    return matches[0]


def main():
    """Pin available qualification inputs and enumerate exact unavailable prerequisites per case."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--corpus-root', type=Path, default=ROOT / 'build/target/dds-pv1/corpus')
    parser.add_argument('--oracle-root', type=Path, default=ROOT / 'build/target/dds-pv1')
    parser.add_argument('--jdk-home', required=True, type=Path)
    parser.add_argument('--jdk-distribution', required=True, type=Path)
    parser.add_argument('--conformance-report', type=Path)
    parser.add_argument('--qualification-scope', choices=('all-dds', 'pc-only'), default='all-dds')
    parser.add_argument('--skip-source-reverification', action='store_true',
                        help='Prepare bindings only; mark source verification pending for the normative runner')
    args = parser.parse_args()
    output = args.output.resolve()
    if not output.is_relative_to(ROOT / 'build/target') and not output.is_relative_to(ROOT / 'target'):
        raise ValueError('Preparation artifacts must stay under ignored build/target or target')
    output.mkdir(parents=True, exist_ok=False)
    protocol = runner.load(ROOT / 'tests/performance/protocol.json')
    if runner.file_digest(args.jdk_distribution) != protocol['jvm']['distribution_sha256']:
        raise ValueError('JDK distribution is not the protocol-pinned release')
    profile_path = ROOT / 'jbsa/src/main/resources/META-INF/jbsa-codec-profile.json'
    profile_document = profile_path.read_bytes().decode('utf-8')
    profile = json.loads(profile_document)
    complete_catalog = catalog.create_catalog(profile, profile_document)
    catalog_binding = write_json(output / 'catalog.json', complete_catalog)
    cases = [case for case in complete_catalog['cases']
             if case['identity']['archive_family_or_layout'] == 'fo4-dx10-v1']
    impact = {'reason': 'FO4 DDS v1 chunking, compression, reconstruction and public random access.',
              'selectors': [{'archive_family_or_layout': 'fo4-dx10-v1'}],
              'case_ids': [case['identity']['case_id'] for case in cases]}
    write_json(output / 'impact.json', impact)
    library = single_artifact('jbsa', '.jar')
    cli = single_artifact('jbsa-cli', '.jar')
    benchmark = single_artifact('jbsa-benchmarks', '-standalone.jar')
    jvm = {name: binding(args.jdk_home / relative) for name, relative in {
        'release': 'release', 'java': 'bin/java.exe', 'vm_library': 'bin/server/jvm.dll',
        'jfr_tool': 'bin/jfr.exe'}.items()}
    jvm['distribution'] = binding(args.jdk_distribution)
    if any(value is None for value in jvm.values()):
        raise ValueError('Incomplete installed JDK')
    runner.validate_jvm_bytes(jvm, args.jdk_distribution)
    artifacts = [binding(library), binding(cli)]
    inventory = [binding(path) for path in sorted(args.jdk_home.rglob('*')) if path.is_file()]
    inventory += [*artifacts, binding(benchmark), binding(profile_path)]
    candidate = {'launcher': None, 'production_arguments': [], 'worker_arguments': {},
                 'inventory': inventory, 'jvm': jvm, 'codec_profile': binding(profile_path),
                 'codec_profile_sha256': binding(profile_path)['sha256'],
                 'provider_configurations': catalog.codec_mappings(profile),
                 'conformance_artifacts': artifacts, 'jmh_jar': binding(benchmark),
                 'jmh_library_artifact': binding(library)}
    oracle_path = ROOT / 'tests/fixtures/local/oracle/BSArch.exe'
    oracle = {'launcher': binding(oracle_path), 'production_arguments': [], 'worker_arguments': {},
              'inventory': [binding(oracle_path)]}
    if not oracle['launcher'] or oracle['launcher']['sha256'] != runner.ORACLE_SHA256:
        raise ValueError('Pinned oracle missing or changed')
    cv1 = runner.load(ROOT / 'tests/conformance/catalog.json')
    prerequisites = [case['identity']['case_id'] for case in cv1['cases']
                     if case['identity']['archive_family'] in ('fo4-dx10-v1', 'global')
                     and case['identity']['codec'] in ('zlib', 'mixed', 'none', 'stored')]
    xbox_scope = {
        'CV1-fo4-dx10-v1.encode.dds-target-xbox.zlib.xbox-v1',
        'CV1-fo4-dx10-v1.encode.dds-target-mismatch-xbox.zlib.xbox-v1',
        'CV1-fo4-dx10-v1.scenario.dds-reconstruction-selection.zlib.pc-v1'}
    deferred = sorted(set(prerequisites) & xbox_scope) if args.qualification_scope == 'pc-only' else []
    observations = runner.load(args.conformance_report) if args.conformance_report else {}
    cv1_rows = {row['case_id']: row for row in observations.get('cases', observations.get('results', []))}
    passed = {case_id for case_id, row in cv1_rows.items()
              if row.get('result') == 'PASS' and row.get('codec_profile_sha256') == candidate['codec_profile_sha256']
              and row.get('candidate_artifacts') == artifacts}
    missing_cv1 = sorted(set(prerequisites) - passed)
    definitions, readiness = {}, []
    for workload in ('bulk-compressible', 'bulk-incompressible', 'mixed-10k', 'metadata-100k', 'dds-mipmapped'):
        name = workload if workload == 'dds-mipmapped' else workload + '-dds-source'
        manifest_path = ROOT / 'tests/performance/corpus' / (name + '.json.gz')
        document = corpus.read_manifest(manifest_path)
        corpus.validate_manifest(document, require_normative=True)
        source = (args.corpus_root / workload).resolve()
        if not args.skip_source_reverification:
            corpus.verify_materialization(document, source)
        expected = write_json(output / 'expected' / (workload + '.json'), dds_validator.projection(document))
        archive = binding(args.oracle_root / (workload + '.ba2'))
        # Oracle output may only become a qualification input after independent validation.
        archive_validated = False
        archive_error = None
        if archive:
            try:
                dds_validator.inspect_archive(ROOT / archive['path'], document)
                archive_validated = True
            except (ValueError, OSError) as error:
                archive_error = str(error)
                archive = None
        definitions[workload] = {'manifest': binding(manifest_path), 'source': str(source),
                                 'expected': expected, 'archive': archive,
                                 'archive_validated': archive_validated}
        readiness.append({'workload': workload, 'files': document['file_count'],
                          'logical_bytes': document['logical_bytes'], 'manifest': binding(manifest_path),
                          'source_verified': not args.skip_source_reverification, 'oracle_archive': archive,
                          'oracle_archive_independent_validation': archive_validated,
                          'oracle_archive_validation_error': archive_error})
    registrations, statuses = [], []
    python = binding(Path(sys.executable))
    validator_path = ROOT / 'build/performance/dds_validator.py'
    for case in cases:
        item = definitions[case['identity']['workload']]
        registration = {'case_id': case['identity']['case_id'], 'case_sha256': catalog.digest(case),
                        'prerequisites': prerequisites, 'corpus_manifest': item['manifest'],
                        'source_directory': item['source'], 'binary_conformance': False,
                        'validator': {'executable': python, 'inputs': [binding(validator_path)],
                                      'arguments': [str(validator_path), '--manifest', str(ROOT / item['manifest']['path'])],
                                      'timeout_seconds': 600}, 'expected_projection': item['expected']}
        blockers = ['shipping launcher and production arguments not available',
                    'environment attestation not supplied']
        if missing_cv1:
            blockers.append('missing exact passed CV1 prerequisites')
        if deferred:
            blockers.append('existing harness still requires three explicitly deferred Xbox/mixed-target cases')
        if not case['identity']['surface'].startswith('pack'):
            registration['oracle_archive'] = item['archive']
            registration['oracle_archive_producer_sha256'] = runner.ORACLE_SHA256
            if not item['archive_validated']:
                blockers.append('validated oracle archive unavailable')
        if case['identity']['surface'].endswith('scaling'):
            blockers.append('shipping CLI has no fixed worker-count selection for 2/4/8/16')
        if case['identity']['surface'].startswith('random'):
            if case['identity']['workload'] in ('bulk-compressible', 'bulk-incompressible'):
                registration['jmh'] = {'archive': item['archive'], 'manifest': item['manifest'],
                                       'oracle_archive_producer_sha256': runner.ORACLE_SHA256}
            else:
                blockers.append('separate fixed-class or 10k companion oracle archive binding not yet supplied')
        registrations.append(registration)
        statuses.append({'case_id': registration['case_id'], 'status': 'PREPARED_NOT_QUALIFIED', 'blockers': blockers})
    configuration = {'catalog': catalog_binding, 'protocol_sha256': runner.file_digest(ROOT / 'tests/performance/protocol.json'),
                     'qualification_scope': args.qualification_scope,
                     'release_version': '0.1.0-SNAPSHOT', 'baseline_required': False,
                     'comparators': {'candidate': candidate, 'oracle': oracle},
                     'conformance_report': binding(args.conformance_report) if args.conformance_report else None,
                     'environment_attestation': {}, 'timeout_seconds': 1200, 'cases': registrations}
    config_binding = write_json(output / 'configuration.incomplete.json', configuration)
    write_json(output / 'readiness.json', {'status': 'PREPARED_NOT_QUALIFIED', 'configuration': config_binding,
               'selected_cases': len(cases), 'codec_profile_sha256': candidate['codec_profile_sha256'],
               'runtime_bytes_verified_against_distribution': True, 'corpora': readiness,
               'required_cv1_cases': prerequisites, 'missing_passed_cv1_cases': missing_cv1,
               'qualification_scope': args.qualification_scope,
               'explicitly_deferred_xbox_cases': deferred,
               'required_pc_and_global_cases': sorted(set(prerequisites) - set(deferred)),
               'scope_note': 'The existing harness prerequisite check is unchanged; deferred cases are not waived.',
               'cases': statuses})
    print(json.dumps({'configuration': config_binding['path'], 'cases': len(cases),
                      'required_cv1': len(prerequisites), 'missing_cv1': len(missing_cv1),
                      'status': 'PREPARED_NOT_QUALIFIED'}))


if __name__ == '__main__':
    main()
