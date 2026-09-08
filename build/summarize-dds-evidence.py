"""Retain compact synthetic DDS observations without claiming unexecuted CV1 or PV1 gates."""

import argparse
import hashlib
import json
from pathlib import Path


def digest(path):
    """Hash retained evidence incrementally."""
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def main():
    """Produce a reviewable summary and explicit status for each applicable immutable case."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--report', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--checkpoint', type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    report = json.loads(args.report.read_text())
    catalog = json.loads((root / 'tests/conformance/catalog.json').read_text())
    summary = {'issue': 40, 'kind': 'development-evidence', 'synthetic_only': True,
               'raw_report_sha256': digest(args.report), 'result': report['result'],
               'automated_conformance': False, 'performance_qualification': False,
               'candidate_artifacts': [{'name': Path(p).name, 'sha256': sha}
                                       for p, sha in report['artifacts'].items()],
               'java_sha256': report['java']['sha256'], 'scanner_sha256': report['scanner_sha256'],
               'source_count': len(report.get('source', {})), 'output_bytes': report.get('output_bytes'),
               'payload_and_metadata_errors': report['errors'],
               'reconstruction_bytes_equal': report.get('reconstruction_bytes_equal'),
               'reference_differences': report.get('reference_differences', []),
               'directxtex': {'release': 'may2026', 'sha256': report['directxtex'].get('sha256'),
                             'validated_reconstructions': len(report['directxtex'].get('observations', []))},
               'process_development_seconds': {name: observation['elapsed_ns'] / 1e9
                                              for name, observation in report['observations'].items()},
               'cv1_cases': [{'case_id': case['identity']['case_id'], 'status': 'NOT_EXECUTED_BY_CV1_RUNNER',
                              'missing': ['materialized immutable case bindings', 'reviewed assertion golden',
                                          'registered public adapter execution']}
                             for case in catalog['cases']
                             if case['identity']['archive_family'] == 'fo4-dx10-v1'],
               'pv1_missing': ['bound profile and executable inventory', 'passed DDS CV1 prerequisites',
                               'corpus and independent expected-projection bindings',
                               'idle and no-concurrent-work environment attestation'],
               'limits': ['Three exact oracle header discrepancies follow JBSA-DDS-009; raw byte equality is false.',
                          'Development timings are not normative throughput ratios or memory qualification.',
                          'DirectXTex validates reconstructed DDS; it does not approve CV1 golden replacement.']}
    if args.checkpoint:
        import csv
        with args.checkpoint.open(newline='') as stream:
            summary['checkpoint'] = {'sha256': digest(args.checkpoint), 'rows': list(csv.DictReader(stream))}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(summary, indent=2) + '\n')
    print(json.dumps({'cv1_cases': len(summary['cv1_cases']), 'result': summary['result']}))


if __name__ == '__main__':
    main()
