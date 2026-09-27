# Getting started

## Prerequisites

The runtime needs a Linux host (x86_64 or aarch64).

Most operations require `CAP_SYS_ADMIN` to unshare namespaces, install seccomp filters, and mount inside the container. Every command below assumes `sudo`.

## Install

Grab the latest release binary from GitHub and drop it on `PATH`.

```bash
curl -fsSL https://raw.githubusercontent.com/ternbusty/kontainer-runtime/main/scripts/install.sh | sudo bash
```

Or fetch a specific version.

```bash
curl -fsSL https://raw.githubusercontent.com/ternbusty/kontainer-runtime/main/scripts/install.sh | sudo bash -s -- --version v0.6.0
```

All releases with their assets live at [https://github.com/ternbusty/kontainer-runtime/releases](https://github.com/ternbusty/kontainer-runtime/releases).

## Run a container from the sample bundle

The repository ships a minimal OCI bundle at [`test-bundle/`](https://github.com/ternbusty/kontainer-runtime/tree/main/test-bundle). The rootfs is not checked in, so set it up first.

```bash
mkdir -p test-bundle/rootfs/{proc,dev,sys,tmp}
cp /usr/bin/busybox test-bundle/rootfs/bin/busybox
```

Then run the container.

```bash
# 1. Create the container. State lives under /run/kontainer/<id>.
sudo kontainer-runtime create --bundle test-bundle demo

# 2. Inspect it. Should be in "created" state.
sudo kontainer-runtime state demo

# 3. Start it. The default config runs sleep 30.
sudo kontainer-runtime start demo

# 4. Check that it is running.
sudo kontainer-runtime state demo

# 5. Clean up.
sudo kontainer-runtime kill demo SIGKILL
sudo kontainer-runtime delete demo
```

## Running your own program

Edit [`test-bundle/config.json`](https://github.com/ternbusty/kontainer-runtime/blob/main/test-bundle/config.json) and change `process.args` to whatever binary you want to run inside the container. The binary must be present in the rootfs referenced by `root.path`.

For a more realistic setup, use `containerd` to drive the runtime against a real OCI image. See [containerd integration](containerd.md).

## Running without root (rootless)

The runtime can also run containers as a regular user. Generate a rootless config in a bundle whose `rootfs/` you extracted yourself, then run it without `sudo`.

```bash
kontainer-runtime spec --rootless
kontainer-runtime run demo
```

`spec --rootless` does the same as `runc spec --rootless`. It adds a user namespace that maps your own uid and gid to root in the container, drops the network namespace, bind-mounts the host's `/sys` instead of mounting sysfs, and removes cgroup resources.

- Container state lives under `$XDG_RUNTIME_DIR/kontainer/<id>` instead of `/run/kontainer/<id>`
- When you may not create its cgroup and the spec sets neither `cgroupsPath` nor resource limits, the container runs without a cgroup. Resource limits need a cgroup delegated to you, given as an absolute `cgroupsPath`
- Mapping more ids than your own needs `newuidmap` and `newgidmap` (the `uidmap` package) and matching entries in `/etc/subuid` and `/etc/subgid`
- Ubuntu 23.10 and later restrict unprivileged user namespaces with AppArmor (`kernel.apparmor_restrict_unprivileged_userns=1`). The binary then needs an AppArmor profile that allows `userns`, like the one Ubuntu ships for runc in `/etc/apparmor.d/runc`

## Where things live

Paths that the runtime creates or reads at runtime.

- `/run/kontainer/<id>/state.json` holds the container state (id, status, pid, bundle, annotations)
- `/run/kontainer/<id>/config.json` holds internal runtime config such as the resolved cgroup path
- `/run/kontainer/<id>/.lock` is an advisory lock guarding state.json for concurrent CLI calls
- `/run/kontainer/<id>/notify.sock` is the notify socket the create process listens on for `start`
- `/sys/fs/cgroup/kontainer-runtime/<id>/` is the default cgroup location. A relative `cgroupsPath` in the spec gets nested here. An absolute path is used verbatim.

## Build from source

Building the runtime yourself needs a JDK 21+, `gcc`, and `libseccomp-dev`. See [Contributing → Build](contributing.md#build) for the Gradle commands.
