# CV1 case assignments

`catalog.json` is the immutable case assignment manifest, and
`coverage-contract.json` is its independently checked base/targeted coverage inventory.
`objects/sha256` contains digest-addressed project-authored scenario and configuration
descriptors. A descriptor does not substitute for materialized fixture bytes or
executed evidence. Missing dependencies always block the case.

See [the harness guide](../../docs/conformance-harness.md) for commands, adapter
contracts, evidence reporting and the separate qualification gates. Audit this
catalog without executing product behavior:

```powershell
pwsh -File tests/conformance/test-catalog.ps1
```
