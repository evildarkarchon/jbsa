# CV1 case assignments

> Frozen historical Assurance v2 input. This tree is identified by
> [`tests/assurance/history.json`](../assurance/history.json) and is no longer an
> active gate or rebaseline target. The reviewed legacy digest remains the
> comparison input for [`tests/assurance/plan.json`](../assurance/plan.json).

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
