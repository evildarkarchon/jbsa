# FO3 / FNV / Skyrim LE synthetic BSA vectors

These CC0-1.0, project-authored vectors cover stored, zlib, and mixed entries,
each with and without explicit embedded names, for all three game selectors.
The selectors intentionally produce identical bytes for identical options:
they select the same wire family, not three distinct formats. These fixtures
are synthetic interoperability inputs, not proprietary game assets or evidence
of manual game acceptance.

`manifest.json` records source payloads, game/selector assignments, both generator
digests, wire digests and hexadecimal-file digests. Regenerate with:

```powershell
python build/generate-bsa068-fixtures.py --output target/bsa068-fixtures
```

The independent Python recipe imports no JBSA or xEdit code. Zlib fixture bytes
use Python's provider and make no cross-provider Binary Conformance claim.
The separate scanner validates complete named, contiguous, unshared ASCII
archives. It deliberately rejects noncanonical embedded-name mismatches;
product tests separately establish their tolerated-warning disposition.
