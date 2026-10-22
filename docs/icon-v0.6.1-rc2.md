# v0.6.1-rc2：仅替换应用图标

2026-10-22。根据用户收窄后的要求，只采用吉祥物启动图标，不新增封面、关于页、聊天背景、动画或功能入口。图片资源不参与聊天列表更新。

- 正式包：`com.n.watchremote`，versionCode 12 / 0.6.1-rc2。
- 静态黑底图标和 Android adaptive icon；透明前景在构建时缩放到安全区，完整封面未打包。
- `assembleRelease lintRelease` 通过；打包资源检查确认 application icon 已绑定。
- 与现有版本同证书，`adb install -r` 覆盖成功；原应用重新打开显示 ONLINE，未卸载或清除数据。
- 这是图标改动的构建和启动检查，不重复宣称语音或全功能本轮全部重新验收。
- rc1 为 1,095,053 字节，rc2 为 1,210,133 字节，增加 115,080 字节（约 112 KiB）。
- 安装包：`WristCodex-v0.6.1-rc2.apk`。
- SHA-256：`c835c482e8231f96909c7d8fc571ccafc8ca789acead9c20a5ac19c61a148384`。

生成图标和提示词说明见 `design/brand/README.md`。本地安装不等于远端 Release 已发布。
