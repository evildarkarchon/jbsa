"""Corroborate DDS archive reconstruction with a digest-pinned Microsoft DirectXTex process."""

import hashlib
import base64
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile


def main():
    """Validate independently reconstructed DDS files and return the semantic archive projection."""
    archive, texdiag = Path(sys.argv[1]), Path(sys.argv[2])
    with texdiag.open('rb') as stream:
        if hashlib.file_digest(stream, 'sha256').hexdigest() != '411c303c98ba73e4423376f717ac139347dd749bf80a7fc1a22368ab1088ff56':
            raise ValueError('DirectXTex executable identity mismatch')
    spec = importlib.util.spec_from_file_location('dds_expectations', Path(__file__).with_name('dds-cv1-expectations.py'))
    scanner = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(scanner)
    raw = archive.read_bytes()
    selection = len(sys.argv) > 3 and sys.argv[3] == 'SELECTION'
    projection = ({'pc':scanner.projection(raw), 'xbox':scanner.projection(raw,'XBOX'),
        'profile_inferred':scanner.projection(raw,'XBOX'), 'profile_explicit_pc':scanner.projection(raw)}
        if selection else scanner.projection(raw))
    files = list(scanner.reconstructed_files(raw).values())
    if selection:
        files.extend(scanner.reconstructed_files(raw, 'XBOX').values())
    # These are validator-owned transient outputs, separate from both product and oracle trees.
    with tempfile.TemporaryDirectory(prefix='jbsa-directxtex-') as directory:
        for ordinal, data in enumerate(files):
            path = Path(directory) / f'{ordinal}.dds'
            path.write_bytes(data)
            command = [str(texdiag), 'info', str(path)]
            observed = subprocess.run(command, capture_output=True, timeout=30)
            # The harness retains stderr verbatim; preserve successful validator evidence too.
            receipt = {'validator':'Microsoft DirectXTex','release':'may2026',
                'executable_sha256':'411c303c98ba73e4423376f717ac139347dd749bf80a7fc1a22368ab1088ff56',
                'invocation':command,'input_sha256':hashlib.sha256(data).hexdigest(),
                'exit_status':observed.returncode,'result':'PASS' if observed.returncode==0 else 'FAIL',
                'stdout_sha256':hashlib.sha256(observed.stdout).hexdigest(),
                'stderr_sha256':hashlib.sha256(observed.stderr).hexdigest(),
                'stdout_base64':base64.b64encode(observed.stdout).decode('ascii'),
                'stderr_base64':base64.b64encode(observed.stderr).decode('ascii')}
            sys.stderr.write(json.dumps(receipt, separators=(',', ':')) + '\n')
            if observed.returncode:
                sys.stderr.buffer.write(observed.stdout + observed.stderr)
                raise ValueError('DirectXTex rejected reconstructed DDS')
    print(json.dumps({'projection': projection}, separators=(',', ':')))


if __name__ == '__main__':
    main()
