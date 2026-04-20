# LightTerm Deployment

## 项目声明

- 本项目主要由 AI 辅助编写，定位为娱乐性、实验性 Android 项目。
- 当前发布资产以体验和演示为主，不建议直接用于生产环境。

## 1. 本地构建

环境要求：

- JDK 17
- Android SDK 34
- 可用的 `adb`

构建 Debug APK：

```bash
./gradlew assembleDebug
```

输出文件：

```text
app/build/outputs/apk/debug/app-debug.apk
```

运行单元测试：

```bash
./gradlew testDebugUnitTest
```

## 2. 本地安装

连接 Android 设备后执行：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 3. 压缩发布资产

为了在 GitHub Release 中分发安装包，可以将生成的 APK 压缩为 zip：

```bash
mkdir -p dist
cp app/build/outputs/apk/debug/app-debug.apk dist/LightTerm-debug.apk
cd dist
zip -r lightterm-android-v0.1.0-debug.zip LightTerm-debug.apk
```

生成的压缩包可直接作为 Release 资产上传。

## 4. GitHub Release 发布

示例命令：

```bash
gh release create v0.1.0 \
  dist/lightterm-android-v0.1.0-debug.zip \
  --title "LightTerm v0.1.0" \
  --notes "AI 编写的娱乐性实验版本，包含压缩后的 Debug APK。"
```

如果 Release 已存在，可上传或替换资产：

```bash
gh release upload v0.1.0 dist/lightterm-android-v0.1.0-debug.zip --clobber
```

## 5. 用户下载与安装

1. 打开仓库 Releases 页面。
2. 下载 `lightterm-android-v0.1.0-debug.zip`。
3. 解压得到 `LightTerm-debug.apk`。
4. 手动安装或使用 `adb install -r LightTerm-debug.apk`。
