# 测试与模拟器验证指南

## 1. 本地模拟器（`audiotest_api36`）

- 本机已配置可复用 headless AVD `audiotest_api36`（Pixel 6，Android 16 / API 36，`system-images;android-36.1;google_apis_playstore;arm64-v8a`）。
- 模拟器包与 AVD 分别位于 `~/Library/Android/sdk` 与 `~/.android/avd`；缺失时使用 `sdkmanager` 安装 `emulator` 及上述系统镜像。

```sh
export PATH="$HOME/Library/Android/sdk/platform-tools:$HOME/Library/Android/sdk/emulator:$PATH"
emulator -avd audiotest_api36 -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot &
adb wait-for-device
adb shell 'while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 2; done'

cd Android
export GRADLE_USER_HOME="$HOME/.gradle"
./gradlew :app:connectedDebugAndroidTest --no-configuration-cache \
  -Pandroid.testInstrumentationRunnerArguments.class=com.nanahoshi.audioplayer.data.StoredCatalogOverlayAndroidTest

adb emu kill
```

## 2. 仪器测试与真机边界

- `-Pandroid.testInstrumentationRunnerArguments.class` 传入逗号分隔多个类时只会执行第一个，须逐类运行；不传该参数则运行全部仪器测试（当前共 46 个测试类）。
- `espresso-core` 已升级至 `3.6.1`；若在 API 36+ 仍遇到 Compose UI 反射异常（如 `InputManager.getInstance()` 的 `NoSuchMethodException`），属环境兼容性问题，不要误判为代码回归；验证改动优先使用 Room、迁移及纯逻辑类非 UI 仪器测试。
- 单元测试不得调用 Android 的 `org.json`，解析测试使用适合 JVM 的实现。
- 真机为 Realme RMX5010（沙箱内无法通过 adb 访问）。无论真机或模拟器，安装应用均须用户明确授权；编译通过、模拟器运行结果与真机对通知、存储和系统交互的验证须分别说明，不得互相替代。
