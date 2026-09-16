# Vendored libxposed/service sources

This directory contains a vendored copy of the `libxposed/service` sources
(service library + interface AIDL), previously consumed as a git submodule
pointing at `https://github.com/libxposed/service`.

The upstream repository has been deleted from GitHub, which broke all builds
relying on the submodule. The sources are vendored here so that the build no
longer depends on the dead remote. They correspond to the libxposed service
API version 100 (see `IXposedService.aidl`), which is the version this
project was pinned to via the old submodule commit.

- Upstream: https://github.com/libxposed/service (deleted; see LICENSE,
  Apache License 2.0)
- The Maven Central artifacts (`io.github.libxposed:service` 101+) are not
  used directly because their declared minSdk is higher than desired at
  vendoring time; compiling the sources in-tree keeps the project's own
  minSdk in charge.
