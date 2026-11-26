# 外部 Agent 框架 Trace 接入指南（最小契约）

> 适用范围：任意独立运行的 Java / Python / Node Agent 想把「节点、模型、工具、上下文」这条时间线
> 推到工作台里，用同一套玻璃盒视角查看。**不含任何真实密钥**；示例里的地址是本地默认地址。

## 1. 边界先说清楚

- 外部 Trace **只承载时间线与 Span**：没有计划、没有工具授权、没有检查点。
- 因此它在工作台里**只能只读透视**：不能执行、不能恢复、不能分叉、不能沉淀评测用例。
- 归属由服务端按**当前登录账号**推导，客户端不能指定；跨账号读写一律返回 `404`（不区分「不存在」与「无权限」）。
- 所有文本（名称、属性值）在落库前都会经过 `SensitiveDataMasker` 脱敏并截断，凭据形态的字符串不会原样保存。

## 2. 第一步：创建一条外部接入运行

```bash
curl -X POST http://127.0.0.1:8080/api/external-traces/runs \
  -H 'Content-Type: application/json' \
  -d '{"name":"my-agent-run","source":"python"}'
```

返回：

```json
{ "id": "1f0c...-...", "name": "my-agent-run", "source": "python", "createdAt": "2026-11-26T...Z", "spanCount": 0 }
```

## 3. 第二步：追加 Span

```bash
curl -X POST http://127.0.0.1:8080/api/external-traces/runs/<RUN_ID>/spans \
  -H 'Content-Type: application/json' \
  -d '[
    {"kind":"NODE","name":"plan","nodeKey":"plan","status":"COMPLETED",
     "startedAt":"2026-11-26T09:00:00Z","endedAt":"2026-11-26T09:00:01Z",
     "attributes":{"step":1}},
    {"kind":"MODEL","name":"deepseek-chat","nodeKey":"plan","status":"COMPLETED",
     "startedAt":"2026-11-26T09:00:01Z","endedAt":"2026-11-26T09:00:03Z",
     "attributes":{"inputTokens":120,"outputTokens":80}},
    {"kind":"TOOL","name":"workspace_write","nodeKey":"plan","status":"COMPLETED",
     "startedAt":"2026-11-26T09:00:03Z","endedAt":"2026-11-26T09:00:04Z",
     "attributes":{"path":"src/sort.js"}}
  ]'
```

字段：

| 字段 | 必填 | 说明 |
| :--- | :--- | :--- |
| `kind` | ✅ | `NODE` / `MODEL` / `TOOL` / `CONTEXT` / `HANDOFF` / `CHECKPOINT` / `OTHER` |
| `name` | ✅ | 展示名，≤200 字 |
| `nodeKey` |  | 关联的节点键，用于把同一节点的 Span 归到一起 |
| `status` |  | 例如 `COMPLETED` / `FAILED` / `DENIED`（只做展示，不改变任何判定） |
| `startedAt` / `endedAt` |  | 必须是 ISO-8601；非法时间戳会被拒绝，避免存下无法排序的字符串 |
| `attributes` |  | ≤32 个键；标量原样保留，文本脱敏后保存 |

硬上限：单次 `≤200` 条 Span，单个运行累计 `≤2000` 条，单值 `≤2000` 字符（超出截断）。

## 4. Python 最小示例

```python
import json, urllib.request

BASE = "http://127.0.0.1:8080"

def post(path, payload):
    request = urllib.request.Request(
        BASE + path,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(request) as response:
        return json.loads(response.read().decode("utf-8"))

run = post("/api/external-traces/runs", {"name": "my-python-agent", "source": "python"})
post(
    f"/api/external-traces/runs/{run['id']}/spans",
    [
        {
            "kind": "NODE",
            "name": "plan",
            "nodeKey": "plan",
            "status": "COMPLETED",
            "startedAt": "2026-11-26T09:00:00Z",
            "endedAt": "2026-11-26T09:00:01Z",
            "attributes": {"step": 1},
        }
    ],
)
print(run["id"])
```

> 需要登录态时，先按工作台的登录方式取得会话 Cookie，再复用同一个连接；示例不写入任何密钥。

## 5. 第三步：只读查看

- 运行历史页 →「外部框架接入的 Trace」区域列出当前账号的全部外部运行；
- 点任意一条 → 只读透视弹窗：时间线甘特 + Span 明细 + 边界说明；
- 也可以直接读接口：`GET /api/external-traces/runs/{id}`。

## 6. 从工作台导出到标准 Trace

工作台自己的运行可以反向导出，用于接 Jaeger / Phoenix / Langfuse：

```bash
# 标准 OpenTelemetry Span JSON
curl -s "http://127.0.0.1:8080/api/runs/<RUN_ID>/trace-export?format=otel"

# 工作台自己的 Glass-Box JSON
curl -s "http://127.0.0.1:8080/api/runs/<RUN_ID>/trace-export?format=glassbox"
```

OTel 导出里 `traceId` 由 `runId` 稳定派生、`spanId` 由 Span 标识稳定派生，同一运行重复导出结果一致；
缺少开始时间的记录不会伪造时间混进 OTel，而是写进 `_limitations`。
