# FlintLine (OSS) — 极简 TV / Android VPN 客户端

一个基于 **Android `VpnService` + mihomo/Clash 核心**、面向电视盒子（D-pad 遥控）的极简 VPN
客户端。界面只有一个大「上網」开关、一个「設定」（填订阅网址）、一个「選線路」。

不含私有后端集成、不需要 root。**订阅节点由你自己配置。**

## 特性

- Compose for TV 界面，D-pad 焦点导航
- 标准 `VpnService`（用户授权一次），`:vpn` 独立进程运行核心，崩溃不拖累 UI
- 订阅由 mihomo 核心原生下载/校验；空订阅/下载失败自动回落**直连兜底**，绝不因订阅问题断网
- 支持节点切换（读取订阅里的 select 分组）
- 兼容 Android 6.0+（含安卓7 的 Let's Encrypt ISRG 根兼容：内置 CA bundle）

## 使用前你需要准备两样

### 1) 自备 mihomo 核心 `.so`（不随仓库分发）

见 [`../app/src/main/jniLibs/README.md`](../app/src/main/jniLibs/README.md)。把 `libclash.so` +
`libbridge.so`（来自开源 [ClashMetaForAndroid / mihomo](https://github.com/MetaCubeX/ClashMetaForAndroid)）
放进 `arm64-v8a/` 和 `armeabi-v7a/`。

### 2) 你自己的订阅网址

安装后进 App →「設定」→ 填入指向一份 clash 配置 yaml 的 http(s) 订阅链接 → 儲存 → 回首页
按「上網」即可。订阅节点、机场/自建服务器由你自备，本项目不提供任何节点。

## 构建

```sh
JAVA_HOME=<jdk17> ./gradlew :app:assembleRelease   # 需先放好 .so
```

默认 release 关闭 R8（无混淆规则，clone 即可编）。生产分发请自行配置签名与混淆。

## 第三方与许可

- VPN 内核桥接 `com.github.kr328.clash.*` 源自开源项目
  [ClashForAndroid / ClashMetaForAndroid](https://github.com/MetaCubeX/ClashMetaForAndroid)（kr328 等），
  版权与许可归其原作者，随核心一并遵循其 License。
- 其余代码为本项目抽壳精简部分。

## 远程桌面（Phase 2，已实现）

局域网内用**浏览器**远程看屏 / 操控本机，全程 **rootless**、需你**显式授权**、**默认关闭**：

- **看屏**：Android `MediaProjection` 投屏（需你在系统弹窗里同意）→ 缩放编码为 JPEG →
  内建裸 Socket HTTP 服务以 MJPEG 推流。API 21+ 可用。
- **操控**：`AccessibilityService.dispatchGesture` 注入点按/滑动、`performGlobalAction`
  做返回/主页/最近、聚焦控件写文本。**需 API 24+**；低于 24 只能看屏。需你去系统
  「无障碍」里手动开启本服务。**rootless 无法注入 D-pad 方向键**，这是无障碍方案的固有限制。
- **访问**：进 App →「設定」→「遠程桌面」→ 开启（授权投屏）→ 页面显示
  `http://<局域网IP>:8623/?token=<PIN>`，在浏览器打开。**强制 PIN 令牌**，不符一律 403。

> ⚠️ 安全：无 TLS、PIN 是局域网内的最低门槛。**仅限可信局域网、风险自负**；任何知道地址与 PIN
> 的人都能看屏并操控本机。不用时请「停止」。本功能**不连任何后端、不会开机自启**。
