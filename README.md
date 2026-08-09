# 比亚迪方向盘按键映射 App

无 root 方案，通过 Android 无障碍服务全局捕获方向盘按键，实现：
- **360 影像界面下**：音量± / 上下曲 → 切换前/后/左/右视角；长按上下曲 → 前/后广角（**已实车调通**）
- **模式键** → 音乐播放/暂停（**暂缓**：车机 DiLink 不支持通知使用权，暂时无法控制第三方媒体会话；为避免吞掉模式键，该项默认关闭，后续有方案再开启）
- 每条映射可独立开关
- 开机自启 + 前台保活

> ✅ 2026-08-06 已在实车（Android 10 / DiLink，车机 IP 192.168.250.81）完成实测校准，
> 360 影像界面下全部 6 个视角映射验证通过。
>
> ✅ 2026-08-07 实车验证：**App 内置无线 ADB 客户端**，重启后无障碍授权丢失可自动修复，
> 全程无需电脑（首次需在车机弹窗点一次「始终允许」）。已兼容 DiLink 定制 adbd 的
> 特殊签名校验（DigestInfo 哈希=token，非标准 SHA1withRSA）。

---

## 实车实测校准结果（已固化进默认配置）

### 方向盘按键 keycode（scanCode → Android keyCode）

| 按键 | scanCode | keyCode | 说明 |
|------|---------|---------|------|
| 模式键 | 89 | **289** | AUTO_MODE |
| 音量+ | 115 | **291** | AUTO_VOLUME_UP（非标准 24） |
| 音量− | 114 | **292** | AUTO_VOLUME_DOWN（非标准 25） |
| 上一曲 | 268 | **88** | MEDIA_PREVIOUS |
| 下一曲 | 270 | **87** | MEDIA_NEXT |
| 长按上一曲 | 269 | **303** | AUTO_MEDIA_PREVIOUS_LP（独立按键） |
| 长按下一曲 | 271 | **302** | AUTO_MEDIA_NEXT_LP（独立按键） |
| 360 开关 | 288 | 294 | AUTO_VIDEO |

> 音量键/模式键均为 BYD 自定义 keycode，**无障碍层可收到且可吞键**（实车验证：360 内按音量键不会同时调音量）。

### 360 影像 App

- 包名：**`com.byd.avc`**（AutoVideoActivity）
- 视角按钮**无文案**，靠 resource-id 点击：

| 视角 | resource-id |
|------|-------------|
| 前视 | `com.byd.avc:id/hc_front_2d_button` |
| 后视 | `com.byd.avc:id/hc_back_2d_button` |
| 左视 | `com.byd.avc:id/hc_left_2d_button` |
| 右视 | `com.byd.avc:id/hc_right_2d_button` |
| 前广角 | `com.byd.avc:id/hc_front_wide_2d_button` |
| 后广角 | `com.byd.avc:id/hc_back_wide_2d_button` |

### 360 界面检测

实车**收不到** `TYPE_WINDOW_STATE_CHANGED` 窗口切换事件（DIlink 定制 ROM 限制），
`getWindows()` 也返回空；改为每次按键时用 **`rootInActiveWindow` 的包名**判定是否处于
`com.byd.avc`，实测可靠。设置页「检测当前前台」按钮仍可用。

---

## 开发环境

- Android Studio (Hedgehog / Iguana 或更新)
- JDK 17（AGP 8.x 要求）
- Android SDK，compileSdk 34，minSdk 26，targetSdk 31

## 构建方式

```bash
source ~/.sdkman/bin/sdkman-init.sh
export ANDROID_HOME=/Users/mss/Library/Android/sdk
gradle assembleDebug --no-daemon --console=plain
# 产物: app/build/outputs/apk/debug/app-debug.apk
```

安装到车机：

```bash
adb connect 192.168.250.81:5555
adb -s 192.168.250.81:5555 install -r app/build/outputs/apk/debug/app-debug.apk
# 开启无障碍服务（已装时可跳过）：
adb -s 192.168.250.81:5555 shell settings put secure enabled_accessibility_services \
  "<现有值>:com.byd.buttoncontroller/.service.KeyMapAccessibilityService"
adb -s 192.168.250.81:5555 shell settings put secure accessibility_enabled 1
```

## 使用步骤

1. 安装 APK 到车机。
2. 在系统设置里启用「方向盘按键映射」无障碍服务（主界面或「授权助手」有跳转按钮）。
3. 若 DiLink 有自启动管理，放行本 App（「授权助手」有一键跳转）。
4. 主界面按需开关各映射（M1 模式键默认关闭）。

## 授权助手（App 内集成）

主界面右上角菜单 →「授权助手」，提供：

- **状态检测**：ADB 网络调试（5555 端口自探测）、无障碍服务、通知使用权、自启动管理入口。
- **复制 ADB 授权命令**：自动获取车机 IP、读取当前无障碍授权状态，生成完整可执行的
  adb 命令，一键复制后到电脑终端粘贴运行即可完成「连接车机 + 安装 + 授权」。
- **导出 APK**：把当前安装包导出到车机 `/sdcard/Download/`，之后授权命令可改用车机端
  安装（电脑上无需再准备 APK 文件），适合分发到其它车。

