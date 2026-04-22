# LightTerm Deployment

## 项目声明

- 本项目主要由 AI 辅助编写，定位为娱乐性、实验性 Android 项目。
- 当前发布资产以体验和演示为主，不建议直接用于生产环境。
- 当前仓库默认将 `release` 构建使用 debug keystore 签名，以便直接安装验证；正式发布前应替换为私有签名。

## 1. 本地构建

环境要求：

- JDK 17
- Android SDK 34
- 可用的 `adb`

构建 Debug APK：

```bash
./gradlew assembleDebug
```

构建 Release APK：

```bash
./gradlew assembleRelease
```

输出文件：

```text
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release.apk
```

运行单元测试：

```bash
./gradlew testDebugUnitTest
```

## 2. 本地安装

连接 Android 设备后执行：

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

## 3. 压缩发布资产

为了在 GitHub Release 中分发安装包，可以将生成的 release APK 压缩为 zip：

```bash
mkdir -p dist
cp app/build/outputs/apk/release/app-release.apk dist/LightTerm-release.apk
cd dist
zip -r lightterm-android-v0.1.2-release.zip LightTerm-release.apk
```

生成的压缩包可直接作为 Release 资产上传。

## 4. GitHub Release 发布

示例命令：

```bash
gh release create v0.1.2 \
  dist/lightterm-android-v0.1.2-release.zip \
  --title "LightTerm v0.1.2" \
  --notes "包含命令模板、历史搜索、远端文件管理增强、终端刷新修复、默认直输终端与可选命令栏等改进。当前为实验性发布，release 构建使用 debug keystore 签名。"
```

如果 Release 已存在，可上传或替换资产：

```bash
gh release upload v0.1.2 dist/lightterm-android-v0.1.2-release.zip --clobber
```

## 5. 用户下载与安装

1. 打开仓库 Releases 页面。
2. 下载 `lightterm-android-v0.1.2-release.zip`。
3. 解压得到 `LightTerm-release.apk`。
4. 手动安装或使用 `adb install -r LightTerm-release.apk`。
