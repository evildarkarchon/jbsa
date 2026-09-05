# Golden storage

Golden objects are immutable exact bytes stored at `sha256/<sha256>.json`. The generated
`index.json` maps logical identities to those content-addressed objects without changing object
identity.

Ordinary verification and generation are read-only with respect to a populated corpus. A golden
replacement must be produced in a separate staging directory, reviewed as untrusted output, and
accompanied by an approved record conforming to `../rebaseline-record.schema.json`. No rebaseline
record exists for the initial objects because they have not replaced an older approved object.
