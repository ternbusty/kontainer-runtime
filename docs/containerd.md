# containerd integration

Point `containerd`'s `ctr` at kontainer-runtime to run real OCI images (Alpine, Ubuntu) through it. The [`test-bundle/`](https://github.com/ternbusty/kontainer-runtime/tree/main/test-bundle) in this repo is enough for smoke tests; `ctr` gets you closer to production.

## Wiring it up

Pass `--runc-binary` to `ctr run`. Each command names the runtime binary. No system-wide change, so the host's real `runc` (relied on by docker, kubelet, other engines) stays intact. CI uses this form in [`.github/workflows/integration.yml`](https://github.com/ternbusty/kontainer-runtime/blob/main/.github/workflows/integration.yml).

```bash
BIN="$PWD/build/bin/linuxX64/debugExecutable/kontainer-runtime.kexe"
sudo ctr run --runc-binary="$BIN" ...
```

## End-to-end example

Run an Alpine image with seccomp, a read-only rootfs, memory and CPU limits, a user namespace, and a non-root uid inside the container.

```bash
BIN="$PWD/build/bin/linuxX64/debugExecutable/kontainer-runtime.kexe"

sudo ctr image pull docker.io/library/alpine:latest

sudo ctr run --rm \
    --runc-binary="$BIN" \
    --seccomp --read-only \
    --memory-limit 134217728 \
    --cpus 0.5 \
    --uidmap "0:100000:65536" --gidmap "0:100000:65536" \
    --user 1000:1000 \
    --hostname my-test-container \
    docker.io/library/alpine:latest \
    test-alpine \
    sh -c 'id; cat /proc/self/status | grep Cap; ls /'
```

## What CI exercises

[`.github/workflows/integration.yml`](https://github.com/ternbusty/kontainer-runtime/blob/main/.github/workflows/integration.yml) runs an Alpine container end-to-end with the same flags shown above. It then runs two verification scripts.

[`test-scripts/verify-from-container.sh`](https://github.com/ternbusty/kontainer-runtime/blob/main/test-scripts/verify-from-container.sh) runs assertions inside the container. It checks uid, gid, hostname, `Cap*` values, and read-only mounts.

[`test-scripts/verify-from-host.sh`](https://github.com/ternbusty/kontainer-runtime/blob/main/test-scripts/verify-from-host.sh) runs assertions from the host against `/proc/<pid>/*` of the container's PID 1.

[`test-scripts/check-expectations.sh`](https://github.com/ternbusty/kontainer-runtime/blob/main/test-scripts/check-expectations.sh) diffs the observed output against a checklist of `EXPECT_*` lines. A missing `EXPECT_*` fails the workflow.

