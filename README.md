# JevTape

> **Record Jev once. Replay it forever.**

JevTape 是一个面向 Jev 应用开发者的轻量级 **Record / Replay / Inspect** 工具。

它录制真实 Jev 决策（Decision），在本地开发、测试和 CI 中以确定性方式重放 —— 无需 API Key、无需网络、无需真实调用模型。

```text
Application
    │
    ▼
JevTape ──── Record ────► Jev API
    │
    └──── Replay ────► Local Cassette
```

## 快速上手

```bash
# 第一次：录制真实 Jev 决策
$ jevtape record

JevTape RECORD
Listening:  http://127.0.0.1:8787
Upstream:   https://api.typesafe.ai

REC issue-routing
    saved .jevtape/cassettes/issue-routing.json
```

```bash
# 之后：离线重放
$ jevtape replay

JevTape REPLAY
3 cassettes loaded
Network: OFF

HIT issue-routing
    4 ms — 0 network requests

MISS
    closest   issue-routing
    state     MATCH
    contract  CHANGED
    model     MATCH
```

Miss 的诊断直接指出是哪一部分变了：上面这一次是 Question（Decision Contract）改了，而 State 与 Model 都没动。

将 `.jevtape/cassettes` 提交进 Git，CI 中即可在无 API Key、无网络的环境下运行全部 Jev 相关测试。

## 为什么不是普通的 HTTP VCR

JevTape 记录的不只是 HTTP Response，而是完整的 **决策上下文**：

- Jev 输入 State
- Question 定义与类型（Choice / Score / Noul）
- Choice Criteria、Score Levels、Noul Criteria
- Model、Probability、Confidence、Usage
- **Decision Contract Fingerprint**

因此 JevTape 能回答普通 VCR 回答不了的问题：

> “现在代码里的 Jev 决策契约，是否还是录制这份结果时的那个决策契约？”

当你给 Question 增加一个选项、修改了 criteria 时，旧 Cassette 会立刻产生 Replay Miss，而不是静默地返回一个已经不代表当前契约的结果。

## 核心命令

| 命令 | 说明 | 状态 |
|---|---|---|
| `jevtape record` | 录制真实 Jev 请求与响应为本地 Cassette | MVP |
| `jevtape replay` | 从 Cassette 确定性重放，默认离线 | MVP |
| `jevtape inspect` | 不打开 JSON 即可查看 Cassette 内容 | MVP |
| `jevtape verify` | 校验当前 Decision Contract 与录制契约是否一致 | 规划中 |
| `jevtape diff` | 比较两份 Cassette 的决策差异 | 规划中 |
| `jevtape simulate` | 模拟低置信度、超时、429/500 等边界场景 | 规划中 |

## 配置

配置文件 `.jevtape/config.json`：

```json
{
  "listen": "127.0.0.1",
  "port": 8787,
  "cassetteDir": ".jevtape/cassettes",
  "match": "strict",
  "onMiss": "error"
}
```

配置优先级：`CLI 参数 > 环境变量 > .jevtape/config.json > 默认值`。

`onMiss` 决定磁带里没有答案时怎么办：`error`（默认，返回 `JEVTAPE_REPLAY_MISS`）、`live`（转发真实 Jev，但不保存）、`record`（转发并补录成新 Cassette）。后两者会联网，必须显式启用 —— `jevtape replay --on-miss live`。

API Key 通过环境变量提供，**绝不写入配置文件或 Cassette**。

## 核心不变量

无论版本如何演进，JevTape 始终保证：

1. Replay 可以完全离线运行；
2. Replay 默认绝不访问线上 API；
3. Cassette 可读、可版本控制（Git Diff 友好）；
4. 认证信息绝不写入 Cassette；
5. 默认匹配是确定性的（strict fingerprint）；
6. 核心不依赖具体语言 SDK（工作在 HTTP 协议层）；
7. JevTape 不发展成 AI Platform；
8. 每一个新增功能必须服务于 Jev 开发、测试或决策契约管理。

## 项目定位

JevTape 是一个**小工具，不是平台**：

```text
JUnit          → Java Test
WireMock       → HTTP Mock
Testcontainers → Infrastructure Test
VCR            → HTTP Record/Replay
JevTape        → Jev Decision Record/Replay
```

它**不是**：Jev SDK、Playground、Benchmark 平台、Calibration 平台、Observability 平台、代理网关。

## 开发

```bash
mvn verify   # 全部测试，不需要 API Key，不需要网络
```

项目测试通过内部 `FakeJevServer` 模拟上游，永远不需要真实 Jev 凭证。

## 许可证

[Apache License 2.0](LICENSE)
