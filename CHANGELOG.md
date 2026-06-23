# Changelog

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
