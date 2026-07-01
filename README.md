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

To get the status of the container

```bash
sudo ./build/bin/linuxX64/debugExecutable/kontainer-runtime.kexe state test
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
