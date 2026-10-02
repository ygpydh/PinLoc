# PinLoc

虚拟定位 Xposed 模块（libxposed API 102）。

- 包名：`com.pinloc.app`
- 版本：1.0.1（versionCode 2）
- 框架：LSPosed / libxposed API 102
- minSdk 26 / targetSdk 34

选点后向系统定位框架返回模拟位置。纯系统级架构，不注入目标 App 进程。

## 功能

- **定位伪装**：选点后向 `LocationManager` 全管线返回假坐标
- **OU 物理抖动**：Ornstein-Uhlenbeck 过程让坐标在目标点周围 ±4m 自然漂移，不会钉死
- **自动参数**：精度自动 5m、速度 0、海拔/方位跟真实，不需要手动填
- **卫星伪装**：反射构造 24 颗多星座 GnssStatus（GPS 12 + BDS 12 + GLO 4 + GAL 4），C/N0 按仰角加权
- **NMEA 改写**：hook `IOnNmeaMessageListener`，重写 `$GPGGA` / `$GPRMC` 的经纬度、定位质量、卫星数
- **反检测**：`isFromMockProvider` 抹除、WifiScan 返空
- **底图**：Carto Voyager 瓦片（WGS-84），高德搜索（GCJ-02 → WGS-84 自动转换）

## 设置

两个开关，都在 App 设置里：

| 开关 | 说明 |
|---|---|
| 定位抖动 | OU 漂移 ±4m，关了就钉死在选点 |
| 卫星伪装 | 24 颗多星座 + NMEA 改写，关了就放行真实卫星 |

底图与搜索：Carto Key 和高德 Key 可留空（Carto 公开瓦片不需要 key；高德搜索需要自己去开放平台申请 Web 服务 Key）。

## 作用域

LSPosed 里勾选：
- `system`（system_server）
- `com.android.phone`
- `com.android.bluetooth`
- `com.android.location.fused` 及各厂商 fused（小米/OPPO/华为/vivo/荣耀）
- `com.google.android.gms`

## 手动构建 APK

```bash
./gradlew :app:assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`
用仓库内 `keystore/debug.keystore` 签名。

## 安装

1. 安装 APK
2. LSPosed → 模块 → 勾选 PinLoc，作用域按上面列表全选
3. 重启手机
4. 打开 App，选点，点播放按钮开始模拟
