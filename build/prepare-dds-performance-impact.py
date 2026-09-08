"""Select every FO4 DX10 v1 performance case from an inventory or bound catalog."""

import argparse
import json
from pathlib import Path


def main():
    """Write the exact family-wide impact union required by performance-v1."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--catalog', type=Path, default=Path('tests/performance/catalog.json'))
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    catalog = json.loads(args.catalog.read_text())
    selected = sorted(case['identity']['case_id'] for case in catalog['cases']
                      if case['identity']['archive_family_or_layout'] == 'fo4-dx10-v1')
    if not selected:
        raise ValueError('Catalog contains no FO4 DDS performance cases')
    impact = {'reason': 'Issue 40 introduces DDS parsing, chunk compression, reconstruction and random access.',
              'selectors': [{'archive_family_or_layout': 'fo4-dx10-v1'}], 'case_ids': selected}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    # An impact manifest is an input binding; never silently replace an existing one.
    with args.output.open('x') as stream:
        json.dump(impact, stream, indent=2)
        stream.write('\n')
    print(json.dumps({'cases': len(selected), 'profile_bound': catalog.get('profiles') is not None}))


if __name__ == '__main__':
    main()
