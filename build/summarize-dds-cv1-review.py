"""Retain the DDS case comparison and validator outcomes without granting golden approval."""

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path


def main():
    """Bind the complete raw run and preserve each case's independent qualification status."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--report', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    report = json.loads(args.report.read_text())
    rows = []
    for row in report['results']:
        execution = row['execution']
        rows.append({'case_id':row['case_id'],'comparison_result':row['comparison_result'],
            'reason':execution['reason'],
            'assertions':[{'assertion_id':a['assertion_id'],'result':a['result'],'reason':a.get('reason')}
                          for a in execution['assertions']],
            'validators':[{'result':v['result'],'validator':v['validator'],'invocation':v['invocation'],
                'input':v['input'],'output':v['output'],'error':v['error']}
                for v in execution['validators']],
            'oracle_result':None if execution['oracle'] is None else execution['oracle']['result']})
    summary = {'status':'UNTRUSTED_PENDING_MAINTAINER_APPROVAL','approval':None,
        'automated_conformance':False,
        'comparison_summary':dict(Counter(row['comparison_result'] for row in rows)),
        'case_count':len(rows),'raw_report':{'path':args.report.resolve().relative_to(root).as_posix(),
            'sha256':hashlib.sha256(args.report.read_bytes()).hexdigest()},
        'proposed_catalog_sha256':report['proposed_catalog_sha256'],
        'codec_profile_sha256':report['codec_profile_sha256'],'candidate_artifacts':report['candidate_artifacts'],
        'adapter_registration_sha256':report['adapter_registration_sha256'],
        'java_runtime':report['java_runtime'],'results':rows}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(summary,indent=2)+'\n')
    print(json.dumps(summary['comparison_summary']))


if __name__ == '__main__':
    main()
