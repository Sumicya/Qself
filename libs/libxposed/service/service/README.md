# Vendored libxposed/service sources

This directory contains a vendored copy of the `libxposed/service` sources
(service library + interface AIDL), previously consumed as a git submodule
pointing at `https://github.com/libxposed/service`.

The upstream repository has been deleted from GitHub, which broke all builds
relying on the submodule. The sources are vendored here so that the build no
longer depends on the dead remote. They correspond to the libxposed service
API version 100 (see `IXposedService.aidl`), which is the version this
project was pinned to via the old submodule commit.

- Upstream: https://github.com/libxposed/service (deleted; Apache License 2.0)
- Mirror used for re-import: https://github.com/Colo-Thor/libxposedService
- Published artifacts (for reference): `io.github.libxposed:service` /
  `io.github.libxposed:interface` on Maven Central (101.0.0+, minSdk 26,
  therefore not directly usable here because this project's minSdk is 24)
