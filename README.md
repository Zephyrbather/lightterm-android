# LightTerm

LightTerm 是一个原生 Android SSH 客户端实验项目，聚焦多会话管理、终端渲染、服务器配置保存、跳板机链路和移动端快捷交互。

## 项目声明

- 本项目主要由 AI 辅助编写与迭代，定位为娱乐性、实验性项目。
- 本项目不构成生产级 SSH 工具承诺，也没有经过完整安全审计。
- 不建议将本项目直接用于高敏感资产、生产服务器或需要合规保证的环境。

## 功能特性

- 多会话标签页：`SessionManager + ViewPager2 + StateFlow`
- 真实 SSH 连接：基于 Apache MINA SSHD
- 认证方式：密码认证、公钥认证
- 跳板机支持：多跳配置保存与连接测试
- 服务器管理：保存、编辑、删除、排序、最近使用
- 终端体验：环形缓冲区、自定义终端视图、字体缩放、快捷键条
- 稳定性能力：断线重连、前后台心跳、延迟展示
- 个性化：纯黑、纯白、系统色主题，中英文切换
- 演示模式：`PreviewSshTransport`

## 技术栈

- Kotlin
- Android View System
- MVVM + StateFlow
- Room
- AndroidX Security Crypto
- Android Keystore
- Material 3
- Apache MINA SSHD

## 项目结构

- `app/src/main/java/com/lightterm/core`
  会话管理、SSH 传输、终端渲染、设备画像、网络检测
- `app/src/main/java/com/lightterm/data`
  Room、Repository、安全存储
- `app/src/main/java/com/lightterm/ui`
  主界面、会话页、服务器配置页、主题与自定义控件
- `app/src/test/java/com/lightterm`
  单元测试
- `docs/ARCHITECTURE.md`
  运行时结构和主要数据流说明
- `docs/DEPLOYMENT.md`
  构建、安装、打包和 GitHub Release 发布说明

## 使用说明

### 1. 获取安装包

- 直接从 GitHub Releases 下载压缩包：
  `https://github.com/Zephyrbather/lightterm-android/releases`
- 解压后得到 `LightTerm-debug.apk`

### 2. 安装到 Android 设备

使用 `adb` 安装：

```bash
adb install -r LightTerm-debug.apk
```

或将 APK 传到手机后手动安装。

### 3. 应用内基本流程

1. 打开右上角菜单，进入“服务器配置”。
2. 新建或编辑服务器，填写主机、端口、用户名和认证方式。
3. 如需跳板机，开启跳板链路并逐跳填写配置。
4. 保存后回到主界面，使用“打开会话”建立连接。
5. 在会话页通过底部输入框、发送按钮和虚拟快捷键条操作终端。

## 本地开发与编译

开发环境：

- JDK 17
- Android SDK 34
- 最低支持 Android 8.0 (API 26)
- 推荐使用 Android Studio

`local.properties` 不会提交到仓库，需要本地自行配置 Android SDK 路径。

常用命令：

构建 Debug 包：

```bash
./gradlew assembleDebug
```

运行单元测试：

```bash
./gradlew testDebugUnitTest
```

本地安装到已连接设备：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 部署与发布

- 构建与发布步骤见：[docs/DEPLOYMENT.md](docs/DEPLOYMENT.md)
- 当前仓库通过 GitHub Release 分发压缩后的 APK 资产
- Release 资产目前为调试构建，主要用于体验与演示

## 当前状态

- 已完成多会话 SSH 主链路、服务器配置保存、跳板机、主题与语言切换、快捷键条、终端缩放、连通性测试
- 已修复活跃会话页切换主题/语言时的生命周期崩溃
- 已修复 `Send` 与输入法发送的重复提交问题

## 文档

- [架构说明](docs/ARCHITECTURE.md)
- [构建与发布](docs/DEPLOYMENT.md)

## 安全说明

- 密码通过 AndroidX Security Crypto 存储
- SSH 密钥管理接入 Android Keystore
- `local.properties`、Gradle 缓存、构建产物默认不进入仓库
