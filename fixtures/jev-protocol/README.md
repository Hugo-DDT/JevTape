# fixtures/jev-protocol

System One 协议的请求 / 响应样例，供 `JevProtocolAdapter` 的回归测试使用。

**这些不是真实线上录制**，是按官方规范手写的协议样例，不含任何真实凭证、真实 state 或真实应答。
真实录制产物在 `fixtures/cassette-v1/`，那是已发布的 Cassette Format v1，原件不得改写。

## 来源

[TypeSafe API reference](https://docs.typesafe.ai/api)，2026-09-22 核对。

官方规范里明确写到的：

- question `type` 取小写：`choice` / `score` / `noul`；
- Choice 的 `criteria` 是 “a map of option to rubric description”；
- Score 的 `criteria` 是 “an ordered array of level descriptions”；
- Noul 的 `criteria` 是带 `true` / `false` 两个键的 object（见官方 noul 示例）；
- `instructions` “can be a string, an object, or an array”；
- `state` 允许 `string | object | array`；
- 应答里 Noul 用 `noul` 字段，Choice 用 `choice` + `probabilities` + `confidence`，Score 用 `score` + `legend` + `probabilities` + `confidence`；
- `usage` 用 snake_case：`input_tokens` / `output_tokens`；
- 文档化的错误状态码：401 / 422 / 429 / 529。

官方规范里**没有**写到的，本目录按 JevTape 自己的构造补齐，并在下表标出：错误响应的 body 形状、
结构化 `instructions` 的完整示例。测试因此只断言解析行为，不断言 “上游一定会这么回”。

## 文件形状

每个文件是一对 HTTP 消息，不是一份 cassette：

```json
{
  "request": { "…": "原始 System One 请求 body" },
  "response": { "status": 200, "body": { "…": "原始响应 body" } }
}
```

测试取 `request` 喂 `parseRequest`，取 `response.body` 喂 `parseResponse`。headers 不在样例里：
录制 / 回放对 header 的处理由各自的测试直接构造，样例只固定 body 语义。

## 清单

| 文件 | 覆盖 | 来源 |
|---|---|---|
| `choice.json` | 小写 `choice`，criteria map，`choice`/`probabilities`/`confidence` 应答 | 官方示例 |
| `score.json` | 小写 `score`，criteria array，`score`/`legend`/`probabilities` 应答 | 官方示例 |
| `noul.json` | 小写 `noul`，criteria object 的 true/false 规则，`noul` 应答 | 官方示例 |
| `all-types.json` | 三类小写 type 同处一份请求 | 官方示例合并 |
| `structured-instructions.json` | `instructions` 为 object / array，`state` 为 object，嵌套与 `null` 的 criteria | 官方只声明了允许的类型，示例由 JevTape 构造 |
| `error-unauthorized.json` | 401，无 `answers` | 状态码官方，body 由 JevTape 构造 |
| `error-invalid-request.json` | 422，请求缺 `criteria` | 状态码官方，body 由 JevTape 构造 |
| `error-rate-limited.json` | 429 | 状态码官方，body 由 JevTape 构造 |
| `error-overloaded.json` | 529 | 状态码官方，body 由 JevTape 构造 |

## 与 fixtures/cassette-v1 的关系

两份 fixture 记录的是**两个不同的协议理解**，都要留着：

- `cassette-v1/` 用大写 `Choice` / `Score` / `Noul`，Choice 的可选项在 `options[]`、Score 的等级在
  `levels[]`、Noul 的 criteria 是一条字符串，`usage` 是 camelCase。这是 v0.5.0 实现所依据的形状，
  也是已发布 cassette 的真实内容 —— 删掉它等于宣布旧磁带不必再可读（charter §63）。
- 本目录用当前官方形状。S02 之后，实现要同时读得懂两边：官方形状是主路径，大写形状退为历史兼容分支。
