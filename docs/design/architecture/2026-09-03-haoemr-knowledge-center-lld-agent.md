# HaoEMR 知识中心 LLD-AGENT

> - 文档版本：v0.1
> - 日期：2026-09-03（Asia/Shanghai）
> - 文档状态：`CREATED`，待联合评审
> - 需求输入：[知识中心 PRD](../product/prd/2026-09-03-haoemr-knowledge-center-prd.md) KC-FR-012、[知识中心 HLD](./2026-09-03-haoemr-knowledge-center-hld.md) §7.2
> - 上位设计：[Medical Agent Harness LLD](./2026-08-25-haoemr-medical-agent-harness-lld.md) §8、[haoemr LLD-AGENT](./2026-08-14-haoemr-lld-agent.md)

## 0. 适用性判断

知识检索本身是**确定性能力**，不引入「知识检索 Agent」；通过 Harness 的 **Tool 机制**把知识检索作为受控能力暴露给已有 Agent（摘要/文书/质控/结果闭环/协同）。决策依据：检索是普通函数/查询即可完成的确定性流程，无需模型规划（S005-3 原则「普通函数或检索能完成时优先简单方案」）。

## 1. 工具契约（继承 T0 只读分级）

| Tool | 目的 | 输入 | 输出 | 权限/范围 | 幂等/超时 | 错误 |
|---|---|---|---|---|---|---|
| `knowledge_search` | 混合检索知识 | `{query, filters?, purpose}` | `ContextReference[]` | T0 只读；角色用途白名单 ∩ 敏感级 ∩ 授权水位 | 只读可重试；5s | `KNOWLEDGE_SEARCH_DENIED` / `KNOWLEDGE_NO_RESULT` |
| `knowledge_lookup` | 精确查术语/药品/编码 | `{conceptType, code, system?}` | canonical 条目 | 同上；精确不向量猜测 | 幂等；3s | `KNOWLEDGE_LOOKUP_NOT_FOUND` |
| `knowledge_graph` | 图邻接（depth≤2） | `{nodeRef}` | `{node, neighbors[]}` | 同上；禁开放遍历 | 幂等；5s | `KNOWLEDGE_GRAPH_TRAVERSAL_LIMIT` |

Tool 响应统一 canonical JSON：

```json
{ "status": "COMPLETE|INCOMPLETE|NO_EVIDENCE|BLOCKED|FAILED",
  "data": {}, "sourceRefs": [], "warnings": [],
  "error": {"code": "...", "retryable": false}, "resultHash": "sha256" }
```

`ContextReference` 必含 `source_type/source_id/version/locator/content_hash/authorization_watermark/retrieved_at/score`。

## 2. 上下文与信任边界

- 知识检索结果注入 **C4 Evidence 层**（Tool 返回的 canonical JSON + source refs），不进入 System/Developer 指令；外部知识按**不可信内容**处理（防 Prompt 注入，harness LLD §14.2）。
- 知识 release 进入 `RunScope` 快照：`RunScope ∩ knowledge_release(graph)` 锁定所用知识版本；同一次运行不静默混用新旧知识（KC-BR-017）。
- 不建设跨患者「记忆」；知识命中文档不携带患者正文。

## 3. 权限矩阵

| 动作 | 主体 | 资源 | 范围 | 条件 |
|---|---|---|---|---|
| 检索 | Agent/用户 | `knowledge_chunk` | 用途白名单 ∩ 敏感级 | 版本 ACTIVE 且未过期，服务端二次鉴权 |
| 精确查询 | Agent/用户 | `knowledge_concept`/编码 | 同上 | 同上 |
| 图遍历 | Agent/用户 | `knowledge_relation` | 同一租户 | depth≤2，禁开放式遍历 |
| 导入/维护 | 知识库管理员 | 全部 | 本租户 | 非 RESTRICTED 或二次授权 |

## 4. 预算与终止

- 单次 `knowledge_search`：Top-K 召回上限（`待基线化`，默认 ≤20）+ 重排后截断 ≤8；总上下文字节上限受父 Agent 预算约束。
- `knowledge_graph`：硬上限 depth≤2、每节点邻接 ≤50；无进展检测复用 Harness NoProgressGuard（同 query+版本无新结果即记 no-progress）。
- 模型不可用/知识检索不可用：降级为「无知识增强」的确定性路径，不伪装检索成功。

## 5. 模型路由与降级

- 检索不依赖模型；仅「重排」可选模型化（cross-encoder），首版用确定性 RRF + 规则重排，模型化重排留待评测基线。
- 实际检索路径（SQL/BM25/Dense/Graph）、版本、耗时、命中数写入 `ai_tool_invocation` 与 `knowledge_retrieval_log`。

## 6. 评测、红队与门禁

- Eval：越权引用=0、可寻址引用=100% 为硬门；Top-K 命中/召回、人工改选率 `待基线化`。
- 红队：Prompt 注入（知识文档含指令）、跨租户/用途越权、RESTRICTED 泄露、图遍历放大。
- 门禁：契约测试通过 + 红队 0 高危 + 越权引用=0，才可对临床用例开放。

## 7. 交接

- S005-2：Tool 执行契约（鉴权、canonical JSON、错误码）对齐。
- S005-4：检索/引用的人工审阅 UI、流式事件（`source.available`、`proposal.ready`）。
- S009：Evals 契约；S010：攻击面（注入/越权/图遍历）。
