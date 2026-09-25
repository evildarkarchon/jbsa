"""Author materialized DDS CV1 successor fixtures and independent goldens for review."""

import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import struct
import subprocess
import zlib

ROOT = Path(__file__).resolve().parent.parent
PROPOSAL = ROOT / 'docs/reviews/issue40-cv1'
ORACLE = '4c34fe4173a2bd04ba52d5a6357348256ee424573785085fdafaab524cf7b0c2'
DEFERRED_XBOX = {'dds-target-xbox', 'dds-target-mismatch-xbox', 'dds-reconstruction-selection'}


def canonical(value):
    """Use the CV1 canonical object encoding for content-addressed descriptors."""
    return json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(',', ':')).encode()


def digest(path):
    """Hash exact persisted input bytes."""
    return hashlib.sha256(path.read_bytes()).hexdigest()


def bind(path):
    """Bind a repository-relative immutable file."""
    return {'path': path.relative_to(ROOT).as_posix(), 'sha256': digest(path)}


def module(name):
    """Load a build-only independent tool without importing product implementation."""
    spec = importlib.util.spec_from_file_location(name, ROOT / 'build' / (name + '.py'))
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


def object_file(value, directory):
    """Persist a content-addressed object, rejecting a conflicting existing object."""
    content = canonical(value)
    path = directory / (hashlib.sha256(content).hexdigest() + '.json')
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists() and path.read_bytes() != content:
        raise ValueError('Content address conflict')
    path.write_bytes(content)
    return path


