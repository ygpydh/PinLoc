# PinLoc

虚拟定位 Xposed 模块（libxposed API 102）。

- 包名：`com.pinloc.app`
- 版本：1.0.0（versionCode 1）
- 框架：LSPosed / libxposed API 102
- minSdk 26 / targetSdk 34

选点后向系统定位框架返回模拟位置。设置里可自定义海拔、精度、速度、方位、卫星 extras；空白则尽量跟真实字段。

## 手动构建 APK

GitHub Actions **不会**在 push 时自动编。需要：

1. 打开仓库 **Actions** → **Build APK**
2. **Run workflow**
3. 完成后：
   - 本次运行的 Artifacts 里有 `PinLoc-x.y.z.apk`
   - 同时发布到 **Releases**（tag `v版本号`，例如 `v1.0.0`）

同一版本再跑一次会覆盖同名 Release。改版本请先改 `app/build.gradle.kts` 里的 `versionName` / `versionCode`。

## 本地构建

```bash
./gradlew :app:assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`  
当前用仓库内 `keystore/debug.keystore` 签名，仅供自用安装。

## 安装

1. 安装 APK
2. LSPosed → 模块 → 勾选 **PinLoc**
3. 重启后再打开 App 选点模拟
