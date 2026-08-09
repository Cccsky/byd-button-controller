# 本机构建环境安装记录

> 2026-07-29~08-01 为编译、安装、调试本 App 在本机安装的工具与目录清单。
> 目的：Google 官方源（dl.google.com / ghcr.io / services.gradle.org）在本机不可达，全部改用国内镜像搭建，避免重建时重复踩坑。

---

## 一、工具清单总览

| 工具 | 版本 | 安装方式 | 所在目录 | 持久性 |
|------|------|---------|---------|--------|
| adb / platform-tools | 37.0.1 | brew cask | `/opt/homebrew/bin/adb`（软链到 `/opt/homebrew/Caskroom/android-platform-tools/37.0.1/platform-tools/adb`） | ✅ 持久 |
| Android SDK cmdline-tools | 11076708 | 腾讯云镜像下载解压 | `~/Library/Android/sdk/cmdline-tools/latest/` | ✅ 持久 |
| Android SDK platform-34 | 34 (ext12 hack 成 base) | 腾讯云镜像下载解压 | `~/Library/Android/sdk/platforms/android-34/` | ✅ 持久 |
| Android SDK build-tools | 34.0.0 | 腾讯云镜像下载解压 | `~/Library/Android/sdk/build-tools/34.0.0/` | ✅ 持久 |
| **JDK** | **17.0.20** | **sdkman 安装（Zulu）** | **`~/.sdkman/candidates/java/17.0.20-zulu/`**（`current` 链接指向） | ✅ 持久 |
| **Gradle** | **8.7** | **sdkman 管理（手动从腾讯镜像部署）** | **`~/.sdkman/candidates/gradle/8.7/`**（`current` 链接指向） | ✅ 持久 |
| Gradle 依赖缓存 | — | Gradle 自动 | `~/.gradle/`（约 894MB） | ✅ 持久 |

> ✅ 已全部迁移到持久目录（sdkman / brew / SDK），**不再依赖 /tmp**，重启不丢失。
> sdkman 脚本版本 5.22.4，位于 `~/.sdkman/`。

---

## 二、各工具详情

### 1. adb（brew 安装）
```bash
brew install --cask android-platform-tools
# 软链: /opt/homebrew/bin/adb -> .../Caskroom/android-platform-tools/37.0.1/platform-tools/adb
```
> 注：首次尝试时 brew 卡在 auto-update，用 `HOMEBREW_NO_AUTO_UPDATE=1` 跳过即秒装。

### 2. Android SDK（腾讯云镜像手动部署）
根目录：`~/Library/Android/sdk`，三个子目录均从腾讯云镜像 `https://mirrors.cloud.tencent.com/AndroidSDK/` 手动下载 zip 解压。

| 组件 | 包文件 | 解压后落点 |
|------|--------|-----------|
| cmdline-tools | `commandlinetools-mac-11076708_latest.zip` | `~/Library/Android/sdk/cmdline-tools/latest/` |
| build-tools 34 | `build-tools_r34-macosx.zip`（解压内层为 `android-14` 目录） | `~/Library/Android/sdk/build-tools/34.0.0/` |
| platform-34 | `platform-34-ext12_r01.zip`（解压内层为 `android-34-ext12` 目录） | `~/Library/Android/sdk/platforms/android-34/` |

**platform-34 被 hack 成 base platform（关键！）**
腾讯镜像没有 base `platform-34`，只有 ext 版。AGP 默认找 `platforms;android-34`，ext 包会被当成 `android-34-ext12` 而找不到。已做三处改动：
1. `platforms/android-34/package.xml`：`localPackage path` 从 `platforms;android-34-ext12` 改为 `platforms;android-34`
2. `package.xml`：删除了 `<extension-level>12</extension-level>` 和 `<base-extension>false</base-extension>`
3. `platforms/android-34/source.properties`：`AndroidVersion.IsBaseSdk=false` 改为 `true`

> 备份文件：`platforms/android-34/package.xml.bak`、`source.properties.bak`。
> ⚠️ 若日后 SDK 目录被重新安装/覆盖，这三处 hack 会丢失，需重新执行。

