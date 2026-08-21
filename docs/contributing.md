# Contributing

## Build

```bash
./gradlew linkDebugExecutableLinuxX64   # debug build (x86_64)
./gradlew linkReleaseExecutableLinuxX64 # release build (x86_64)
./gradlew linkReleaseExecutableLinuxArm64 # release build (arm64, cross-compile)
```

## Test

Unit tests run under Kotest.

```bash
./gradlew linuxX64Test
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

OCI runtime-tools validation runs on every push in CI. The workflow lives at [`.github/workflows/oci-validation.yml`](https://github.com/ternbusty/kontainer-runtime/blob/main/.github/workflows/oci-validation.yml).

Runc bats compatibility tests run the upstream runc integration test suite against kontainer-runtime. These cover lifecycle commands, exec, cgroups, seccomp (including `SCMP_ACT_NOTIFY`), mounts, idmap, pidfd, and SELinux. The test script is [`scripts/runc-compat-test.sh`](https://github.com/ternbusty/kontainer-runtime/blob/main/scripts/runc-compat-test.sh) and the CI workflow is [`.github/workflows/runc-compat.yml`](https://github.com/ternbusty/kontainer-runtime/blob/main/.github/workflows/runc-compat.yml). SELinux tests run on a Fedora VM via Lima.

## Repo layout

```
src/nativeMain/kotlin/
├── Main.kt                     # CLI entry point (clikt), subcommand wiring
├── command/                    # create / start / run / state / list / kill / delete
│                               #   exec / ps / pause / resume / update / events / spec
├── process/                    # MainProcess (parent), InitProcess (PID 1), PidfdSocket
├── spec/                       # OCI spec data classes + JSON loader
├── state/                      # state.json I/O with per-container flock
├── rootfs/                     # mount, pivot_root, devices, masked/readonly paths, idmap
├── capability/                 # capset/capget orchestration
├── cgroup/                     # cgroup v2 controllers, limit writes, eBPF device cgroup
├── namespace/                  # clone flag calculation
├── seccomp/                    # filter compile + notify FD handshake + listener protocol
├── hook/                       # external hook program exec
├── channel/                    # UNIX socket sender/receiver abstractions
├── console/                    # PTY allocation, master/slave relay, console-socket handoff
├── exeseal/                    # CVE-2019-5736 mitigation (binary self-cloning via overlayfs/memfd)
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
