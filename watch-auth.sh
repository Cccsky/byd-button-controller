#!/usr/bin/env bash
# ============================================================
# 比亚迪按键映射 App - 无障碍授权自动守护脚本（电脑端）
#
# 问题：DiLink 车机重启后会清除第三方 App 的无障碍授权。
# 本脚本在电脑上持续运行，每 15 秒检测一次；一旦发现授权丢失
# （车机重启后）就自动通过 adb 恢复，全程无需手动操作。
#
# 用法:
#   ./watch-auth.sh                    # 默认 10.105.91.81:5555
#   ./watch-auth.sh <车机IP>            # 指定 IP
#   ./watch-auth.sh <IP> <间隔秒数>     # 自定义检测间隔（默认 15）
#
# 要求: 电脑已装 adb；车机开启 ADB 网络调试（5555 端口可达）。
# ============================================================
set -u

IP="${1:-10.105.91.81}"
INTERVAL="${2:-15}"
PACKAGE="com.byd.buttoncontroller"
SERVICE="$PACKAGE/.service.KeyMapAccessibilityService"

if ! command -v adb >/dev/null 2>&1; then
    echo "❌ 未找到 adb，请先安装 platform-tools"
    exit 1
fi

echo "==> 无障碍授权守护启动，目标 $IP:5555，每 ${INTERVAL}s 检测一次"
echo "    按 Ctrl+C 停止"

while true; do
    adb connect "$IP:5555" >/dev/null 2>&1
    CUR=$(adb -s "$IP:5555" shell settings get secure enabled_accessibility_services 2>/dev/null | tr -d '\r')
    if echo "$CUR" | grep -q "$PACKAGE"; then
        echo "[$(date +%H:%M:%S)] 授权正常"
    else
        echo "[$(date +%H:%M:%S)] ⚠️ 检测到授权丢失，正在自动修复..."
        if [ -z "$CUR" ]; then
            NEW="$SERVICE"
        else
            NEW="$CUR:$SERVICE"
        fi
        adb -s "$IP:5555" shell settings put secure enabled_accessibility_services "$NEW"
        adb -s "$IP:5555" shell settings put secure accessibility_enabled 1
        VERIFY=$(adb -s "$IP:5555" shell settings get secure enabled_accessibility_services 2>/dev/null | tr -d '\r')
        if echo "$VERIFY" | grep -q "$PACKAGE"; then
            echo "[$(date +%H:%M:%S)] ✅ 授权已自动恢复"
        else
            echo "[$(date +%H:%M:%S)] ❌ 恢复失败，请检查车机 adb 连接"
        fi
    fi
    sleep "$INTERVAL"
done
