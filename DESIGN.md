# 设计说明:从 FlintLine 0.2.8 抽壳为开源 VPN 客户端

## 目标

把内部产品 FlintLine 的 **0.2.8** 版本,精简为一个可公开的开源 **VPN 客户端**:
APP 壳 + 标准 VpnService + mihomo 核心 + 用户自配订阅。零私有后端、零遥测、零 root、零翻墙旁路。

## 方法:取壳 + 重写胶水(不是"全量删敏感")

从真实商业码里逐行删敏感,连锁编译错多、且极易漏删导致泄露。改为:**只保留安全可复用子集
(壳 + Clash 内核桥),把连接私有后端的"胶水层"重写干净。**

## KEEP / DROP / 重写

| 处置 | 内容 |
|---|---|
| **原样保留** | `com.github.kr328.clash.*`(Clash 内核桥,开源血统)、`EndpointManager`、`VpnClient`、`VpnPrefs`、`VpnControlProtocol`、`NodeSelectScreen`、`theme/*`(含 TV 焦点)、`util/AppVersion`、`TlsCompat`(安卓7 CA 兼容)、`assets/ca-bundle.pem`、`assets/fallback_direct.yaml` |
| **改写** | `AppConfig`(清空所有私有端点/域名/地区检测,只留 TUN 参数等本地默认值)、`FlintVpnService`(移除 CnBlock 绕过与 PaypayClient,订阅改从 `SubscriptionProvider` 取)、`MainScreen`(移除注册/配额/always-on/远程协助,改为订阅设置 + 连接)、`MainActivity`(移除心跳启动,路由改 home/settings/node)、`FlintLineApp`(移除 root 与远程配置拉取,保留崩溃自愈 + CA + TLS) |
| **新增** | `SubscriptionProvider`(用户订阅 URL 持久化;下载校验交给 mihomo 核心)、`SettingsScreen`(填订阅 URL) |
| **丢弃** | NativeDomainResolver、PaypayClient、FlintHeartbeat{Client,Service}、DeviceFingerprint、FlintIdentity、AlwaysOnController、CnBlockBypass、RootShell、AdbTunnelClient/RemoteAccess/RemoteInput、AdbEnsurer/LocalAdbClient/DebugProps、OtaManager、Watchdog、Boot/PackageReplacedReceiver、DeviceInfoScreen、RemoteHelpScreen、`jni-bridge/`(domain-tools 桥源码)、`libdr/libbridge?`... 仅保留 mihomo 的 libclash/libbridge |

## 关键设计点

- **订阅下载不重复造轮子**:mihomo 核心的 `Clash.fetchAndValid` 原生支持 clash 订阅格式与节点校验,
  `SubscriptionProvider` 只负责持久化用户填的 URL,不用 OkHttp 再实现一遍。
- **绝不断网**:订阅为空/下载失败 → 回落 `fallback_direct.yaml`(不代理,只保证 TUN 起得来)。
- **无 root**:标准 `VpnService.prepare()` 系统授权一次即可;移除了 root 强制 always-on/lockdown。

## Phase 2

### 远程桌面(已实现,rootless 干净重建)

不再基于原来的 RootShell + 本机 adbd,而是 rootless 方案:
- **看屏**:MediaProjection + VirtualDisplay + ImageReader → JPEG,裸 Socket HTTP 以 MJPEG 推流(`RemoteDesktopService` / `RemoteHttpServer`)。
- **操控**:AccessibilityService `dispatchGesture`(API24+)+ 全局动作 + 文本注入(`RemoteInputAccessibilityService`)。
- **同意与安全**:MediaProjection 需系统弹窗授权、无障碍需手动开启、默认关闭、**强制 PIN 令牌**、仅局域网、无后端、不开机自启。
- **兼容**:API21+ 看屏;API24+ 才能注入手势(dispatchGesture 限制),UI/README 已说明。
- **坐标**:客户端传归一化坐标,服务端乘真实屏幕像素,与抓屏缩放解耦。

### 其余未做

- **OTA**:改为用户自配的更新源,不依赖私有 agent 推送。
