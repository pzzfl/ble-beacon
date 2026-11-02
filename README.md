# 蓝牙信标模拟器（iBeacon）

把安卓手机变成 iBeacon 信标：开启后持续广播标准 iBeacon 帧，iPhone / 安卓的 Beacon 扫描 App 可直接识别。

## 安装

- 文件：`app-release.apk`（用 debug 证书签名，可直接安装）
- 手机要求：**Android 5.0+，且支持 BLE 外设广播（peripheral mode）**
- 安装时允许"未知来源应用"

## 使用

1. 首次打开会申请蓝牙广播、通知权限，全部允许
2. 顶部会显示自检结果：`BLE：支持　蓝牙：已开启　外设广播：支持`
   - 若"外设广播：不支持"，这台手机无法当信标，换机测试
3. 填写参数（默认值可直接用）：
   - UUID：`E2C56DB5-DFFB-48D2-B060-D0F5A71096E0`
   - Major / Minor：0–65535
   - 参考功率：1 米处 RSSI，常见 -59 dBm
4. 点"开始广播"，通知栏出现常驻通知（可在通知里直接点"停止"）
5. 用另一台手机验证：
   - 安卓：nRF Connect、Beacon Scanner、Locate Beacon
   - iPhone：Locate Beacon、LightBlue
   - 应能看到 UUID/Major/Minor 与实测 RSSI

## 帧格式

```
厂商数据（Company ID 0x004C = Apple）：
02 15 <UUID 16 字节> <Major 2 字节 BE> <Minor 2 字节 BE> <TX Power 1 字节>
共 23 字节，加 Company ID 与 Flags 后仍在 31 字节传统广播上限内
```

- 广播模式：`ADVERTISE_MODE_LOW_LATENCY`（约 100ms 一次），`ADVERTISE_TX_POWER_HIGH`
- 不可连接（`connectable=false`），符合 iBeacon 规范
- 前台服务类型 `connectedDevice`，Android 14+ 也满足要求

## 已知限制

- **硬件**：部分机型（尤其早期联发科）不支持外设广播，App 会直接提示
- **后台**：Doze 和省电模式会降低广播频率；小米/华为/OPPO 需在系统设置里给本应用"允许后台运行/自启动"，并关闭电池优化
- **并发**：一台手机可同时开多个广播（一般 4–8 条），本 App 只开一条
- **iOS 兼容**：使用标准 iBeacon 帧，iPhone 的 CoreLocation 区域监听可正常触发；不需要 Apple 认证

## 自己编译

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=~/Android/Sdk
~/gradle-8.7/bin/gradle assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

## 目录

```
ble-beacon/
├── settings.gradle.kts
├── build.gradle.kts
├── local.properties          # sdk.dir
└── app/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/xiaozeng/beacon/
        │   ├── MainActivity.kt   # 参数输入、权限、自检
        │   └── BeaconService.kt  # 前台服务 + iBeacon 广播
        └── res/
            ├── layout/activity_main.xml
            └── values/{strings,themes}.xml
```
