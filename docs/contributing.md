# Contributing

## Build

```bash
./gradlew linkDebugExecutableLinuxX64   # debug build
./gradlew linkReleaseExecutableLinuxX64 # release build
```

## Test

Unit tests run under Kotest.

```bash
./gradlew kotest
```

Lint.

```bash
./gradlew ktlintCheck   # fail on style violation
./gradlew ktlintFormat  # rewrite in place
```

Integration tests need `sudo` and `containerd`.

```bash
sudo test-scripts/verify-from-host.sh <container-id>
```

OCI runtime-tools validation is the biggest test surface but is expensive to run locally (Linux x86_64 only, needs a GraalVM-scale toolchain). CI runs it on every push. The workflow lives at [`.github/workflows/oci-validation.yml`](https://github.com/ternbusty/kontainer-runtime/blob/main/.github/workflows/oci-validation.yml).

## Repo layout

```
src/nativeMain/kotlin/
├── Main.kt                     # CLI entry point, subcommand wiring
├── command/                    # create / start / state / kill / delete / exec / ps
├── process/                    # MainProcess (parent), InitProcess (PID 1)
├── spec/                       # OCI spec data classes + JSON loader
├── state/                      # state.json I/O with per-container flock
├── rootfs/                     # mount, pivot_root, devices, masked/readonly paths
├── capability/                 # capset/capget orchestration
├── cgroup/                     # cgroup v2 controllers, limit writes
├── namespace/                  # clone flag calculation
├── seccomp/                    # filter compile + notify FD handshake
├── hook/                       # external hook program exec
├── channel/                    # UNIX socket sender/receiver abstractions
├── syscall/                    # thin wrappers, injectable via Syscall interface
├── config/                     # per-container internal config (cgroupPath cache)
├── logger/                     # stderr / file / JSON logging
└── utils/                      # FileSystem interface, JsonCodec

src/nativeTest/kotlin/          # Kotest specs mirroring the above tree
src/nativeInterop/cinterop/
├── bootstrap/bootstrap.c       # stage-1 pre-fork setns / unshare / clone
├── *.def                       # cinterop bindings for headers not in K/N's platform.*
```

## Architectural constraints

Every kernel-facing call goes through `Syscall`. Production is `LinuxSyscall`, tests inject `FakeSyscall`. Don't call `platform.posix.*` from domain code. Put it in the `Syscall` interface first.

`FileSystem` fronts cgroupfs writes. Cgroup and state code writes files via the injected `FileSystem` so tests don't touch the real filesystem.

For the reason `bootstrap.c` stays single-threaded C, see [Architecture → Why the C bootstrap](architecture.md#why-the-c-bootstrap).

## Commit / PR conventions

Conventional Commits. `feat: ...`, `fix: ...`, `docs: ...`, `deps(deps): ...`, `ci(deps): ...`, `refactor: ...`, `test: ...`.

`release-please` opens a release PR based on these types when they land on `main`. `feat` becomes a minor bump, `fix` a patch, and `feat!` or `BREAKING CHANGE:` a major.

PR titles follow the same convention. They are used as the merge commit subject.

Keep PRs focused. We landed the OCI-compliance work as ten separate PRs. Each stood on its own for review.

## Code of Conduct

See [CODE_OF_CONDUCT.md](https://github.com/ternbusty/kontainer-runtime/blob/main/CODE_OF_CONDUCT.md).
