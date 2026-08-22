# Changelog

## [0.5.1](https://github.com/ternbusty/kontainer-runtime/compare/v0.5.0...v0.5.1) (2026-08-22)


### Bug Fixes

* **ci:** add libc6-dev:arm64 to release workflow crossPkgs ([#101](https://github.com/ternbusty/kontainer-runtime/issues/101)) ([2551df5](https://github.com/ternbusty/kontainer-runtime/commit/2551df58d04427ff7ee5bf0cdfcc223b7858d09f))


### Performance Improvements

* unify exeseal + bootstrap into single re-exec for create/run ([#100](https://github.com/ternbusty/kontainer-runtime/issues/100)) ([d52228c](https://github.com/ternbusty/kontainer-runtime/commit/d52228c5641dcbe5925c3ce52b7177e8eb89002c))

## [0.5.0](https://github.com/ternbusty/kontainer-runtime/compare/v0.4.0...v0.5.0) (2026-08-20)


### Features

* implement runc bats test feature gaps ([#99](https://github.com/ternbusty/kontainer-runtime/issues/99)) ([b9ce8bf](https://github.com/ternbusty/kontainer-runtime/commit/b9ce8bf30316950627c7fffa0c3e2c78e851ae36))


### Bug Fixes

* add arm64 apt sources to release workflow ([#93](https://github.com/ternbusty/kontainer-runtime/issues/93)) ([6dc2fe1](https://github.com/ternbusty/kontainer-runtime/commit/6dc2fe19b46da57db3dcec5ba92b2b554d05adde))

## [0.4.0](https://github.com/ternbusty/kontainer-runtime/compare/v0.3.1...v0.4.0) (2026-08-17)


### Features

* add CVE-2019-5736 mitigation via cloned binary (memfd) ([#82](https://github.com/ternbusty/kontainer-runtime/issues/82)) ([62fd748](https://github.com/ternbusty/kontainer-runtime/commit/62fd7484657967ec080c25e2011b1d13fef683cc))
* add exec flags, run/list commands, device cgroup, sysctl, pause/resume/update/events, PTY ([#78](https://github.com/ternbusty/kontainer-runtime/issues/78)) ([40f3708](https://github.com/ternbusty/kontainer-runtime/commit/40f37083d43ed2a3d0b8947aa6ff1bd40fd5d28e))
* migrate CLI parser to clikt, fix cgroup ESRCH race condition ([#80](https://github.com/ternbusty/kontainer-runtime/issues/80)) ([5c82189](https://github.com/ternbusty/kontainer-runtime/commit/5c82189fe8539f1adbe12ee2fbad08fc41ec0e63))


### Bug Fixes

* add workflow_call trigger to ci.yml for release workflow ([#86](https://github.com/ternbusty/kontainer-runtime/issues/86)) ([5f621b1](https://github.com/ternbusty/kontainer-runtime/commit/5f621b178f1ff0621fcf87858388ef8a63ad0f78))
* **exec:** close remaining gaps from the PR [#75](https://github.com/ternbusty/kontainer-runtime/issues/75) review, on top of main ([#76](https://github.com/ternbusty/kontainer-runtime/issues/76)) ([629b3df](https://github.com/ternbusty/kontainer-runtime/commit/629b3dff121c98fdcd309644e114f36571d81546))


### Performance Improvements

* use overlayfs for zero-copy exeseal (CVE-2019-5736) ([#88](https://github.com/ternbusty/kontainer-runtime/issues/88)) ([9990326](https://github.com/ternbusty/kontainer-runtime/commit/9990326cbbf92643b3cec10edeb2b57cfddf7687))

## [0.3.1](https://github.com/ternbusty/kontainer-runtime/compare/v0.3.0...v0.3.1) (2026-08-01)


### Bug Fixes

* **exec:** apply the container's OCI process security to exec'd processes ([#71](https://github.com/ternbusty/kontainer-runtime/issues/71)) ([019e7ca](https://github.com/ternbusty/kontainer-runtime/commit/019e7ca4a187310eef405d1b4c596218a632026d))
* remove /tmp fixed paths, apply global CLI options correctly, atomic state saves, oomScoreAdj support ([#72](https://github.com/ternbusty/kontainer-runtime/issues/72)) ([e94a796](https://github.com/ternbusty/kontainer-runtime/commit/e94a7969c80bec7fed75663f6299e9340a00b2c0))

## [0.3.0](https://github.com/ternbusty/kontainer-runtime/compare/v0.2.1...v0.3.0) (2026-07-25)


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
