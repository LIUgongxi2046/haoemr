# D01 开源下载事件·机器人排除与多渠道去重（V123）证据报告

> 日期：2026-08-22
> 切片：`ReleaseDownloadEvent`（`release_download_event`）
> 范围：D01 数据中心·开源指标下载口径首切
> 结论：**VERIFIED**（本机全量门禁通过）

## 1. 结论

在既有 `release_metric_snapshot`（V110）之上，新增开源下载事件入口：多来源渠道（GITHUB/WEBSITE/PACKAGE_REGISTRY/DOCKER_HUB）的下载事件统一记录，机器人判定由服务端确定性规则计算（客户端不能自报「非机器人」），非机器人下载按「渠道 + 指纹哈希」去重（数据库部分唯一索引），有效下载计数只统计去重后的非机器人事件。三处硬门共同闭合「下载机器人排除口径 + 多渠道聚合 + 有效下载去重」。

## 2. 高风险验收表

| 验收项 | 硬门/约束 | 证据 |
|---|---|---|
| 机器人判定服务端计算 | `classifyRobot`（空/缺失 UA 或命中 bot 特征 → 机器人） | `givenBotDownload_whenRecording_thenRobot`、`givenBlankAgent_whenRecording_thenRobot` |
| 有效下载去重 | `release_download_valid_dedup_idx` 部分唯一索引（非机器人 + 渠道 + 指纹） | `givenDuplicateValidDownload_whenRecording_thenRejected` |
| 指纹哈希格式 | `release_download_fingerprint_check`（`^[0-9a-f]{64}$`）+ 服务层 `INVALID_FINGERPRINT_HASH` | `givenInvalidFingerprint_whenRecording_thenRejected` |
| 有效计数排除机器人 | `validCount` 只统计 `not is_robot` | `givenDownloads_whenCountingValid_thenExcludesRobots` |

## 3. 自动化门禁

```
scripts/verify.sh → VERIFY_EXIT=0
- contracts test/check：3/3，check 无漂移（357 schemas / 365 outputs / 330 operations）
- AI eval：100/100
- red-team：15 payloads / 12 surfaces
- test-schema.sh：V1–V123 迁移 + 断言，rollback 通过
- backup-restore-verify.sh：通过
- gradle test：111 suites / 430 tests / 0 failures
- web test + build：通过
- security-scan.sh：通过
- verify-traceability.mjs：138/138 FR / 138/138 AC / 138/138 route refs
- generate-route-map.mjs --audit：194/194 routes
```

## 4. 本批实现

- **迁移 V123**：`release_download_event`（租户/渠道/来源 IP/UA/指纹哈希/机器人标记/下载时间）；渠道枚举、指纹 64 位小写哈希约束、非机器人去重部分唯一索引、渠道索引。
- **契约**：新增 `ReleaseDownloadEvent`、`ReleaseDownloadEventCreateRequest`、`ReleaseDownloadValidCount` 三 Schema 与 3 端点（list/record/valid-count）。
- **模块**：`org.haoemr.research` 下 `ReleaseDownloadEventService`（记录 + 服务端机器人判定 + 有效计数 + 幂等）、`Controller`、`Exception`、`ExceptionHandler`。
- **测试**：`ReleaseDownloadEventApiTest` 6 用例覆盖真人/机器人/空 UA 判定、重复去重拒绝、非法指纹拒绝、有效计数排除机器人。

## 5. 未关闭风险

- 真实下载源采集器与机器人特征库仍属外部适配器（本切片实现的是确定性口径与去重逻辑，非真实抓取）。
- 队列成员计算引擎与队列统计口径仍未实现（D01 其余项）。
- 按当前优先级，本切片为 D01「机器人排除 + 多渠道聚合 + 有效下载去重」首切；后续继续 A01 审批流/SSE、A02 限频/转任务、Q01 源系统盘点等全局史诗。
