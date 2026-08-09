[![Kotlin](https://img.shields.io/badge/Kotlin-2.4-blue.svg?style=flat&logo=kotlin)](https://kotlinlang.org)
[![Kotlin Native](https://img.shields.io/badge/Kotlin-Native-green.svg?style=flat&logo=kotlin)](https://kotlinlang.org/docs/native-overview.html)

# Kontainer Runtime

Kontainer Runtime is a low layer container runtime written in Kotlin/Native.

## Documentation

Full documentation is published at [https://ternbusty.github.io/kontainer-runtime/](https://ternbusty.github.io/kontainer-runtime/).

- [Getting started](https://ternbusty.github.io/kontainer-runtime/getting-started/)
- [Architecture](https://ternbusty.github.io/kontainer-runtime/architecture/)
- [containerd integration](https://ternbusty.github.io/kontainer-runtime/containerd/)
- [Contributing](https://ternbusty.github.io/kontainer-runtime/contributing/)

## How to Run

### Build

```bash
./gradlew linkDebugExecutableLinuxX64
```

### Run

To create a container whose name is `test` from a bundle located at `test-bundle`, use the following command

```bash
sudo ./build/bin/linuxX64/debugExecutable/kontainer-runtime.kexe create --bundle test-bundle test
```

To start the container

```bash
sudo ./build/bin/linuxX64/debugExecutable/kontainer-runtime.kexe start test
```

To create and start the container in a single step

```bash
sudo ./build/bin/linuxX64/debugExecutable/kontainer-runtime.kexe run --bundle test-bundle test
```

To get the status of the container

```bash
sudo ./build/bin/linuxX64/debugExecutable/kontainer-runtime.kexe state test
```

To list all containers

```bash
sudo ./build/bin/linuxX64/debugExecutable/kontainer-runtime.kexe list
```

To pause the container

```bash
sudo ./build/bin/linuxX64/debugExecutable/kontainer-runtime.kexe pause test
```

To resume the container

```bash
sudo ./build/bin/linuxX64/debugExecutable/kontainer-runtime.kexe resume test
```

To update the container's resource limits

```bash
sudo ./build/bin/linuxX64/debugExecutable/kontainer-runtime.kexe update --memory 134217728 --pids-limit 100 test
```

To get a snapshot of the container's resource usage

```bash
sudo ./build/bin/linuxX64/debugExecutable/kontainer-runtime.kexe events --stats test
```

To list processes in the container

```bash
sudo ./build/bin/linuxX64/debugExecutable/kontainer-runtime.kexe ps test
```

To execute a process in the container

```bash
sudo ./build/bin/linuxX64/debugExecutable/kontainer-runtime.kexe exec test -- sh -c "echo hello"
```

To stop the container

```bash
sudo ./build/bin/linuxX64/debugExecutable/kontainer-runtime.kexe kill test SIGKILL
```

To delete the container

```bash
sudo ./build/bin/linuxX64/debugExecutable/kontainer-runtime.kexe delete test
```

## License

Apache License 2.0. See [LICENSE](LICENSE) for the full text.
