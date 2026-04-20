# LightTerm

LightTerm 是一个原生 Android SSH 客户端，目标是用尽量轻的架构完成多会话管理、终端渲染、服务器配置持久化和移动端友好的快捷交互。

当前实现已经覆盖真实 SSH 连接、保存服务器、跳板机链路、主题/语言切换、快捷键条、终端缩放、重连与连通性测试等核心能力。

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
- `intro.md`
  原始需求与产品方向草稿

## 开发环境

- JDK 17
- Android SDK 34
- 最低支持 Android 8.0 (API 26)
- 推荐使用 Android Studio 打开项目

`local.properties` 不会提交到仓库，需要本地 Android SDK 路径自行配置。

## 常用命令

构建 Debug 包：

```bash
./gradlew assembleDebug
```

运行单元测试：

```bash
./gradlew testDebugUnitTest
```

安装到已连接设备：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 当前状态

- 已完成多会话 SSH 主链路、服务器配置保存、跳板机、主题与语言切换、快捷键条、终端缩放、连通性测试。
- 最近修复包括：
  - 活跃会话页切换主题/语言时的生命周期崩溃
  - `Send` 与输入法发送的重复提交问题

## 文档

- [架构说明](docs/ARCHITECTURE.md)
- [原始需求草稿](intro.md)

## 安全说明

- 密码通过 AndroidX Security Crypto 存储
- SSH 密钥管理接入 Android Keystore
- `local.properties`、Gradle 缓存、构建产物默认不进入仓库
