#!/usr/bin/env bash
# ============================================================
# 比亚迪按键映射 App - 电脑端一键部署/授权脚本
#
# 用法:
#   ./deploy.sh                # 默认连接 192.168.250.81:5555
#   ./deploy.sh <车机IP>       # 指定车机 IP
#   ./deploy.sh <IP> <APK路径> # 指定 IP 与本地 APK 路径（缺省用已构建的 debug APK）
#
# 功能: 连接车机 -> 安装 APK -> 开启无障碍服务 -> 验证
# ============================================================
set -e

IP="${1:-192.168.250.81}"
APK="${2:-app/build/outputs/apk/debug/app-debug.apk}"
PACKAGE="com.byd.buttoncontroller"
SERVICE="$PACKAGE/.service.KeyMapAccessibilityService"

if ! command -v adb >/dev/null 2>&1; then
    echo "❌ 未找到 adb，请先安装 platform-tools（brew install --cask android-platform-tools）"
    exit 1
fi

echo "==> 1/5 连接车机 $IP:5555"
adb connect "$IP:5555"
adb -s "$IP:5555" wait-for-device
echo "    已连接: $(adb -s "$IP:5555" shell getprop ro.build.version.release 2>/dev/null | tr -d '\r')"

if [ -f "$APK" ]; then
    echo "==> 2/5 安装 APK: $APK"
    adb -s "$IP:5555" install -r "$APK"
else
    echo "⚠️  未找到 APK ($APK)，跳过安装；如需更新请先构建或指定 APK 路径"
fi

echo "==> 3/5 开启无障碍服务"
CUR=$(adb -s "$IP:5555" shell settings get secure enabled_accessibility_services 2>/dev/null | tr -d '\r')
if echo "$CUR" | grep -q "$PACKAGE"; then
    echo "    无障碍服务已在列表中，跳过"
else
    if [ -z "$CUR" ]; then NEW="$SERVICE"; else NEW="$CUR:$SERVICE"; fi
    adb -s "$IP:5555" shell settings put secure enabled_accessibility_services "$NEW"
    adb -s "$IP:5555" shell settings put secure accessibility_enabled 1
    echo "    已开启 ($NEW)"
fi

echo "==> 4/5 验证"
adb -s "$IP:5555" shell dumpsys accessibility 2>/dev/null | grep -o "$PACKAGE[^ ]*" | head -1 | sed 's/^/    无障碍服务: /'

echo "==> 5/5 完成 ✅"
echo "    日志查看: adb -s $IP:5555 logcat -s BYD-BtnMap"
echo "    若车机有自启动白名单，请在车机「自启动管理」中放行本 App"
