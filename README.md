# WristCodex · 腕上遥控

## 安装包与版本选择

所有历史安装包已归档到 [Releases 下载区](https://github.com/maofaw/WristCodex/releases)。各版本均为侧载测试版；0.6.0-rc1 是候选版，部分决策入口仍需回电脑处理。

| 版本 | 下载与说明 | 配套源码 |
|---|---|---|
| 0.6.0-rc1 候选版 | [APK、功能边界与校验值](https://github.com/maofaw/WristCodex/releases/tag/v0.6.0-rc1) | [0.6.0-rc1](https://github.com/maofaw/WristCodex/tree/v0.6.0-rc1) |
| 0.5.1（含早期版） | [APK 与早期包](https://github.com/maofaw/WristCodex/releases/tag/v0.5.1) | [0.5.1](https://github.com/maofaw/WristCodex/tree/v0.5.1) |
| 0.5.0 | [APK](https://github.com/maofaw/WristCodex/releases/tag/v0.5.0) | [0.5.0](https://github.com/maofaw/WristCodex/tree/v0.5.0) |
| 0.4.0（含 QA 包） | [APK](https://github.com/maofaw/WristCodex/releases/tag/v0.4.0) | [0.4.0](https://github.com/maofaw/WristCodex/tree/v0.4.0) |
| 0.3.0（含 QA 包） | [APK](https://github.com/maofaw/WristCodex/releases/tag/v0.3.0) | [0.3.0](https://github.com/maofaw/WristCodex/tree/v0.3.0) |
| 0.2.0 | [APK](https://github.com/maofaw/WristCodex/releases/tag/v0.2.0) | [0.2.0](https://github.com/maofaw/WristCodex/tree/v0.2.0) |
| 0.1.0（含最初 OPPO 界面原型） | [APK](https://github.com/maofaw/WristCodex/releases/tag/v0.1.0) | [0.1.0](https://github.com/maofaw/WristCodex/tree/v0.1.0) |

**手表 APK 与电脑服务应使用同一版本。** 本页下方及 `main` 的服务源码仍对应 0.4 兼容模式；使用 0.5/0.6 时请打开表中的配套源码和说明。候选版持续开发在 [codex/decision-management](https://github.com/maofaw/WristCodex/tree/codex/decision-management)，没有把未完成特性合并到主分支。

QA 包使用独立包名，可与主应用共存；早期版和后续 0.5.1 是不同二进制。每页附 `SHA256SUMS.txt`，历史包保留原测试签名且未重新打包。低版本可能无法直接覆盖降级，卸载会删除应用本地配置和草稿。

仓库使用 MIT 许可证，保留原作者署名。个人连接口令、会话状态、模型缓存、录音及签名私钥不随源码或安装包分发；请自行配置电脑服务。

---


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

## 0.4 连续对话

- 终端绿界面：纯黑、细线框、系统字体和原生复用列表，无发光、模糊或装饰动画。
- 首页只有一个“发送”：始终沿用当前对话；执行中发送为补充当前回合。点顶部标题进入列表，列表顶部“新建对话”；空白草稿首次发送才创建原生会话。长按标题打开更多操作。
- 手表对话保留各自的上下文、草稿和结果；切换浏览不会切换正在执行的任务。一个任务执行时可看其他记录，但不能在另一段对话发送或新建执行任务，暂不提供队列。
- 电脑 Codex 记录明确标注“只读”，只浏览历史，不直接续聊。现用 v0.3 手表会话会迁移；更早且没有本地归属记录的对话保守显示为只读，不根据工作目录猜测来源。
- 对话列表每页 20 项；消息先请求最近 10 个原生历史项，过滤工具过程后显示文字（最多跳过三页纯工具项），手表最多保留 30 条展示记录。更早记录按需加载，单条全文按约 1000 字符分页；超长消息只提供前 20000 字符并明确标注。
- 待确认只振动一次，通知定位到需要处理的对话，简要说明和选项置顶，详情按需展开；没有回应就持续等待。完成后安静更新，主动停止不振动；需要介入的失败才提醒。
- 新提醒请求全屏通知并短暂唤醒，立即释放唤醒锁，亮屏时长交给系统。OPPO 的充电界面、锁屏和后台策略可能阻挡卡片；通知仍保留。
- 识别语音后检查文字再发送；电脑确认接收才清空匹配的草稿。未确认送达的原请求编号保留，手动重试使用同一编号，不自动补发。草稿隔离保存在本机，卸载会删除。
- 网络和 JSON 处理在后台，文字流留在电脑端，只更新阶段变化；无 WebView、无 UI 定时刷新或逐字重绘。瞬时断线安静重连，活动任务连续三次失败才提醒一次，不擅自重试任务。

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

- POST /assistant/send：prompt、request_id、thread_id、new（新会话为 true）。
- GET /assistant/conversations?cursor=：分页对话目录及只读标记。
- GET /assistant/conversations/messages?thread_id=&cursor=：分页文字历史及活动任务摘要。
- GET /assistant/conversations/message?thread_id=&item_id=&page=0：已加载历史消息的全文分页；电脑仅缓存最近 64 条全文，缓存失效时重新打开历史。
- GET /assistant/state：当前状态、回复和待确认项。
- GET /assistant/events?after=revision：最长等待 20 秒的状态变化通知。
- POST /assistant/answer：待确认 id、thread_id、turn_id，加 decision=accept/decline 或 answers 对象。
- POST /assistant/stop：thread_id、turn_id，停止指定活动回合。
- POST /assistant/steer：prompt、request_id、thread_id、turn_id；只向指定活动回合补充，不新建任务；成功返回 supplement_accepted=true。每回合最多 32 次补充，重复编号不会重复投递，投递结果不明时要求先检查当前任务。
- POST /assistant/voice：16 kHz 单声道 PCM WAV，最大约 1.1 MB，返回识别文字。

POST 使用 Bearer Token；GET 使用原有口令机制。以上接口只用于受信任私人网络。

state/events 支持 compact=1：省略 reply，增加 summary（最多 180 字原文节选）、needs_attention 和 task；不带此参数保留完整回复，兼容旧客户端。内部补充投递凭据不通过接口返回。v0.4 返回 protocol_version=4；新客户端要求服务也更新。旧接口的可选目标字段保留兼容，v0.4 客户端所有操作都传明确目标。对话归属、每段快照和发送凭据存放于 server/assistant-state-conversations/，不进入版本控制；迁移电脑时应与原状态文件一同保留。