> ⚠️ 说明：车机上的 App 无法自行执行 adb（无 root），首次授权必须由电脑执行一次。
> 授权助手负责把命令和状态准备好；也可用仓库根目录的一键脚本代替手动操作：

```bash
./deploy.sh                    # 默认连接 192.168.250.81:5555
./deploy.sh <车机IP> <APK路径>   # 指定 IP 与本地 APK
```


## 重启后无障碍授权丢失怎么办（App 内自动修复）

实车问题：**DiLink 车机重启会清除第三方 App（非预装）的无障碍授权**，表现为每次重启后
方向盘映射失效，需要重新授权。

**解决方案（2026-08-07 实车验证）：App 内置无线 ADB 客户端，自动连接车机自身完成授权修复，无需电脑。**

原理：
- App 内嵌轻量 ADB 客户端（`util/AdbAuth.kt`），开机后（`BootReceiver` 延迟 12 秒）检测
  无障碍授权是否丢失；丢失则用车机自身局域网 IP:5555 连接 adbd，执行
  `settings put secure enabled_accessibility_services ...` 自动恢复。
- 也可以在「授权助手」里点「检测并修复授权」一键修复。

首次使用（只需一次）：
1. 保证车机已开启「ADB 网络调试」（开发者选项，授权助手可检测 5555 端口）。
2. 点击「授权助手 - 检测并修复授权」；车机弹出「允许调试」时**勾选「始终允许」并点允许**。
3. 之后 key 持久化在车机 `/data/misc/adb/adb_keys`，重启后 App 免弹窗自动修复。

技术要点（DiLink 定制 adbd 兼容，均实车验证）：
- **签名**：车机 adbd 校验 SIGNATURE 时直接取 DigestInfo 哈希字段与 token 对比（不再重新
  SHA1），因此签名 = `DigestInfo(哈希=token) + RSA 私钥加密(PKCS1)`，**不是**标准 SHA1withRSA。
- **公钥**：RSAPUBLICKEY 必须用 android_pubkey 编码（524 字节），openssh 格式会被
  adbd 以 `Invalid base64 key` 拒绝。
- **null 终止**：RSAPUBLICKEY payload 必须以 `\0` 结尾（adbd 用 `std::string(payload.data())` 截断）。
- **一次性 shell**：执行命令用 `shell:<cmd>` 而非交互式 shell（交互式不自动退出会超时）。

前提：App 需要加入自启动白名单（车机「自启动管理」→ 允许本 App），否则开机广播收不到、
无法自动修复（可手动点「检测并修复授权」）。

备选（电脑手动恢复）：
```bash
adb -s <车机IP>:5555 shell settings put secure enabled_accessibility_services \
  "$(adb -s <车机IP>:5555 shell settings get secure enabled_accessibility_services | tr -d '\r'):com.byd.buttoncontroller/.service.KeyMapAccessibilityService"
adb -s <车机IP>:5555 shell settings put secure accessibility_enabled 1
```

## 关键日志

```bash
adb logcat -s BYD-BtnMap
```

可观察：360 状态判定、按键吞键/短按触发、360 按钮点击结果等。

## 已知问题与降级

| 问题 | 影响 | 当前状态 |
|------|------|---------|
| M1 需控制第三方媒体会话 | 模式键播放/暂停不可用 | DiLink 不支持通知使用权（系统设置页提示不支持），`getActiveSessions` 无权限；M1 默认关闭、不吞模式键，保留原厂功能。后续可研究：媒体通知 Action 点击、BYD 媒体广播等 |
| 360 检测依赖活跃窗口 | 个别情况下可能误判 | 实测在 360 内按键、退出后按键均正确；极端场景（360 以悬浮窗形式叠加）未覆盖 |

## 模块结构

```
app/src/main/java/com/byd/buttoncontroller/
├── App.kt                      Application 入口，初始化配置仓库
├── config/                     配置层
│   ├── MappingConfig.kt        数据模型 + 实测默认键值/360 resource-id
│   └── ConfigRepository.kt     DataStore 持久化 + StateFlow 暴露
├── action/
│   └── ActionExecutor.kt       播放/暂停 + 360 视角按钮点击（按 resource-id / 文案）
├── service/
│   ├── KeyMapAccessibilityService.kt  核心：按键捕获+吞键+360检测+动作执行
│   ├── MediaControlNotificationListener.kt  通知监听（M1 备用，ROM 暂不支持）
│   └── KeepAliveService.kt     前台保活 + 开机自启载体
├── receiver/
│   └── BootReceiver.kt         开机自启
├── ui/
│   ├── MainActivity.kt         映射开关列表 + 无障碍/通知权限状态
│   ├── SettingsActivity.kt     可配置项编辑页
│   └── MappingAdapter.kt       列表适配器
└── util/
    ├── AppLog.kt               统一日志
    ├── AccessibilityUtil.kt    无障碍开关检测
    ├── AdbAuth.kt              内嵌轻量 ADB 客户端（认证 + 执行 shell）
    ├── AuthRepairer.kt         授权检测/自动修复（无线 ADB 连车机自身）
    └── AdbHelper.kt            本机 IP / 端口探测
```
