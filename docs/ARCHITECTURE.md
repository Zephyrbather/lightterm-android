# LightTerm Architecture

## 1. 运行时总览

应用启动后由 [`LightTermApp`](../app/src/main/java/com/lightterm/LightTermApp.kt) 创建 `AppContainer`，统一装配以下核心对象：

- `AppSettingsRepository`
- `ServerRepository`
- `SessionManager`
- `ServerConnectivityTester`
- `SecureCredentialStore`
- `SshKeyManager`

`ProcessLifecycleOwner` 会把前后台状态同步给 `SessionManager`，用于调节心跳频率和会话保活策略。

## 2. 分层结构

### `core`

- `core/session`
  负责会话生命周期、SSH 传输层、重连、心跳、延迟与会话标签同步
- `core/terminal`
  负责终端缓冲区、快照与文本渲染数据
- `core/network`
  负责服务器连通性测试
- `core/device`
  负责设备画像与特定机型参数调优

### `data`

- `data/local`
  Room 数据库与 DAO
- `data/repository`
  服务器、应用设置、快捷键仓库
- `data/security`
  密码安全存储与 SSH 密钥管理
- `data/model`
  数据库存储结构与编解码逻辑

### `domain`

- 与业务相关的纯模型定义，如 `ServerConfig`、`JumpHostConfig`、`VirtualKey`

### `ui`

- `ui/main`
  主界面、多标签页、主题/语言菜单、全局快捷键入口
- `ui/session`
  单会话终端页与发送逻辑
- `ui/serverconfig`
  服务器配置编辑、排序、连通性测试
- `ui/widget`
  终端视图与虚拟快捷键条

## 3. 主要数据流

## 3.1 打开会话

1. 用户在 `ServerConfigActivity` 或主界面选择服务器。
2. `SessionManager.openSession(server)` 根据 `server.id` 生成稳定的 `sessionId`。
3. 若已存在同一服务器会话，则更新配置并切换到该会话。
4. 若不存在，则创建新的 `SshSession`，开始连接并发布到 `sessionTabs`。
5. `MainActivity` 通过 `SessionPagerAdapter` 和 `ViewPager2` 展示多个会话页。

涉及文件：

- [`SessionManager.kt`](../app/src/main/java/com/lightterm/core/session/SessionManager.kt)
- [`SshSession.kt`](../app/src/main/java/com/lightterm/core/session/SshSession.kt)
- [`MainActivity.kt`](../app/src/main/java/com/lightterm/ui/main/MainActivity.kt)

## 3.2 SSH 输入输出

1. `SessionFragment` 接收输入框发送、IME 发送或快捷键条事件。
2. 命令通过 `SessionViewModel` 转发到 `SessionManager.sendToSession(...)`。
3. `SshSession.send(...)` 负责补齐换行并写入 `ConnectedShell`。
4. 传输层输出由监听器回流到 `TerminalEmulator`。
5. `SessionUiState` 更新后推动终端界面重绘。

涉及文件：

- [`SessionFragment.kt`](../app/src/main/java/com/lightterm/ui/session/SessionFragment.kt)
- [`SessionViewModel.kt`](../app/src/main/java/com/lightterm/ui/session/SessionViewModel.kt)
- [`MinaSshTransport.kt`](../app/src/main/java/com/lightterm/core/session/MinaSshTransport.kt)
- [`TerminalEmulator.kt`](../app/src/main/java/com/lightterm/core/terminal/TerminalEmulator.kt)

## 3.3 保存服务器配置

1. `ServerConfigViewModel` 聚合表单状态、服务器列表和应用设置。
2. 保存时先做表单校验，再调用 `ServerRepository.saveServer(...)`。
3. 密码交给 `SecureCredentialStore`，普通配置进入 Room。
4. 如果该服务器已有活跃会话，`SessionManager.updateServerConfig(...)` 会同步到运行中的会话。

涉及文件：

- [`ServerConfigViewModel.kt`](../app/src/main/java/com/lightterm/ui/serverconfig/ServerConfigViewModel.kt)
- [`ServerRepository.kt`](../app/src/main/java/com/lightterm/data/repository/ServerRepository.kt)
- [`SecureCredentialStore.kt`](../app/src/main/java/com/lightterm/data/security/SecureCredentialStore.kt)

## 4. 主题、语言与生命周期

主题与语言配置由 `AppSettingsRepository` 管理：

- 主题：纯黑、纯白、系统色
- 语言：中文、英文
- 服务器排序方式：最近使用、名称、添加顺序

`MainActivity` 在切换主题时会执行 `recreate()`，会话页的延迟滚动逻辑需要严格绑定当前 `binding`，否则在页面销毁过程中容易出现生命周期竞态。

相关文件：

- [`AppSettingsRepository.kt`](../app/src/main/java/com/lightterm/data/repository/AppSettingsRepository.kt)
- [`MainActivity.kt`](../app/src/main/java/com/lightterm/ui/main/MainActivity.kt)
- [`SessionFragment.kt`](../app/src/main/java/com/lightterm/ui/session/SessionFragment.kt)

## 5. 安全与持久化

- 服务器配置：Room
- 密码：AndroidX Security Crypto
- SSH Key：Android Keystore 接口
- 应用设置：`SharedPreferences`

数据库入口：

- [`LightTermDatabase.kt`](../app/src/main/java/com/lightterm/data/local/LightTermDatabase.kt)
- [`ServerConfigDao.kt`](../app/src/main/java/com/lightterm/data/local/ServerConfigDao.kt)

## 6. 测试与构建

当前仓库包含基础单元测试，重点覆盖：

- 终端缓冲与渲染
- SSH 传输层
- 虚拟快捷键定义解析

测试目录：

- [`app/src/test/java/com/lightterm`](../app/src/test/java/com/lightterm)

常用命令：

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
```
