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
6. 打开应用即进入助手首页：连接状态、当前阶段、大麦克风和任务文字。识别文字后检查再发送；电脑确认接收才清空草稿。电脑控制和连接配置在“更多操作”中。

## 0.3 助手体验

- “发送新任务”使用独立上下文；“接着上次聊”沿用当前会话。执行中主按钮变为“补充当前任务”，只投递给对应的活动回合；新任务等待当前结束，暂不提供队列。
- 首页只更新执行阶段，结束后显示最多 180 字的结果预览；全文按每页约 1000 字按需加载。预览是原文节选，不另调用模型总结，也不代表已经核验电脑操作成功。
- 待确认时只振动一次，显示简要说明和选项，操作详情可以展开；没有回应就持续等待。完成后安静更新，主动停止不振动；执行失败等需要介入时才提醒。
- 新待处理事件请求全屏通知并短暂唤醒，立即释放唤醒锁，亮屏时长交给系统。OPPO 的充电界面、锁屏和后台策略可能阻挡直接显示卡片；不会绕过系统锁屏，通知仍保留。此行为必须按实测结果判断。
- 未连接时不开始录音，文字草稿保留，不自动补发。瞬时断线安静重连；活动任务连续三次连接失败才提醒一次，任务状态标为未知，不擅自重试任务。
- 网络和 JSON 处理在后台；文字流留在电脑端，手表只接收紧凑状态变化。无 WebView、无装饰动画、无轮询 UI 定时器。

## 远程连接

推荐两端加入同一个 Tailscale 网络，使用电脑的 Tailscale IP。不需要将端口映射到公网。部分 OPPO 系统提示 VPN 会禁用蓝牙共享网络，关闭 VPN 并重启后恢复；Wi-Fi/手机热点路径需要自行确认。

手表不能打开登录网页时，可在电脑上完成手表生成的设备授权流程。不要分享登录链接或认证口令。

## 边界

- 电脑必须开机、联网且服务运行。暂无开机自启配置。
- Codex 使用 workspace-write 沙箱，默认任务目录为仓库下 workspace/；不自动批准额外权限。
- 任务超时 20 分钟；助手当前回复最多保留 20000 字符，预览和分页读取此范围。旧版独立任务的完整结果和日志在 server/ai-results/；新版会话由 Codex 保存，并在本地状态文件保存最近回复。
- 停止任务不能回滚已经完成的修改。
- 本地 HTTP 不加密；仅用于可信局域网或 Tailscale 加密隧道，禁止直接暴露公网。
- 语音采用点按录音，最长 30 秒；电脑本地识别，识别文字经用户检查后发送，不自动执行。
- 首次录音须允许麦克风权限；无后台常听。录音只在内存中处理，不保存。
- 常驻监听使用一条长轮询连接，不持续持有唤醒锁；仅新待处理事件使用最长 1 秒的屏幕唤醒请求并立即释放。系统省电、网络切换或 Tailscale 被暂停时提醒可能延迟，后台稳定性和实际耗电需持续真机验证。
- 支持一次性的命令/文件确认及问题选项；不支持的权限请求拒绝，不自动放开权限。
- 暂不提供多用户隔离或任务队列。

## 开发

- JDK 17+、Android SDK 35、Gradle Wrapper：`./gradlew assembleDebug`（Windows 使用 gradlew.bat）。
- Android 8.0+；CI 生成未签名 Release APK，维护者签名后放入 Releases。正式签名密钥由维护者私下管理，不提交仓库。
- Python 服务仅依赖标准库。测试：在 server/ 目录设置 WATCHREMOTE_TOKEN 为测试用随机值，执行 `python -m unittest discover -v`。
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
- POST /assistant/steer：prompt、request_id、thread_id、turn_id；只向指定活动回合补充，不新建任务；成功返回 supplement_accepted=true。每回合最多 32 次补充，重复编号不会重复投递，投递结果不明时要求先检查当前任务。
- POST /assistant/voice：16 kHz 单声道 PCM WAV，最大约 1.1 MB，返回识别文字。

POST 使用 Bearer Token；GET 使用原有口令机制。以上接口只用于受信任私人网络。

state/events 支持 compact=1：省略 reply，增加 summary（最多 180 字原文节选）、needs_attention 和 task；不带此参数保留完整回复，兼容旧客户端。内部补充投递凭据不通过接口返回。
