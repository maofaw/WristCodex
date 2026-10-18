# WristCodex · 腕上遥控

将 Android 手表变成电脑 Codex 的任务遥控器。支持文字任务提交、状态及结果查询、停止任务，以及电脑状态、锁屏、睡眠、网络唤醒和自定义 HTTP 按钮。

## 验证设备

OPPO Watch 4 Pro（OWW221），ColorOS Watch V7.1 / Android 11，32 位 ARM。手表端使用 Android 原生界面，不依赖 GMS，不需要 root 或修改 DPI。Tailscale VPN 已在该设备登录和连接；用户反馈不同网络下遥控接口可返回，具体网络质量和后台保活仍需持续测试。

## 工作方式

手表 → 受口令保护的电脑 HTTP 服务 → 本机 Codex CLI → 状态及结果回传。

模型在电脑侧通过现有 Codex 登录调用，需要互联网；本项目不提供离线模型。助手模式通过 Codex app-server 保留上下文，“继续”“修改一下”接在同一会话中；可手动开始新对话。不接管桌面版已有聊天，一台服务同时执行一个任务。旧版独立任务接口仍保留。

## 安装

1. 下载本仓库 Releases 的 APK，导入手表安装。
2. Windows 安装 Python 3.10+ 和 Codex，并在电脑执行 `codex login` 登录。
3. 复制 `server/.env.example` 为 `server/.env`，生成并填写至少 16 字符的随机 TOKEN。可用 `py -3 -c "import secrets; print(secrets.token_hex(24))"`。
4. 默认只监听本机。要让手表访问，将 BIND 改为电脑局域网或 Tailscale IP（也可在可信环境使用 0.0.0.0），并按需配置仅允许目标网络访问 TCP 8877 的防火墙规则。
5. 运行 `server/Start-PC.cmd`。在手表连接设置填写 `http://电脑地址:8877` 和相同 TOKEN。网卡 MAC 仅网络唤醒需要。
6. 先查询电脑状态，再进入 Codex 任务页发送任务。电脑确认接收后清空输入；失败保留文字以便重试。助手页自动等待状态更新，离开页面后通过常驻通知服务提醒完成或待确认。可在助手页关闭主动提醒。

## 远程连接

推荐两端加入同一个 Tailscale 网络，使用电脑的 Tailscale IP。不需要将端口映射到公网。部分 OPPO 系统提示 VPN 会禁用蓝牙共享网络，关闭 VPN 并重启后恢复；Wi-Fi/手机热点路径需要自行确认。

手表不能打开登录网页时，可在电脑上完成手表生成的设备授权流程。不要分享登录链接或认证口令。

## 边界

- 电脑必须开机、联网且服务运行。暂无开机自启配置。
- Codex 使用 workspace-write 沙箱，默认任务目录为仓库下 workspace/；不自动批准额外权限。
- 任务超时 20 分钟；手表结果截取约 1800 字符，完整结果和日志在 server/ai-results/。
- 停止任务不能回滚已经完成的修改。
- 本地 HTTP 不加密；仅用于可信局域网或 Tailscale 加密隧道，禁止直接暴露公网。
- 语音采用点按录音，最长 30 秒；电脑本地识别，识别文字经用户检查后发送，不自动执行。
- 首次录音须允许麦克风权限；无后台常听。录音只在内存中处理，不保存。
- 常驻监听使用一条长轮询连接，无唤醒锁；系统省电、网络切换或 Tailscale 被暂停时提醒可能延迟。后台稳定性需持续真机验证。
- 支持一次性的命令/文件确认及问题选项；不支持的权限请求拒绝，不自动放开权限。
- 暂不提供多用户隔离或任务队列。

## 开发

- JDK 17+、Android SDK 35、Gradle Wrapper：`./gradlew assembleDebug`（Windows 使用 gradlew.bat）。
- Android 8.0+；CI 生成未签名 Release APK，维护者签名后放入 Releases。正式签名密钥由维护者私下管理，不提交仓库。
- Python 服务仅依赖标准库。测试：在 server/ 目录设置 WATCHREMOTE_TOKEN 为测试用随机值，执行 `python -m unittest test_bridge -v`。
- 可用 WATCHREMOTE_BIND、WATCHREMOTE_PORT、WATCHREMOTE_WORKSPACE、WATCHREMOTE_TOKEN 环境变量覆盖部署配置。

## 来源与许可

基于 [nnyj/wearos-remote](https://github.com/nnyj/wearos-remote) 改造，保留原 MIT 许可证与版权。新增中文原生界面、运行时连接配置、自定义接口、Codex 服务、Tailscale 使用流程及发送成功清空逻辑。

本项目是第三方个人工具，与 OpenAI、OPPO、Tailscale 无隶属关系。使用 Codex 和 Tailscale 仍须遵守各自条款。

后续计划见 ROADMAP.md，版本变更见 CHANGELOG.md，开发协作要求见 CONTRIBUTING.md。

## 本地语音识别安装

在项目目录运行：

```powershell
py -3 -m venv .venv
.venv\Scripts\python.exe -m pip install -r server/requirements-voice.txt
.venv\Scripts\python.exe server/setup_voice.py
```

Start-PC.cmd 会优先使用此隔离环境。base 多语言模型首次下载约 145 MB，之后在电脑本地识别；Codex 回答仍需联网。模型与 .venv 不上传仓库。

## 助手接口

- POST /assistant/send：prompt、request_id、new（可选布尔值）。
- GET /assistant/state：当前状态、回复和待确认项。
- GET /assistant/events?after=revision：最长等待 20 秒的状态变化通知。
- POST /assistant/answer：待确认 id，加 decision=accept/decline 或 answers 对象。
- POST /assistant/stop：停止当前回合。
- POST /assistant/voice：16 kHz 单声道 PCM WAV，最大约 1.1 MB，返回识别文字。

POST 使用 Bearer Token；GET 使用原有口令机制。以上接口只用于受信任私人网络。