### 3. JDK 17（sdkman 管理）
```bash
source ~/.sdkman/bin/sdkman-init.sh
export sdkman_auto_answer=true
sdk install java 17.0.20-zulu
# 已设为 default；Zulu 的 azul CDN 国内可达
```
- 版本：`17.0.20-zulu`（Zulu 17.68+17-CA，LTS）
- 目录：`~/.sdkman/candidates/java/17.0.20-zulu/`
- ⚠️ 备选：腾讯 `17.0.19-kona` 曾尝试但 sdkman 下载源 HTTP2 失败；`services.gradle.org`/部分 vendor 源不可达。

### 4. Gradle 8.7（sdkman 管理，手动部署）
sdkman 自带的 gradle 下载走 services.gradle.org（重定向 AWS），SSL 超时失败。改用**腾讯云 gradle 镜像下载 + 手动放入 sdkman candidate 目录**，仍由 sdkman 管理：
```bash
# 一次性部署（若日后需要重建）
curl -sL -o /tmp/gradle-8.7.zip "https://mirrors.cloud.tencent.com/gradle/gradle-8.7-bin.zip"
mkdir -p ~/.sdkman/candidates/gradle
unzip -q /tmp/gradle-8.7.zip -d /tmp/gradle_extract_sdkman
mv /tmp/gradle_extract_sdkman/gradle-8.7 ~/.sdkman/candidates/gradle/8.7
ln -sfn 8.7 ~/.sdkman/candidates/gradle/current
```
- 目录：`~/.sdkman/candidates/gradle/8.7/`
- `sdk current gradle` / `sdk list gradle` 均已识别。

---

## 三、工程内配置改动（走镜像，已固化在工程中）

| 文件 | 改动 |
|------|------|
| `settings.gradle.kts` | 依赖仓库加了阿里云镜像优先：`https://maven.aliyun.com/repository/google` 和 `.../public`（google maven 不可达） |
| `gradle/wrapper/gradle-wrapper.properties` | `distributionUrl` 改为腾讯云：`https://mirrors.cloud.tencent.com/gradle/gradle-8.7-bin.zip` |
| `local.properties` | `sdk.dir=/Users/mss/Library/Android/sdk`（本地路径，不入 git） |
| `gradle.properties` | 加了 `android.builder.sdkDownload=false`（禁用 AGP 联网下载 SDK） |

---

## 四、构建命令模板

```bash
cd /Users/mss/vibeProject/byd-button-controller
source ~/.sdkman/bin/sdkman-init.sh
export ANDROID_HOME=/Users/mss/Library/Android/sdk
gradle assembleDebug --no-daemon --console=plain
# 产物: app/build/outputs/apk/debug/app-debug.apk (~6.6MB)
```
> JDK/Gradle 已由 sdkman 提供（JAVA_HOME 会随 sdkman-init.sh 自动指向 `~/.sdkman/candidates/java/current`）。

安装到车机：
```bash
adb -s 10.105.91.81:5555 install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 五、JDK / Gradle 重建（已持久化，通常无需）

sdkman 管的两者都在 `~/.sdkman`，重启不丢。仅在 SDKMAN 目录损坏/重装时才需重建：
- JDK：`sdk install java 17.0.20-zulu`
- Gradle：见第四节「Gradle 8.7」的一次性部署命令（腾讯云镜像）。

---

## 六、可清理的遗留文件

本次构建的中间产物，均可删：
- `/tmp/gradle-8.7.zip`、`/tmp/gradle_extract_sdkman/`
- 早期 /tmp 下已删（jdk17raw、gradle-8.7、各 *_extract、zip）

> sdkman 下载缓存：`~/.sdkman/archives/`、`~/.sdkman/tmp/`（保留 gradle-8.7.zip 亦可，用于重建）。

---

## 七、常见故障速查

| 现象 | 原因 | 处理 |
|------|------|------|
| AGP 报 `Failed to find target with hash string 'android-34'` | platform-34 的 package.xml 被改回 / SDK 覆盖 | 重做第二节第 2 条的三处 hack |
| 构建时 `SSL peer shut down incorrectly` | AGP 联网验证 SDK | 确保 `gradle.properties` 有 `android.builder.sdkDownload=false` |
| 依赖下载失败 | 仓库仍指向 google | 确认 `settings.gradle.kts` 有阿里云镜像在前 |
| `sdk install gradle` 超时/SSL 失败 | services.gradle.org 不可达 | 用第四节手动部署方式（腾讯云镜像） |
| `java -version` 回到 8 | 未 source sdkman 或 default 变了 | `source ~/.sdkman/bin/sdkman-init.sh && sdk default java 17.0.20-zulu` |
