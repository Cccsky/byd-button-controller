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
>
> ✅ 2026-09-28 授权机制升级：一次性获得 `WRITE_SECURE_SETTINGS` 权限后，App 直接写
> 系统安全设置恢复无障碍授权，不再依赖 ADB 握手（内嵌 ADB 仅用于首次获取该权限，
> 保留为兜底链路并加重试）。同版更新 App 图标与界面。

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
adb connect 10.43.22.81:5555
adb -s 10.43.22.81:5555 install -r app/build/outputs/apk/debug/app-debug.apk
# 关键一步：授予「写入安全设置」权限（持久有效，之后 App 可自行恢复无障碍授权）
adb -s 10.43.22.81:5555 shell pm grant com.byd.buttoncontroller android.permission.WRITE_SECURE_SETTINGS
# 开启无障碍服务（授予上面权限后，App 也能自己完成这一步）
adb -s 10.43.22.81:5555 shell settings put secure enabled_accessibility_services \
  "<现有值>:com.byd.buttoncontroller/.service.KeyMapAccessibilityService"
adb -s 10.43.22.81:5555 shell settings put secure accessibility_enabled 1
```

## 使用步骤

1. 安装 APK 到车机，执行一次上面的 `pm grant`（或在「授权助手」点「自动获取权限」）。
2. 无障碍服务：拿到写安全设置权限后 App 可自行开启；也可在系统设置手动启用。
3. 若 DiLink 有自启动管理，放行本 App（「授权助手」有一键跳转）。
4. 主界面按需开关各映射（M1 模式键默认关闭）。

## 授权助手（App 内集成）

主界面右上角菜单 →「授权助手」，核心是「① 自动授权权限（只需做一次）」：

- **自动获取权限**：App 内嵌无线 ADB 客户端连接车机自身，执行一次
  `pm grant ... WRITE_SECURE_SETTINGS`（车机弹窗勾选「始终允许」即可）。
- **复制电脑命令**：自动获取车机 IP 生成两条命令，内嵌 ADB 失败时在电脑执行一次即可。
- 附加：无障碍一键修复、通知使用权（可选 M1）、自启动管理入口。

> 也可用仓库根目录的一键脚本代替手动操作（含安装 + pm grant + 开无障碍）：

```bash
./deploy.sh <车机IP> [APK路径]
```

## 重启后无障碍授权丢失怎么办（App 内自动修复）

实车问题：**DiLink 车机重启会清除第三方 App（非预装）的无障碍授权**，表现为每次重启后
方向盘映射失效，需要重新授权。

**解决方案（2026-09-28 升级并实车验证）：App 一次性获得 WRITE_SECURE_SETTINGS 权限后，
直接写系统安全设置恢复授权——不再依赖 ADB 握手，秒级、无弹窗、重启同样生效。**

修复链路（按优先级，见 `util/AuthRepairer.kt`）：
1. 已持有 `WRITE_SECURE_SETTINGS`（`pm grant` 一次即永久持有）→ 直接
   `Settings.Secure` 写入无障碍服务列表并轮询服务绑定（纯本地，无网络依赖）；
2. 未持有时 → 内嵌 ADB 客户端（`util/AdbAuth.kt`）执行一次性 `pm grant`；
3. ADB 兜底 → 仍可用 shell 直接写设置（老链路，带 3 次重试）。

触发时机：开机广播（`BootReceiver`，延迟 12 秒）+ 保活服务每 60 秒守护 + 主界面/
授权助手手动「一键修复」。

首次使用（只需一次，二选一）：
1. 车机上：「授权助手 - 自动获取权限」，车机弹「允许调试」时**勾选「始终允许」并点允许**；
2. 电脑上：`adb shell pm grant com.byd.buttoncontroller android.permission.WRITE_SECURE_SETTINGS`。

内嵌 ADB 的技术要点（DiLink 定制 adbd 兼容，均实车验证）：
- **签名**：车机 adbd 校验 SIGNATURE 时直接取 DigestInfo 哈希字段与 token 对比（不再重新
  SHA1），因此签名 = `DigestInfo(哈希=token) + RSA 私钥加密(PKCS1)`，**不是**标准 SHA1withRSA。
- **公钥**：RSAPUBLICKEY 必须用 android_pubkey 编码（524 字节），openssh 格式会被
  adbd 以 `Invalid base64 key` 拒绝。
- **null 终止**：RSAPUBLICKEY payload 必须以 `\0` 结尾（adbd 用 `std::string(payload.data())` 截断）。
- **一次性 shell**：执行命令用 `shell:<cmd>` 而非交互式 shell（交互式不自动退出会超时）。

前提：App 需要加入自启动白名单（车机「自启动管理」→ 允许本 App），否则开机广播收不到、
无法开机自动修复（保活服务的 60 秒守护与手动「一键修复」不受影响）。

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
