## Agent skills

### Issue tracker

Issues and specs are tracked in GitHub Issues on `evildarkarchon/jbsa`; `.scratch/` is read-only history. Read `docs/agents/issue-tracker.md` before creating, triaging, or fetching tickets.

### Triage labels

Use the default five canonical triage-label names. See `docs/agents/triage-labels.md`.

### Domain docs

This repository uses a single-context domain-doc layout. See `docs/agents/domain.md`.

## Linux testing through WSL

WSL 2 is available on the development machine (distro `Ubuntu`) for building and testing the
portable code on Linux. Use it to confirm that everything outside the Windows boundaries in
`docs/development/build.md` passes off Windows before claiming portability.

- Clone the repository into the Linux filesystem (for example `~/src/jbsa`) and run Gradle there.
  Do not build from the Windows checkout through `/mnt/c/...`: cross-filesystem I/O over 9P is far
  slower, and NTFS mounted into Linux does not give the POSIX file-key, inode, and permission
  behavior the portable providers are meant to be tested against.
- Move work between the two checkouts through Git (push a branch or fetch from the Windows checkout
  as a remote), not by copying files across `/mnt/c`.
- Invoke commands with `wsl.exe -d Ubuntu -- bash -lc '<command>'`, using `./gradlew` on the Linux
  side.
- A Linux pass verifies portability only; it never qualifies a Windows x64 boundary.

## Restrictions

The `TES5Edit` directory is to be used only for reference and shall not be written to for any reason.
