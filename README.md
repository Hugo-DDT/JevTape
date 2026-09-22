# JevTape

[中文](README.zh-CN.md) | **English**

> **Record Jev once. Replay it forever.**

JevTape is a lightweight **Record / Replay / Inspect** tool for developers of Jev applications.

It records real Jev decisions and replays them deterministically in local development, tests, and CI — no API key, no network, no live calls to the model.

```text
Application
    │
    ▼
JevTape ──── Record ────► Jev API
    │
    └──── Replay ────► Local Cassette
```

## Quick start

```bash
# First time: record real Jev decisions
$ jevtape record

JevTape RECORD
Listening:  http://127.0.0.1:8787
Upstream:   https://api.typesafe.ai

REC issue-routing
    saved .jevtape/cassettes/issue-routing.json
```

```bash
# After that: replay offline
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

The miss diagnosis points straight at what changed: above, the Question (Decision Contract) changed while State and Model stayed the same.

Commit `.jevtape/cassettes` to Git, and every Jev-related test runs in CI with no API key and no network.

## Why not just another HTTP VCR

JevTape records more than HTTP responses — it records the full **decision context**:

- Jev input State
- Question definitions and types (Choice / Score / Noul)
- Criteria of every question type (an option map, ordered score levels, true/false rules)
- Model, Probability, Confidence, Usage
- **Decision Contract fingerprint**

So JevTape can answer a question a plain VCR cannot:

> "Is the Jev decision contract in my code right now still the contract this recording was made under?"

When you add an option to a Question or edit its criteria, the old cassette produces a replay miss immediately — instead of silently returning a result that no longer represents the current contract.

## Core commands

| Command | Description | Status |
|---|---|---|
| `jevtape record` | Record real Jev requests and responses into local cassettes | MVP |
| `jevtape replay` | Deterministic replay from cassettes, offline by default | MVP |
| `jevtape inspect` | View cassette contents without opening the JSON | MVP |
| `jevtape verify` | Check the current Decision Contract against the recorded one | v0.3.0 |
| `jevtape diff` | Compare the decision differences between two cassettes | v0.4.0 |
| `jevtape simulate` | Simulate edge cases: low confidence, timeouts, 429/500 | v0.5.0 |

## Configuration

Config file `.jevtape/config.json`:

```json
{
  "listen": "127.0.0.1",
  "port": 8787,
  "cassetteDir": ".jevtape/cassettes",
  "match": "strict",
  "onMiss": "error"
}
```

Config precedence: `CLI args > environment variables > .jevtape/config.json > defaults`.

`onMiss` decides what happens when no cassette holds the answer: `error` (default, returns `JEVTAPE_REPLAY_MISS`), `live` (forwards to the real Jev API but saves nothing), `record` (forwards and records the miss into a new cassette). The latter two touch the network and must be enabled explicitly — `jevtape replay --on-miss live`.

API keys come from environment variables and are **never written to the config file or to cassettes**.

## Core invariants

Whatever the version, JevTape always guarantees:

1. Replay runs fully offline;
2. Replay never hits the live API by default;
3. Cassettes are human-readable and version-controlled (Git-diff friendly);
4. Credentials are never written into cassettes;
5. Default matching is deterministic (strict fingerprint);
6. The core does not depend on any language-specific SDK — it works at the HTTP protocol layer;
7. JevTape will not evolve into an AI platform;
8. Every added feature must serve Jev development, testing, or decision contract management.

## Positioning

JevTape is **a small tool, not a platform**:

```text
JUnit          → Java Test
WireMock       → HTTP Mock
Testcontainers → Infrastructure Test
VCR            → HTTP Record/Replay
JevTape        → Jev Decision Record/Replay
```

It is **not**: a Jev SDK, a playground, a benchmark platform, a calibration platform, an observability platform, or a proxy gateway.

## Development

```bash
mvn verify   # the full test suite — no API key, no network required
```

Tests simulate the upstream with the internal `FakeJevServer`; real Jev credentials are never needed.

## License

Apache License 2.0 ([LICENSE](LICENSE)).
