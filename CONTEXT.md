# Bethesda Archive Compatibility

This context describes the Bethesda archive formats and the compatibility criteria used to reproduce their behavior from the pinned reference snapshot.

## Language

**Bethesda Archive**:
A BSA or BA2 container belonging to a game- and payload-specific archive family supported by the reference snapshot.
_Avoid_: Package, bundle

**Archive Family**:
A distinct Bethesda Archive encoding identified by its game generation, container version, and payload kind, such as TES3, SSE BSA, Fallout 4 General BA2, or Starfield DDS BA2.
_Avoid_: Game format, archive type

**Reference Snapshot**:
The exact revision pinned in the read-only `TES5Edit` submodule and used as the primary source of archive and CLI behavior.
_Avoid_: Upstream, latest TES5Edit

**Conformance Oracle**:
The locally provisioned BSArch executable whose identity is fixed by digest and whose behavior is used for differential and golden testing against the Reference Snapshot.
_Avoid_: Reference binary, latest BSArch

**Decode Conformance**:
For a supported Bethesda Archive, reproducing the reference entry names, metadata, and uncompressed entry bytes exactly.
_Avoid_: Read compatibility

**Encode Conformance**:
Producing a Bethesda Archive accepted by the relevant game and decoded equivalently to the intended entries; byte identity is an additional requirement only for designated deterministic cases.
_Avoid_: Write compatibility

**Binary Conformance**:
Producing byte-for-byte identical archive output to the Reference Snapshot for a designated deterministic input and configuration.
_Avoid_: Exact compatibility
