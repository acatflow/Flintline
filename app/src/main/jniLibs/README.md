# jniLibs — 自备 mihomo/Clash 核心（不入库）

本目录的 `*.so` **不提交到 git**（体积大）。clone 后需自行放置，否则运行时 VPN 无法启动
（编译不受影响，`.so` 只在运行时加载）。

每个 ABI 目录（`arm64-v8a/`、`armeabi-v7a/`）需要 2 个：

| 文件 | 来源 |
|---|---|
| `libclash.so` | mihomo / Clash.Meta 核心（Go 编译的 Android 共享库） |
| `libbridge.so` | Clash JNI 桥接（`com.github.kr328.clash.core.bridge`，随核心一同编译） |

这两个库来自开源的 [ClashMetaForAndroid / mihomo](https://github.com/MetaCubeX/ClashMetaForAndroid)
构建产物。请按其构建说明自行编译，或从你信任的发行版取用，放入对应 ABI 目录：

```
app/src/main/jniLibs/arm64-v8a/libclash.so
app/src/main/jniLibs/arm64-v8a/libbridge.so
app/src/main/jniLibs/armeabi-v7a/libclash.so
app/src/main/jniLibs/armeabi-v7a/libbridge.so
```
