# 蓝牙信标模拟器（iBeacon）

![Build APK](https://github.com/pzzfl/ble-beacon/actions/workflows/build.yml/badge.svg)

把安卓手机变成 iBeacon 信标：开启后持续广播标准 iBeacon 帧，iPhone / 安卓的 Beacon 扫描 App 可直接识别。

## 下载

**https://github.com/pzzfl/ble-beacon/releases/latest** → 下载 `ble-beacon-v*.apk`

也可以在仓库 Actions 里手动触发 `Build APK` 工作流，产物在运行记录的 Artifacts 中。

## 安装

- 文件：`ble-beacon-v1.0.apk`（用 debug 证书签名，可直接安装）
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

## 距离优化（v1.1）

信号弱时按顺序排查：

1. **发射档位**：默认"高（最大）"就是 Android 的物理上限（标称 0 dBm），档位只能往下降，不能超过它。
2. **广播模式**：默认"低延迟（约 100ms）"——同样的功率下，发得越密，扫描端越容易收到。
3. **长距离模式（LE Coded S=8）**：勾选后改用 LE Coded PHY，链路预算多约 12 dB（自由空间约 2–4 倍距离）。
   - 需要扫描端支持 **Coded PHY 扩展扫描**（ESP32-C3 / ESP32-S3 / 支持 BLE5 的安卓机都可以）
   - **iPhone 的 iBeacon 收不到**该模式，只能在安卓 BLE5 扫描器上工作
4. **摆放位置往往比参数更关键**：车身、卷帘门、金属货架会吃掉 10–30 dB，尽量放在靠近开口处、远离金属的位置，别塞进密闭金属箱。

### ESP32-C3 接收示例（NimBLE / ESP-IDF）

```c
// 扩展扫描 + Coded PHY，否则只能收到标准模式的帧
esp_ble_gap_set_ext_scan_params(&(esp_ble_ext_scan_params_t){
    .own_addr_type = BLE_ADDR_TYPE_PUBLIC,
    .filter_policy = BLE_SCAN_FILTER_ALLOW_ALL,
    .scan_duplicate = BLE_SCAN_DUPLICATE_DISABLE,
    .cfg_mask = ESP_BLE_GAP_EXT_SCAN_CFG_CODE_MASK,
    .uncoded_cfg = { BLE_SCAN_TYPE_PASSIVE, BLE_SCAN_WINDOW_ALL, 0x50, 0 },
    .coded_cfg   = { BLE_SCAN_TYPE_PASSIVE, BLE_SCAN_WINDOW_ALL, 0x50, 0 },
});
esp_ble_gap_start_ext_scan(0, 0);
```

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
