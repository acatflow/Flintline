# FlintLine (OSS) — 极简 TV / Android VPN 客户端

一个基于 **Android `VpnService` + mihomo/Clash 核心**、面向电视盒子（D-pad 遥控）的极简 VPN
客户端。界面只有一个大「上網」开关、一个「設定」（填订阅网址）、一个「選線路」。

> 本项目由内部产品 FlintLine 的 0.2.8 版本**抽壳精简**而来，已移除所有私有/商业实现：
> 不连任何私有后端、不做设备注册或遥测、不含域名反封锁、不需要 root。**订阅节点由你自己配置。**

## 特性

- Compose for TV 界面，D-pad 焦点导航
- 标准 `VpnService`（用户授权一次），`:vpn` 独立进程运行核心，崩溃不拖累 UI
- 订阅由 mihomo 核心原生下载/校验；空订阅/下载失败自动回落**直连兜底**，绝不因订阅问题断网
- 支持节点切换（读取订阅里的 select 分组）
- 兼容 Android 6.0+（含安卓7 的 Let's Encrypt ISRG 根兼容：内置 CA bundle）

## 使用前你需要准备两样

### 1) 自备 mihomo 核心 `.so`（不随仓库分发）

见 [`app/src/main/jniLibs/README.md`](app/src/main/jniLibs/README.md)。把 `libclash.so` +
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

## 不包含（刻意移除）

私有后端 / 订阅注册 / 流量遥测、domain-tools 动态域名反封锁、中国区检测绕过、
root 提权与强制 always-on/lockdown、远程桌面（adb 隧道/远程输入）、OTA 自更新。
其中「远程桌面」「OTA（用户自配节点）」可作为 Phase 2 另行干净重建，见 [DESIGN.md](DESIGN.md)。