def archive(definitions=None, codec='zlib', names=None, tile=0, level=6):
    """Build independent DX10 records with dimensions-derived chunks and explicit codec bytes."""
    definitions = definitions or [(4, 4, 1, 71, False)] * 2
    names = names or [f'textures/{chr(97+i)}.dds'.encode() for i in range(len(definitions))]
    generator = module('generate-dds-fixtures')
    prepared = []
    for index, (width, height, mips, fmt, cube) in enumerate(definitions):
        payload = generator.texture(width, height, mips, fmt, cube)[148:]
        sizes, w, h = [], width, height
        for mip in range(mips):
            if fmt in (71, 72, 80, 81):
                size = ((w + 3)//4) * ((h + 3)//4) * 8
            elif fmt in (74, 75, 77, 78, 83, 84, 95, 96, 98, 99):
                size = ((w + 3)//4) * ((h + 3)//4) * 16
            else:
                size = w*h*(2 if fmt in (85, 86, 49) else 1 if fmt in (65, 61) else 4)
            sizes.append(size)
            w, h = max(1, w//2), max(1, h//2)
        count, w, h = 1, width, height
        while not cube and count < min(mips, 4) and w >= 512 and h >= 512:
            count += 1
            w, h = w//2, h//2
        chunks, position = [], 0
        for mip in range(count):
            size = len(payload)-position if mip == count-1 else sizes[mip]
            data = payload[position:position+size]
            position += size
            selected = 'stored' if codec == 'mixed' and index % 2 else 'zlib' if codec == 'mixed' else codec
            if selected == 'stored':
                packed, encoded = 0, data
            elif selected == 'raw-deflate':
                compressor = zlib.compressobj(wbits=-15)
                encoded = compressor.compress(data) + compressor.flush()
                packed = len(encoded)
            elif selected == 'raw-lz4':
                encoded = bytes([len(data) << 4]) + data
                packed = len(encoded)
            elif selected == 'lz4-frame':
                encoded = bytes.fromhex('04224d18604082') + struct.pack('<I', 0x80000000 | len(data)) + data + bytes(4)
                packed = len(encoded)
            else:
                encoded = zlib.compress(data, level)
                packed = len(encoded)
            chunks.append((packed, size, mip, mips-1 if mip == count-1 else mip, encoded))
        prepared.append((width, height, mips, fmt, cube, chunks))
    records, payloads = bytearray(), bytearray()
    start = 24 + sum(24 + 24*len(item[-1]) for item in prepared)
    crc = lambda value: zlib.crc32(value.lower(), 0xffffffff) ^ 0xffffffff
    for name, (width, height, mips, fmt, cube, chunks) in zip(names, prepared):
        folder, _, leaf = name.replace(b'/', b'\\').rpartition(b'\\')
        stem, _, extension = leaf.rpartition(b'.')
        records.extend(struct.pack('<I4sIBBHHHBBBB', crc(stem), extension[:4].lower().ljust(4,b'\0'),
            crc(folder), 0, len(chunks), 24, height, width, mips, fmt, int(cube), tile))
        for packed, size, first, last, encoded in chunks:
            records.extend(struct.pack('<qIIHHI', start+len(payloads), packed, size, first, last, 0xbaadf00d))
            payloads.extend(encoded)
    return (struct.pack('<4sI4sIq', b'BTDX', 1, b'DX10', len(prepared), start+len(payloads))
        + records + payloads + b''.join(struct.pack('<H', len(name))+name for name in names))


def recipe(name, level=6):
    """Materialize the exact original coverage scenario without depending on JBSA output."""
    if name.startswith('base-fo4-dx10-v1-'):
        return archive(codec=name.removeprefix('base-fo4-dx10-v1-'), level=level)
    if name.startswith('dds-target-'):
        raw = module('generate-dds-fixtures').texture(4, 4, 1, 71)
        xbox = name in ('dds-target-xbox', 'dds-target-mismatch-pc')
        if xbox:
            raw = raw[:84] + b'XBOX' + raw[88:148] + struct.pack('<IIII', 7, 0, 8, 10705) + raw[148:]
        return raw
    formats = (71,72,74,75,77,78,80,81,83,84,95,96,98,99,28,29,87,91,88,93,85,86,49,65,61)
    scenarios = {
        'dds-bc8-boundaries': [(1,1,1,71,False),(5,7,3,80,False)],
        'dds-bc16-boundaries': [(1,1,1,74,False),(5,7,3,98,False)],
        'dds-cubemaps': [(8,8,4,71,True)],
        'dds-dimensions-mips': [(1,1,1,71,False),(8,4,4,28,False)],
        'dds-nonsquare-mips': [(1024,8,11,71,False)],
        'dds-odd-multichunk': [(513,515,10,71,False)],
        'dds-partitions': [(512,512,10,71,False),(1024,1024,11,71,False),(2048,2048,12,71,False)],
        'dds-writable-formats': [(8,8,4,f,False) for f in formats],
        'dds-reconstruction': [(8,8,4,f,False) for f in formats],
    }
    if name in scenarios:
        return archive(scenarios[name], level=level)
    if name in ('dds-name-tables','dds-cli-selector','dds-reconstruction-selection'):
        return archive(level=level)
    names = {
        'malformed-equal-name-identities': [b'textures/a.dds', b'TEXTURES/A.DDS'],
        'malformed-undecodable-wire-names': [b'textures/\x81.dds', b'textures/b.dds'],
        'malformed-unsafe-absolute-names': [b'/textures/a.dds', b'textures/b.dds'],
        'malformed-unsafe-traversal-names': [b'../textures/a.dds', b'textures/b.dds'],
        'malformed-unsafe-name-extraction': [b'../textures/a.dds', b'textures/b.dds'],
        'malformed-windows-invalid-names': [b'textures/CON.dds', b'textures/b.dds'],
    }
    if name in names:
        return archive(names=names[name])
    raw = bytearray(archive())
    if name == 'malformed-harmless-trailing-bytes':
        return bytes(raw) + b'TRAIL'
    changes = {
        'malformed-arithmetic-overflow': (48, 'q', 0x7fffffffffffffff),
        'malformed-decompression-mismatch': (60, 'I', 9),
        'malformed-exact-shared-spans': (96, 'q', 120),
        'malformed-ignorable-constants': (68, 'I', 0),
        'malformed-owning-dispositions': (68, 'I', 0),
        'malformed-illegal-tuples': (37, 'B', 0),
        'malformed-impossible-counts': (12, 'I', 0xffffffff),
        'malformed-missing-name-tables': (16, 'q', 0),
        'malformed-out-of-range-spans': (48, 'q', len(raw)+1),
        'malformed-partial-overlap': (96, 'q', 121),
        'malformed-truncated-spans': (48, 'q', len(raw)-1),
        'malformed-usable-name-hash-mismatch': (24, 'I', 1),
    }
    at, form, value = changes[name]
    struct.pack_into('<'+form, raw, at, value)
    return bytes(raw)


def oracle_input(expected_archive, key, scanner):
    """Create a pinned oracle archive from independently reconstructed canonical PC source DDS."""
    directory = PROPOSAL / 'oracle'
    directory.mkdir(parents=True, exist_ok=True)
    hex_path = directory / (key + '.hex')
    files = scanner.reconstructed_files(expected_archive)
    work = ROOT / 'target' / ('dds-cv1-oracle-' + key)
    work.mkdir(parents=True, exist_ok=True)
    if not hex_path.exists():
        sources = work / 'sources'
        for name, content in files.items():
            source = sources / name
            source.parent.mkdir(parents=True, exist_ok=True)
            source.write_bytes(content)
        output = work / 'oracle.ba2'
        evidence = ROOT / 'target' / ('dds-cv1-oracle-evidence-' + key)
        result = subprocess.run(['pwsh', '-NoProfile', '-File', str(ROOT/'build/run-dds-oracle.ps1'),
            '-Operation','pack','-InputPath',str(sources),'-OutputPath',str(output),
            '-WorkingDirectory',str(work),'-EvidenceDirectory',str(evidence)], capture_output=True, text=True, check=True)
        observation = json.loads(result.stdout)
        hex_path.write_bytes((output.read_bytes().hex()+'\n').encode())
        hex_path.with_suffix('.receipt.json').write_bytes(canonical({'oracle_sha256':ORACLE,
            'archive_sha256':digest(output), 'exit_status':observation['exit_status'],
            'creator':'JBSA project contributors','spdx_license':'CC0-1.0',
            'redistribution_class':'project-authored-redistributable',
            'source':'Pinned oracle packs independently reconstructed synthetic DDS source bytes'}))
    output = work / 'oracle.ba2'
    output.write_bytes(bytes.fromhex(hex_path.read_text()))
    payloads = [{'path':name,'kind':'file','size':len(content),'sha256':hashlib.sha256(content).hexdigest()}
                for name,content in sorted(files.items())]
    return bind(output), scanner.projection(output.read_bytes()), payloads


def prepare():
    """Write a complete unapproved successor catalog without changing active identities."""
    PROPOSAL.mkdir(parents=True, exist_ok=True)
    scanner = module('dds-cv1-expectations')
    active = ROOT/'tests/conformance/catalog.json'
    catalog = copy.deepcopy(json.loads(active.read_text()))
    spec_digest = hashlib.sha256(canonical(catalog['specification_set'])).hexdigest()
    manifest = {'schema_version':1,'generator':{'id':'jbsa-dds-cv1-review-v1','version':'1',
        'implementation':'build/prepare-dds-cv1-review.py'},'fixtures':[],'goldens':[]}
    descriptor = PROPOSAL/'generator.json'
    descriptor.write_bytes(canonical({**manifest['generator'],'source':'docs/spec/formats/dds-ba2.md',
        'spdx_license':'Apache-2.0','parameters':'explicit original CV1 fixture recipe'}))
    fixtures, successors, oracle_cache, deferred = {}, [], {}, []
    for case in catalog['cases']:
        identity = case['identity']
        if identity['archive_family'] != 'fo4-dx10-v1':
            continue
        if identity['fixture'] in DEFERRED_XBOX:
            deferred.append({'case_id':identity['case_id'],
                'reason':'Maintainer deferred Xbox compatibility and its dedicated DDS tooling on 2026-09-08.'})
            continue
        old_id, old = identity['case_id'], identity['fixture']
        token = 'fo4-dds-' + old + '-v1'
        identity['fixture'] = token
        identity['case_id'] = 'CV1-' + '.'.join(identity[k] for k in ('archive_family','operation','fixture','codec','configuration'))
        if old.startswith('base-fo4-dx10-v1-'):
            case['metadata']['base_matrix_cell'] = True
        successors.append({'previous_case_id':old_id,'proposed_case_id':identity['case_id']})
        raw = recipe(old)
        path = PROPOSAL/'fixtures'/(token+'.hex')
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes((raw.hex()+'\n').encode())
        if token not in fixtures:
            fixtures[token] = {'id':token,'input_sha256':hashlib.sha256(canonical({'recipe':old})).hexdigest(),
                'generation':{'recipe':old},'output':{'path':path.relative_to(PROPOSAL).as_posix(),'sha256':digest(path)},
                'creator':'JBSA project contributors','spdx_license':'CC0-1.0',
                'redistribution_class':'project-authored-redistributable'}
            manifest['fixtures'].append(fixtures[token])
        operation, codec = identity['operation'], identity['codec']
        expected_archive = raw
        if operation == 'encode':
            if codec == 'raw-deflate':
                expected = {'exit_status':2,'artifact_exists':False}
            elif codec in ('raw-lz4','lz4-frame'):
                expected = scanner.failure('ba2.unsupported-codec','UNSUPPORTED','PACK')
            elif codec in ('stored','mixed'):
                expected = scanner.failure('dx10.stored-encode','UNSUPPORTED','PACK')
            elif 'mismatch' in old:
                expected = scanner.failure('dds.target-mismatch','UNSUPPORTED','PACK',0)
            else:
                expected_archive = archive([(4,4,1,71,False)], tile=7 if old=='dds-target-xbox' else 0, level=9) if old.startswith('dds-target-') else archive(level=9)
                expected = scanner.projection(expected_archive)
                case['metadata']['expected_behavior'] = 'accept'
                for assertion in ('oracle-to-jbsa','jbsa-to-oracle'):
                    if assertion not in case['metadata']['assertions']:
                        case['metadata']['assertions'].append(assertion)
        elif operation == 'extract':
            expected = scanner.failure('extract.ineligible-name','POLICY','EXTRACT',0)
            expected['diagnostics'].insert(0, scanner.diagnostic('archive-name.traversal-segment',0,
                '..\\textures\\a.dds','..',values={'segmentOrdinal':'0'},operation='EXTRACT'))
        elif old == 'dds-reconstruction-selection':
            case['metadata']['expected_behavior'] = 'accept'
            expected = {'pc':scanner.projection(raw),'xbox':scanner.projection(raw,'XBOX'),
                'profile_inferred':scanner.projection(raw,'XBOX'),'profile_explicit_pc':scanner.projection(raw)}
        elif operation == 'scenario':
            case['metadata']['expected_behavior'] = 'accept'
            if 'decode-semantic-projection' not in case['metadata']['assertions']:
                case['metadata']['assertions'].append('decode-semantic-projection')
            # Input compression is deliberately level 6; canonical production output is level 9.
            expected = scanner.projection(recipe(old, level=9))
        else:
            expected = scanner.projection(raw)
        rejected = 'failure_kind' in expected or 'exit_status' in expected
        if operation in ('encode','extract') and rejected:
            before = [{'path':'input.ba2','kind':'file','size':len(raw),'sha256':hashlib.sha256(raw).hexdigest()}]
            expected.update(filesystem_before=before,filesystem_after=copy.deepcopy(before))
        if 'failure_kind' in expected:
            case['metadata']['assertions'] = ['explicit-rejection' if x=='decode-semantic-projection' else x for x in case['metadata']['assertions']]
        configuration = next(x for x in catalog['tokens']['configuration'] if x['token']==identity['configuration'])
        golden = {'contract':'conformance-v1','case_id':identity['case_id'],
            'configuration_sha256':configuration['sha256'],'specification_sha256':spec_digest,'assertions':[]}
        oracle = None
        if operation=='encode' and not rejected:
            key = 'single' if old.startswith('dds-target-') else 'base'
            if key not in oracle_cache:
                oracle_cache[key] = oracle_input(expected_archive,key,scanner)
            oracle = oracle_cache[key]
            golden.update(oracle_sha256=ORACLE,oracle_archive=oracle[0],source_payloads=oracle[2])
        for assertion in case['metadata']['assertions']:
            value = digest(path) if assertion=='fixture-integrity' else oracle[1] if assertion=='oracle-to-jbsa' and oracle else expected
            golden['assertions'].append({'assertion_id':assertion,
                'kind':'semantic' if assertion in ('decode-semantic-projection','oracle-to-jbsa','jbsa-to-oracle') else 'exact', 'expected':value})
        golden_path = object_file(golden,PROPOSAL/'goldens/sha256')
        manifest['goldens'].append({'id':identity['case_id'],'path':golden_path.relative_to(PROPOSAL).as_posix(),
            'sha256':digest(golden_path),'source_fixture_ids':[token]})
    manifest_path = PROPOSAL/'proposal-manifest.json'
    manifest_path.write_bytes(canonical(manifest))
    for token, fixture in fixtures.items():
        binding = {'state':'available','files':[bind(PROPOSAL/fixture['output']['path'])],
            'generator':{'id':manifest['generator']['id'],'version':'1','descriptor':bind(descriptor),
                'implementation':bind(Path(__file__).resolve()),'recipe_sha256':fixture['input_sha256'],'configuration':fixture['generation']},
            'provenance':{'manifest':bind(manifest_path),'fixture_id':token,'oracle_sha256':None}}
        binding = json.loads(canonical(binding))
        description = 'Proposed independent DDS BA2 v1 scenario ' + token
        token_path = object_file({'kind':'fixture-or-scenario-descriptor','token':token,'description':description,
            'binding':binding,'provenance':{'creator':'JBSA project contributors','spdx_license':'CC0-1.0'}},ROOT/'tests/conformance/objects/sha256')
        catalog['tokens']['fixture'].append({'token':token,'description':description,**bind(token_path),'binding':binding})
        for case in catalog['cases']:
            if case['identity']['fixture']==token:
                case['metadata']['fixture_binding']=binding
                case['metadata']['golden_bindings']=[bind(PROPOSAL/g['path']) for g in manifest['goldens'] if token in g['source_fixture_ids']]
    catalog_path = PROPOSAL/'catalog.json'
    catalog_path.write_bytes(json.dumps(catalog,ensure_ascii=False,separators=(',',':')).encode())
    (PROPOSAL/'review.json').write_bytes(canonical({'status':'UNTRUSTED_PENDING_MAINTAINER_APPROVAL',
        'automated_conformance':False,'approval':None,'active_catalog':bind(active),'proposed_catalog':bind(catalog_path),
        'qualification_scope':'PC DDS only; PC rejection of Xbox input remains covered', 'deferred_cases':deferred,
        'supersessions':successors,'fixture_manifest':bind(manifest_path),
        'authoring_inputs':[bind(ROOT/'build'/name) for name in ('prepare-dds-cv1-review.py','dds-cv1-expectations.py','generate-dds-fixtures.py')]}))
    pending=[]
    for golden in manifest['goldens']:
        case=next(c for c in catalog['cases'] if c['identity']['case_id']==golden['id'])
        binding=case['metadata']['fixture_binding']
        configuration=next(c for c in catalog['tokens']['configuration'] if c['token']==case['identity']['configuration'])
        pending.append({'schema_version':1,'golden_id':golden['id'].lower().replace('.', '-'),'status':'pending-maintainer-review',
            'old_sha256':'0'*64,'new_sha256':golden['sha256'],'source_fixture_sha256s':[f['sha256'] for f in binding['files']],
            'oracle_sha256':None,'generator':{'id':manifest['generator']['id'],'version':'1'},
            'configuration':{'case_configuration':json.loads((ROOT/configuration['path']).read_text()),
                'generator_configuration':binding['generator']['configuration'],'specification_set':catalog['specification_set']},
            'affected_case_ids':[c['identity']['case_id'] for c in catalog['cases']
                if any(g['sha256']==golden['sha256'] for g in c['metadata']['golden_bindings'])], 'approval':None,
            'rationale':'First independently authored expectations for new DDS successor identities.',
            'semantic_difference':'Materialize DDS wire, canonical header, chunk bytes, warnings and rejection observations.'})
    (PROPOSAL/'pending-records.json').write_bytes(canonical(pending))
    print(json.dumps({'case_count':len(successors),'proposed_catalog':bind(catalog_path)}))


if __name__ == '__main__':
    prepare()
