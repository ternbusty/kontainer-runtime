# Changelog

## [0.3.0](https://github.com/ternbusty/kontainer-runtime/compare/v0.2.1...v0.3.0) (2026-07-01)


### Features

* add exec command to run additional processes in a running container ([#48](https://github.com/ternbusty/kontainer-runtime/issues/48)) ([8ab913a](https://github.com/ternbusty/kontainer-runtime/commit/8ab913afd08199b6c1f7c02eeb100c1652abedbe))
* apply AppArmor profile and SELinux exec label via /proc/self/attr ([#47](https://github.com/ternbusty/kontainer-runtime/issues/47)) ([6edee95](https://github.com/ternbusty/kontainer-runtime/commit/6edee95808270b9697d62552818fa52d953d9955))
* cgroup v2 pids.max and hugetlb limits ([#45](https://github.com/ternbusty/kontainer-runtime/issues/45)) ([4bc1b8f](https://github.com/ternbusty/kontainer-runtime/commit/4bc1b8fe3daf82bb7eb4b171efde4ab0ed15bdb0))
* implement OCI createRuntime / createContainer / startContainer hooks ([#46](https://github.com/ternbusty/kontainer-runtime/issues/46)) ([77a33b7](https://github.com/ternbusty/kontainer-runtime/commit/77a33b72443d49f55dc8dfce262d44dbb0f7701e))
* recognise spec.process.terminal / consoleSize (warns; PTY plumbing TBD) ([#49](https://github.com/ternbusty/kontainer-runtime/issues/49)) ([682eb60](https://github.com/ternbusty/kontainer-runtime/commit/682eb6012264b327551736fd2b207e20c4e6bc5d))


### Bug Fixes

* honour OCI cgroupsPath semantics; nest relative paths under kontainer-runtime/ ([#50](https://github.com/ternbusty/kontainer-runtime/issues/50)) ([e88094e](https://github.com/ternbusty/kontainer-runtime/commit/e88094e978de23002558294c8bdbedcd248fdcc8))


### Documentation

* MkDocs Material site with GitHub Pages workflow ([#58](https://github.com/ternbusty/kontainer-runtime/issues/58)) ([6d2dea3](https://github.com/ternbusty/kontainer-runtime/commit/6d2dea3718d15c07f685abf1a877c173e91b8e09))
* **readme:** bump Kotlin badge from 2.2 to 2.4 to match libs.versions.toml ([#60](https://github.com/ternbusty/kontainer-runtime/issues/60)) ([31e59b2](https://github.com/ternbusty/kontainer-runtime/commit/31e59b2a63907bb63332a477cf3486096aee1dd6))

## [0.2.1](https://github.com/ternbusty/kontainer-runtime/compare/v0.2.0...v0.2.1) (2026-06-23)


### Bug Fixes

* hook timeout via WNOHANG poll + CLOCK_MONOTONIC (not process-wide alarm) ([#43](https://github.com/ternbusty/kontainer-runtime/issues/43)) ([e9ea336](https://github.com/ternbusty/kontainer-runtime/commit/e9ea3363fd7b325fd07cbeaef755fed8c8f00f02))
* serialize state.json reads/writes with per-container flock ([#44](https://github.com/ternbusty/kontainer-runtime/issues/44)) ([9cb7a59](https://github.com/ternbusty/kontainer-runtime/commit/9cb7a59ab23243af74b0521cadf3ab2c796c1238))

## [0.2.0](https://github.com/ternbusty/kontainer-runtime/compare/v0.1.0...v0.2.0) (2026-06-23)


### Features

* bring up loopback interface in container netns ([#41](https://github.com/ternbusty/kontainer-runtime/issues/41)) ([21353ce](https://github.com/ternbusty/kontainer-runtime/commit/21353ce6fd36c5f1c8560e07696e0697ab60fb0c))


### Bug Fixes

* resolve bundle path to absolute via realpath(3) before use ([#40](https://github.com/ternbusty/kontainer-runtime/issues/40)) ([13af970](https://github.com/ternbusty/kontainer-runtime/commit/13af9704ac6a72134f3137437458e3c0bb4a7dd5))
* treat EPERM from close_range as 'use fallback' (seccomp may block it) ([#42](https://github.com/ternbusty/kontainer-runtime/issues/42)) ([c7ed434](https://github.com/ternbusty/kontainer-runtime/commit/c7ed434b7d5c92407a91c5728738f7ac4df6c091))

## 0.1.0 (2026-05-10)


### Bug Fixes

* seccomp behavior ([#15](https://github.com/ternbusty/kontainer-runtime/issues/15)) ([49ee607](https://github.com/ternbusty/kontainer-runtime/commit/49ee6078378a05547fa433652632c86e8a729cde))
* seccomp rule handling with conditional logic ([#16](https://github.com/ternbusty/kontainer-runtime/issues/16)) ([66a0242](https://github.com/ternbusty/kontainer-runtime/commit/66a02425a0508d3b3af452c8a2d1a194a4490f80))


### Code Refactoring

* file utils ([#13](https://github.com/ternbusty/kontainer-runtime/issues/13)) ([c4a0a23](https://github.com/ternbusty/kontainer-runtime/commit/c4a0a23b5ca7ee44f80d8de720deeacfb0956a37))
* introduce Cgroup interface and consolidate cgroupfs operations ([#23](https://github.com/ternbusty/kontainer-runtime/issues/23)) ([1a05e3f](https://github.com/ternbusty/kontainer-runtime/commit/1a05e3f4fee63ad874bb25a510780bb43bc040a4))
* introduce Channel and Notify interfaces with socket-backed impls ([#24](https://github.com/ternbusty/kontainer-runtime/issues/24)) ([2e2eefd](https://github.com/ternbusty/kontainer-runtime/commit/2e2eefd19e48643f06506a1cb2f94f03ab2dfc2f))
* introduce FileSystem interface and thread it through callers ([#22](https://github.com/ternbusty/kontainer-runtime/issues/22)) ([6f98bce](https://github.com/ternbusty/kontainer-runtime/commit/6f98bce907d1e3b215bee018af8193b8cced8571))
* introduce Syscall interface and consolidate wrappers ([#19](https://github.com/ternbusty/kontainer-runtime/issues/19)) ([1f04133](https://github.com/ternbusty/kontainer-runtime/commit/1f04133e1a17c5a56a8b3b959424de3640fac78b))
* move parseSignal into command/Kill.kt ([#18](https://github.com/ternbusty/kontainer-runtime/issues/18)) ([cd96252](https://github.com/ternbusty/kontainer-runtime/commit/cd96252fd7fda99d2319e1116304ca5281f8274d))
* thread Syscall through, migrate remaining direct posix calls, add tests ([#20](https://github.com/ternbusty/kontainer-runtime/issues/20)) ([253cb90](https://github.com/ternbusty/kontainer-runtime/commit/253cb90229188b27f709cfa7aff46189fe16ee79))
* use sibling clone ([#14](https://github.com/ternbusty/kontainer-runtime/issues/14)) ([e0219bd](https://github.com/ternbusty/kontainer-runtime/commit/e0219bd280ac72770fe8798530ee3b2ab82a38d5))
