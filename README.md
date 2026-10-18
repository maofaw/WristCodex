# WristCodex · 腕上遥控

将 Android 手表变成电脑 Codex 的任务遥控器。支持文字任务提交、状态及结果查询、停止任务，以及电脑状态、锁屏、睡眠、网络唤醒和自定义 HTTP 按钮。

## 验证设备

OPPO Watch 4 Pro（OWW221），ColorOS Watch V7.1 / Android 11，32 位 ARM。手表端使用 Android 原生界面，不依赖 GMS，不需要 root 或修改 DPI。Tailscale VPN 已在该设备登录和连接；用户反馈不同网络下遥控接口可返回，具体网络质量和后台保活仍需持续测试。

## 工作方式

手表 → 受口令保护的电脑 HTTP 服务 → 本机 Codex CLI → 状态及结果回传。

模型在电脑侧通过现有 Codex 登录调用，需要互联网；本项目不提供离线模型。每次发送为独立任务，不接管 Codex 桌面版现有聊天。当前一台服务同时处理一个任务。

## 安装

1. 下载本仓库 Releases 的 APK，导入手表安装。
2. Windows 安装 Python 3.10+ 和 Codex，并在电脑执行 `codex login` 登录。
3. 复制 `server/.env.example` 为 `server/.env`，生成并填写至少 16 字符的随机 TOKEN。可用 `py -3 -c "import secrets; print(secrets.token_hex(24))"`。
4. 默认只监听本机。要让手表访问，将 BIND 改为电脑局域网或 Tailscale IP（也可在可信环境使用 0.0.0.0），并按需配置仅允许目标网络访问 TCP 8877 的防火墙规则。
5. 运行 `server/Start-PC.cmd`。在手表连接设置填写 `http://电脑地址:8877` 和相同 TOKEN。网卡 MAC 仅网络唤醒需要。
6. 先查询电脑状态，再进入 Codex 任务页发送任务。电脑确认接收后清空输入；失败保留文字以便重试。状态及结果目前手动刷新。

## 远程连接

推荐两端加入同一个 Tailscale 网络，使用电脑的 Tailscale IP。不需要将端口映射到公网。部分 OPPO 系统提示 VPN 会禁用蓝牙共享网络，关闭 VPN 并重启后恢复；Wi-Fi/手机热点路径需要自行确认。

手表不能打开登录网页时，可在电脑上完成手表生成的设备授权流程。不要分享登录链接或认证口令。

## 边界

- 电脑必须开机、联网且服务运行。暂无开机自启配置。
- Codex 使用 workspace-write 沙箱，默认任务目录为仓库下 workspace/；不自动批准额外权限。
- 任务超时 20 分钟；手表结果截取约 1800 字符，完整结果和日志在 server/ai-results/。
- 停止任务不能回滚已经完成的修改。
- 本地 HTTP 不加密；仅用于可信局域网或 Tailscale 加密隧道，禁止直接暴露公网。
- 当前没有语音、持续会话、自动刷新、多用户隔离或任务队列。

## 开发

- JDK 17+、Android SDK 35、Gradle Wrapper：`./gradlew assembleDebug`（Windows 使用 gradlew.bat）。
- Android 8.0+；CI 生成未签名 Release APK，维护者签名后放入 Releases。正式签名密钥由维护者私下管理，不提交仓库。
- Python 服务仅依赖标准库。测试：在 server/ 目录设置 WATCHREMOTE_TOKEN 为测试用随机值，执行 `python -m unittest test_bridge -v`。
- 可用 WATCHREMOTE_BIND、WATCHREMOTE_PORT、WATCHREMOTE_WORKSPACE、WATCHREMOTE_TOKEN 环境变量覆盖部署配置。

## 来源与许可

基于 [nnyj/wearos-remote](https://github.com/nnyj/wearos-remote) 改造，保留原 MIT 许可证与版权。新增中文原生界面、运行时连接配置、自定义接口、Codex 服务、Tailscale 使用流程及发送成功清空逻辑。

本项目是第三方个人工具，与 OpenAI、OPPO、Tailscale 无隶属关系。使用 Codex 和 Tailscale 仍须遵守各自条款。

后续计划见 ROADMAP.md，版本变更见 CHANGELOG.md，开发协作要求见 CONTRIBUTING.md。
