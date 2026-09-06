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

**解除阻塞的最短路径（需仓库所有者在 Termux 操作，2026-09-05 v2）**：

> v1 的 heredoc 方案作废——从聊天 UI 复制会带入 HTML 转义（`&amp;` 等），且多行粘贴可能被前台进程吃掉。
> v2 已把 workflow 内容提交到本仓库 `docs/refactoring/ci/test.yml`（普通路径不受 workflows 权限限制），所有者只需 `cp` 后提交。
> 命令刻意写成零转义字符（无 `&` `<` `>`），任何复制方式都安全。

**粘贴 A（交互式，单独执行，按提示在浏览器完成设备码授权）：**
```
gh auth login -h github.com -p https -w
gh auth setup-git
```

**粘贴 B（非交互，等 A 完成后整段粘贴）：**
```
gh api -X PUT repos/Sumicya/Qself/actions/permissions -F enabled=true -F allowed_actions=all
git clone --depth 1 -b arena/01a0718a-qself https://github.com/Sumicya/Qself.git
cd Qself
mkdir -p .github/workflows
cp docs/refactoring/ci/test.yml .github/workflows/test.yml
git config user.name "$(gh api user --jq .login)"
git config user.email "$(gh api user --jq .login)@users.noreply.github.com"
git add .github/workflows/test.yml
git commit -m "ci: add unit test workflow"
git push origin arena/01a0718a-qself
gh run list --limit 3
```

Actions 启用后，即使 `test.yml` 未落地，也可先 `workflow_dispatch` 现有 `push_ci.yml` 于本分支获得编译门禁。

## 中继协议 v2（2026-09-06 定稿，教训固化）

**背景**：内联大命令块经聊天链路中继不可靠——实证两次 `git add` 到达时已是 `t add`（agent 输出侧错，非 Termux 粘贴问题），YAML/python 缩进也被变形两次。 giant payload + 多行块 = 高风险。

**规则**：
1. **文件内容绝不内联进命令块**。agent 先把文件直推到暂存路径 `docs/refactoring/ci/`（普通文件，agent token 可推，字节精确）；
2. 用户侧中继只剩 5 条短命令：`git fetch` / `git checkout -B ...` / `cp docs/refactoring/ci/xxx.yml .github/workflows/xxx.yml` / `git add ... ; git commit -m "..."` / `git push`——每条一行、独立代码块、无特殊字符；
3. **一条命令一个代码块**，禁止把多条命令合成一个巨型粘贴块；单行被吃字符时肉眼立刻可见、影响隔离；
4. 命令块发出前 agent 逐行复读一遍（自检）；关键短命令（git add/commit/push）永远单独成块。
