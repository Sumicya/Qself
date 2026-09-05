# 开发环境事实与复现指南（refactoring P0 附页）

> 结论先行：**本仓库的权威测试执行环境是 GitHub Actions（`.github/workflows/test.yml`）**。
> 本地跑 `:app:testDebugUnitTest` 需要完整网络（Gradle 发行版 + Maven Central + maven.google.com + JITPack + dl.google.com）。

## 1. 沙箱网络矩阵（2026-09-05 实测）

| 目标 | 状态 | 影响 |
|---|---|---|
| github.com（含 git smart-HTTP） | ✅ 通 | git/gh 可用；子模块克隆可用 |
| api.github.com | ✅ 通 | `gh` CLI 可监控 CI |
| raw.githubusercontent.com / objects.githubusercontent.com | ❌ 断 | 无法下载 GitHub Release 资产（如 JDK tarball） |
| services.gradle.org | ❌ 断 | 无法取 Gradle 发行版 → 本地构建不可能 |
| repo.maven.apache.org / maven.google.com / jitpack.io / api.xposed.info | ❌ 断 | 无法解析依赖 |
| dl.google.com | ❌ 断 | 无法安装 Android SDK |
| deb.debian.org（apt） | ❌ 断 | 无法 `apt install openjdk-*` |
| api.adoptium.net | ❌ 断 | 无法取 JDK |

## 2. 各环境怎么跑测试

- **CI（权威）**：push 任意分支或对 `main` 提 PR → `Unit Test CI` workflow → `./gradlew :app:testDebugUnitTest`。
- **本地（全网络）**：
  ```bash
  git submodule update --init --recursive
  # 确认 ANDROID_HOME 指向含 compileSdk 37 的 SDK，接受 licenses
  ./gradlew :app:testDebugUnitTest
  ```
- **沙箱（本环境）**：仅静态验证（编译期语法自查靠 `javac -version` 不可得时的 code review；测试逻辑执行靠 CI 回传）。

## 3. 沙箱内已做的环境升级尝试（避免重复踩坑）

1. `apt install openjdk-17/21` → 失败（Debian 镜像不可达）。
2. Adoptium API / GitHub Release 直链下载 JDK 21 → 失败（对应域名被断）。
3. 结论：沙箱最多做到「git 操作 + 文档 + 源码静态分析 + CI 触发与监控」，均已就绪。

## 4. GitHub 侧权限矩阵（2026-09-05 实测，阻塞项）

| 能力 | 状态 | 说明 |
|---|---|---|
| push 分支 / 提交普通文件 | ✅ | `arena/01a0718a-qself` 已推送 |
| 创建/修改 `.github/workflows/*` | ❌ 403 | GH App token 缺 `workflows` 权限（`test.yml` 暂存于本地未提交） |
| 触发/查看 Actions 运行 | ❌ | **本 fork 的 Actions 默认禁用**，启用需仓库所有者在网页操作；API 启用需 admin 权限 |

**解除阻塞的最短路径（需仓库所有者操作）**：
1. 在 `https://github.com/Sumicya/Qself/actions` 点击启用 Actions（一次性）；
2. 提交本地已备好的 `.github/workflows/test.yml`（需在 Arena 重连 GitHub 并授予 workflows 权限，由我推送；或所有者自行提交该文件）。

Actions 启用后，即使 `test.yml` 未落地，也可先 `workflow_dispatch` 现有 `push_ci.yml` 于本分支获得编译门禁。
