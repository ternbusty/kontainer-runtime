# Kontainer Runtime

A low-level OCI-compliant container runtime written in Kotlin/Native.

Kontainer Runtime is a drop-in alternative to [runc](https://github.com/opencontainers/runc), [crun](https://github.com/containers/crun), and [youki](https://github.com/youki-dev/youki). It creates and manages Linux containers per the [OCI Runtime Specification](https://github.com/opencontainers/runtime-spec).

## Why Kotlin/Native

Kotlin/Native compiles to a static native executable with no JVM at runtime. This project exists to demonstrate that Kotlin is a viable language for systems programming. Namespaces, seccomp, cgroups, capability drops, and pivot_root all live in normal Kotlin code, with a thin C bootstrap for the parts of the container lifecycle that must happen before the Kotlin runtime starts.

## Feature status

The runtime passes the [opencontainers/runtime-tools](https://github.com/opencontainers/runtime-tools) validation suite and the [runc integration tests](https://github.com/opencontainers/runc/tree/main/tests/integration) (bats) in CI. Highlights of what works today.

- Namespaces (mount, pid, network, ipc, uts, user, cgroup) with create + join by path
- Rootfs handling covering `pivot_root`, bind mounts, tmpfs, propagation, masked/readonly paths, idmap mounts
- Default `/dev` set (`null`, `zero`, `full`, `random`, `urandom`, `tty`) plus spec.linux.devices
- Cgroups v2 controllers (memory, cpu, pids, hugetlb) and eBPF device cgroup
- Security features (seccomp with `SCMP_ACT_NOTIFY`, capability sets, no_new_privileges, AppArmor / SELinux exec labels)
- CVE-2019-5736 mitigation via exeseal (binary self-cloning)
- PTY / console support with `--console-socket`
- Hooks in both legacy (prestart, poststart, poststop) and modern (createRuntime, createContainer, startContainer) forms
- Sysctl support
- Lifecycle commands (create, start, run, state, list, kill, delete, exec, ps, pause, resume, update, events, spec)
- Platforms: Linux x86_64 and arm64

## Getting started

Read [Getting started](getting-started.md) for a working "hello world" container. The rest of the site covers specific topics.

- [Architecture](architecture.md) documents the two-stage bootstrap and the main/init process split
- [containerd integration](containerd.md) shows how to run the runtime under `ctr`
- [Contributing](contributing.md) covers building, testing, and the code layout
