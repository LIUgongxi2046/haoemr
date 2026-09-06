# HaoEMR V0.1 纵向切片自动化测试与 AI 质量评估报告

> 执行日期：2026-08-14  
> 范围：患者→门诊就诊→病历草稿→确定性质控→AI 候选→人工决策→签署证据；住院入院→床位占用→病区清单→文书时限任务；诊断→医嘱→用药安全硬规则→执行→结果版本/更正→危急值接收与处置→统一临床任务；审计/Outbox→备份恢复  
> 数据边界：仅完全合成数据；未接入真实医院、真实患者、真实 OIDC、CA 或外部大模型。

## 1. 发布结论

- **开发纵向切片：GO**。门诊病历主链、住院入院/床位/病区清单/文书时限，以及诊断—医嘱—用药安全—执行—结果更正—危急值处置首切已可作为后续住院文书、完整处方审查、护理和外部集成的工程基线。
- **真实医院生产部署：NO-GO**。真实 OIDC/统一身份、CA/时间戳、外部连接器 Inbox/Outbox 对账、外部模型安全评测、限流压测、灾备演练和等保测评尚未完成。
- 本结论不等同于“完整 EMR V1.0 已完成”。PRD 与 164 路由原型覆盖的是目标产品，生产代码当前实现门诊病历纵切与住院入院首切，尚未实现完整住院闭环。

## 2. 本次实际执行结果

| 防线 | 工具/证据 | 实际结果 | 判定 |
|---|---|---:|---|
| 线协议 | OpenAPI 3.1 + Node generator | 74 Schema、75 生成物，漂移检查通过 | PASS |
| 契约测试 | Node test | 2/2 | PASS |
| 数据库迁移 | PostgreSQL 18 + Flyway SQL | V1–V21；新增 Outbox、科室支持、住院主链、版本化规则、多级审签、统一临床事件、医嘱执行/控制、诊断/结果版本、危急值、用药安全、统一任务/委托责任链与不可变质控运行证据 | PASS |
| 数据约束 | 事务化 schema assertions | 跨租户、空岗位、非法状态、重复幂等、签后更新/删除均拒绝 | PASS |
| Java 单元/集成 | JUnit 5 + Spring Boot | 34/34 | PASS |
| 前端单元/契约 | Vitest + TypeScript | 28/28（10 文件） | PASS |
| 前端生产构建 | Vite 8 | JS gzip 106.68 KB | PASS |
| 浏览器纵切 | Browser/Playwright | 病历 AI 候选主链；门诊医嘱创建/签署→任务汇聚→查看→接手→限时委托→回到来源；门住院工作域切换；独立病历中心、版本与差异证据；质控治理页“未运行→执行质控→已通过”；1280px 无横向溢出，控制台 0 error / 0 warning | PASS |
| 恢复验真 | pg_dump/隔离 restore/fingerprint | 269 patient / 346 encounter / 107 document / 197 version / 5 quality run；患者/文书版本/质控运行/审计指纹为 `1ab0eec0128316327347bf8e6e8f3981` / `88d64434490a598cb553b5628c91ed13` / `927a371b73bd35d73559c4227662815a` / `aafb71f20d3dab829dbbc0139c6440ac` | PASS |
| PRD 追踪 | traceability verifier | 138/138 FR、138/138 AC | PASS |
| 原型路由 | route audit | 164/164 路由，328 页面—资产链接 | PASS |
| 合成黄金集 | Node dataset gate | 100/100，唯一 ID，明确禁止 AI 直接副作用 | PASS |

根级复现命令：`scripts/verify.sh`。

## 3. 关键用例矩阵

### 3.0 V24 病案归档增量执行（2026-08-19）

| 防线 | 本轮实际结果 | 判定 |
|---|---:|---|
| OpenAPI 生成/漂移 | 83 Schema、84 生成物，`node contracts/generate.mjs --check` | PASS |
| Java 21 编译 | 新增 Archive 主码、生成契约和 `ArchiveApiTest` 使用隔离 `javac --release 21` 编译 | PASS |
| Web 单测 | 30/30（11 文件） | PASS |
| Web 生产构建 | JS gzip 109.95 KB | PASS |
| V22 Schema assertions | 已编写；当前 sandbox 禁止 PostgreSQL 初始化所需共享内存 | **NOT RUN** |
| `ArchiveApiTest` | 已编写且编译；Gradle/Spring/PostgreSQL 进程执行受当前权限/用量限制 | **NOT RUN** |
| 真实浏览器 | Playwright Chrome 进程被 sandbox 终止，内置浏览器 localhost 访问被安全策略拒绝；未生成截图证据 | **NOT RUN** |

V24 当前是“实现已编译、数据库/集成执行待完成”，不继承 V23 的全绿结论，也不得将新增病案能力标记为可发布。

| ID | Given | When | Then | 状态 |
|---|---|---|---|---|
| SEC-01 | 开发 OIDC 替身令牌、有效岗位 | 签发患者/就诊租约 | 15 分钟过期，64 位授权水印，审计与 Outbox 同事务 | PASS |
| SEC-02 | 不存在或跨租户患者 | 签发/使用租约 | 统一 `CONTEXT_NOT_PERMITTED`，副作用 0 | PASS |
| SEC-03 | 过期/撤销租约或岗位 | Worker/命令再次校验 | fail closed | 代码覆盖；待独立时钟注入测试 |
| CLN-01 | 有效患者租约 | 创建门诊就诊 | Encounter + audit + outbox 原子提交 | PASS |
| DOC-01 | 当前草稿 row_version=1 | 保存新正文 | 生成不可变 v2，当前指针前移 | PASS |
| DOC-02 | 客户端仍持 row_version=1 | 再次保存 | `VERSION_CONFLICT + OPEN_DIFF`，不自动合并 | PASS |
| DOC-03 | 重复 Idempotency-Key | 重放创建/保存 | 409，重复副作用 0 | PASS |
| DOC-04 | 就诊中的文书已有 v1/v2 | 按当前就诊查文书，再查指定文书版本链 | 只返回同患者/同就诊数据；当前文书指向 v2，版本按 v2→v1 排序；跨患者文书统一 403 且不泄漏类型/患者信息 | PASS |
| QC-01 | 主诉/现病史缺失 | 运行确定性质控 | BLOCKING finding | PASS |
| QC-02 | 存在 BLOCKING | 签署 | `SIGNING_RULE_BLOCKED`，签名数 0 | PASS |
| QC-03 | 文书完整 | 质控后签署 | 签名证据、SIGNED 状态、audit/outbox 同事务 | PASS |
| QC-04 | 完整文书当前版本尚无质控运行 | 直接签署 | `QUALITY_CHECK_REQUIRED`，签名数 0；质控运行绑定内容哈希且更新/删除被触发器拒绝 | PASS |
| IMM-01 | 已签署版本 | UPDATE/DELETE 正文 | PostgreSQL 触发器拒绝 | PASS |
| AUD-01 | 新增临床/AI 决策事件 | 检查增量链 | 每个 previous_hash 指向前一 event_hash | PASS |
| AI-01 | 有效就诊租约与指定文书版本 | 运行假模型 | 仅 1 次只读 Tool，生成 PENDING_REVIEW 候选 | PASS |
| AI-02 | 候选生成 | 检查引用 | source_id/version、field_path、content_hash、水印完整 | PASS |
| AI-03 | 错患者人工决策 | 接受候选 | 403，Proposal 保持待审 | PASS |
| AI-04 | 正确医生接受候选 | 决策 | Proposal=ACCEPTED，临床正文不变 | PASS |
| AI-05 | 模型 Provider 不可用 | 创建 Run | `DEGRADED`，候选 0，手工主链可用 | PASS |
| SSE-01 | Run 产生 4 个序列事件 | Last-Event-ID=2 重连 | 仅返回 sequence>2 | PASS |
| UI-01 | 首屏真实 API | 浏览器加载 | 患者上下文、租约、病历字段和哈希可见 | PASS |
| UI-02 | AI 候选待审 | 医生点击接受 | 只进入编辑区，明确要求保存与重新质控 | PASS |
| UI-ROUTE-01 | 用户直达 `#/record` | 生产 React 解析一级路由 | 显示独立病历中心及真实文书版本证据，不显示门诊病历工作台 | PASS |
| UI-ROUTE-02 | 用户直达未知深链 | 生产 React 解析路由 | 显式显示“页面不存在或尚未开放”，不静默进入门诊或复用患者上下文 | PASS |
| UI-REC-01 | 合成门诊病历 v1 | 修改治疗与随访计划并保存 v2，进入版本证据 | 页面显示 v2→v1、状态、时间、内容指纹与只读边界；不伪造 CA/时间戳成功证据 | PASS |
| UI-REC-02 | v1/v2 均属于当前文书 | 从版本链进入比较 | 服务端返回且页面仅显示“治疗与随访计划”由空到新内容，两版哈希可见，不自动合并 | PASS |
| UI-REC-03 | 当前版本尚未运行确定性质控 | 进入 `#/record-qc`、回编辑页执行质控再返回 | 先显示“未运行/禁止签署/空列表不等于通过”，后显示 PASSED、运行 ID、内容哈希和 AI 隔离边界；不伪造 CA 有效性 | PASS |
| ARC-01 | 就诊未结束/文书未签署/当前哈希质控未通过/签名非 VALID | 查询归档就绪度或创建病案 | 结构化 blocker；创建返回 `ARCHIVE_NOT_READY`，无清单副作用 | NOT RUN（已编译） |
| ARC-02 | 病案已由归档员建立 | 同一人封存 | `ARCHIVE_SEPARATION_REQUIRED`，状态不变 | NOT RUN（已编译） |
| ARC-03 | 独立病案/管理岗位与有效封存理由 | 封存/解封 | 乐观锁版本前移，事件/审计/Outbox 留证 | NOT RUN（已编译） |
| ARC-04 | 病案已封存 | 固化并下载 JSON 导出 | UTF-8 字节数一致，响应/DB SHA-256 一致，脱离系统可解析，含质控/签名证据 | NOT RUN（已编译） |
| ARC-05 | 归档清单/事件/导出包已存在 | UPDATE/DELETE | PostgreSQL 触发器拒绝 | NOT RUN（已编写） |
| REC-01 | 开发数据库已有完整纵切数据 | 备份至隔离库恢复 | 行数/关系/哈希指纹一致 | PASS |
| OUT-01 | 同一聚合有 v1/v2 待发事件 | Dispatcher 批量领取 | v1 发布后 v2 才可领取；投影与回执持久化 | PASS |
| OUT-02 | 消费者连续失败达到阈值 | 重试并进入死信 | 已成功消费者不重复产生效果；死信状态和错误码可对账 | PASS |
| OUT-03 | 获授权用户填写重放原因 | 重放死信事件 | 重放审计保留原 attempt，成功后投影仍只有一份 | PASS |
| OUT-04 | Worker 租约已过期 | 回收并由新 Worker 领取 | fencing token 从 7 增至 8，旧 Worker 不能完成发布 | PASS |
| SPC-01 | 有效专科包、证据 hash、零安全缺口和未来有效期 | 发布科室支持声明 | `BASIC_CLOSED_LOOP`、row_version、审计与 Outbox 同事务 | PASS |
| SPC-02 | 支持声明版本已变化 | 旧 expected_row_version 覆盖 | 409 `SUPPORT_VERSION_CONFLICT`，原声明不变 | PASS |
| SPC-03 | 缺证据或仍有安全门未关闭 | 声明积极支持 | 409 `SAFETY_GATE_MISSING`，声明数 0 | PASS |
| SPC-04 | 已发布证据过期 | 读取科室支持 | 有效状态降级为 `PACK_PENDING` 并返回 `EVIDENCE_EXPIRED` | PASS |
| SPC-05 | 前端收到四种支持等级 | 判定专科路由 | 仅 `BASIC_CLOSED_LOOP` 开放专科流；其余通用/缺口/阻断 | PASS |
| IP-01 | 有效住院就诊、空闲床位和病区岗位 | 执行入院 | Admission + 床位占用 + 3 个文书时限任务 + audit/outbox 同事务 | PASS |
| IP-02 | 床位已被活动住院占用 | 另一患者入院 | 409 冲突，不新增占用或任务 | PASS |
| IP-03 | 用户病区岗位已失效 | 读取病区工作清单 | 403 默认拒绝，不泄露其他病区患者 | PASS |
| IP-04 | 文书任务截止时间已过 | 加载住院总览 | 状态由服务端计算为 `OVERDUE` | PASS |
| UI-IP-01 | `#/inpatient` 或住院子路由 | 加载 React 应用 | 住院一级导航正确高亮，不误回门诊，API 失败显式呈现 | PASS |
| IP-05 | 待完成住院文书时限任务 | 建立草稿、质控并签署 | 复用通用病历不可变版本/质控/签署内核；任务从 `PENDING`→`IN_PROGRESS`→`COMPLETED`，签署失败全事务回滚 | PASS |
| IP-06 | 源/目标病区均授权且目标床空闲 | 执行转科转床 | 旧占用结束、新占用建立、Admission 版本前移、转科记录/audit/outbox 同事务；目标床已占用时全部回滚 | PASS |
| IP-07 | 在院患者有未完成必需文书 | 执行出院 | 默认 409 阻断；仅主诊医生填写明确原因可豁免，之后任务、床位、Admission、Encounter、出院记录、audit/outbox 同事务收口 | PASS |
| IP-08 | 当前租户有有效住院文书配置 | 查看目录并执行新入院 | 返回 15 类住院志/病程/三级查房/会诊/围术期/抢救/输血/病危/出院/死亡规则；入院任务记录规则版本和发生键，规则发布不改写事件截止时间 | PASS |
| IP-09 | 在院患者按有效规则新增病程或发生转科 | 创建任务/执行转科 | 多实例任务固化规则版本、发生键和截止时间，重复发生键返回 409；转科记录任务自动创建，审计与 Outbox 同事务 | PASS |
| UI-IP-02 | 住院医生在工作台新增病程并开始书写 | 调用真实任务与建稿 API | 非静态跳转；任务按规则模板章节初始化，失败明确呈现且不伪造成功 | PASS |
| IP-10 | 入院记录规则要求主治终审 | 越级签署、作者签名、不同主治终审 | 越级 409；作者签名后任务不完成；有效主治岗位且不同签署人终审后文书/任务原子完成，审签进度可查询 | PASS |
| IP-11 | 作者已签、主治审阅中 | 主治填写原因退回，作者重写新版本并重新审签 | 旧签名转 `REVOKED` 且退回决定不可变留痕；新版本继承策略并从作者层重启，终审后任务完成 | PASS |
| UI-IP-03 | 当前用户具下一审签岗位 | 在住院任务行填写退回原因 | 先读取真实当前文档版本，再提交幂等退回；并发或无权限错误显式呈现 | PASS |
| IP-12 | 在院患者发生会诊/术前/手术/抢救/输血/病危/死亡事件 | 记录带稳定来源键的事件 | 不可变事件与规则化文书任务同事务建立；重复来源返回 409；审计/Outbox 不含明文摘要 | PASS |
| UI-IP-04 | 医生在住院工作台记录临床事件 | 选择事件类型并填写摘要 | 真实 API 生成对应文书任务，提交中防连点，失败明确呈现 | PASS |
| ORD-01 | 门诊或住院进行中就诊有一条草稿医嘱 | 使用当前规则水位签署 | 医嘱转 ACTIVE 并按行项创建执行任务；重复/旧版本签署无重复副作用 | PASS |
| ORD-02 | 执行任务数量为 2 次 | 先记录 1 次 PARTIAL，再记录 1 次 COMPLETED | 累计数量精确校验；任务与医嘱依次转 PARTIAL/IN_PROGRESS 和 COMPLETED；执行事件不可修改删除 | PASS |
| ORD-03 | 用户持有另一患者租约 | 查询原患者医嘱 | 403 且不泄露资源存在性 | PASS |
| UI-ORD-01 | 访问 `#/opd-orders` 或 `#/ip-orders` | 创建、签署和执行医嘱 | 两个工作域共用真实组件与状态机，loading/empty/busy/error 明确呈现 | PASS |
| ORD-04 | ACTIVE 医嘱尚无执行事实 | 填写原因并取消 | 医嘱、行项与待执行任务均转为 CANCELLED；原因写入不可变控制事件 | PASS |
| ORD-05 | 医嘱已有 PARTIAL 在途事实 | 提交停嘱后完成在途执行 | 先转 STOPPING，待执行收口后转 STOPPED；不删除已有执行事实 | PASS |
| UI-ORD-02 | ACTIVE/IN_PROGRESS/STOPPING 不同状态 | 查看医嘱卡片 | 仅显示合法的取消、停止或在途收口操作；原因必填且带行版本 | PASS |
| DX-01 | ICD-10-CN 2026A/2026B 术语有效期不同 | 记录旧日期诊断并在后续版本更正 | 每个诊断版本保留当时编码名称和发布版，新术语不改写历史 | PASS |
| DX-02 | 就诊已有活动主诊断 | 再次创建主诊断 | 数据库唯一约束返回 409；停止原主诊断后可建立新主诊断 | PASS |
| DX-03 | 初步诊断 v1 | 确认、更正、停止 | 依次追加确认/更正版本和不可变控制事件，行版本冲突阻断 | PASS |
| UI-DX-01 | 访问 `#/opd-diagnosis` | 新增、确认、更正或停止诊断 | 真实 API 驱动；术语版本、不可变版本、水印和 AI 候选边界可见 | PASS |
| RES-01 | 已完成且类型匹配的检验/检查执行任务 | 提交带稳定来源键的结果报告 | 结果可回溯原医嘱/执行；未完成或错类型拒绝；重复来源键 409 且无重复副作用 | PASS |
| RES-02 | 结果观察项触发危急值 | 先确认已阅，再提交评估、措施、结局与复查决定 | 状态严格 `OPEN→ACKNOWLEDGED→DISPOSED`；已阅回执不等于处置完成 | PASS |
| RES-03 | 已存在结果报告 v1 | 提交含原因的结果更正 | 追加 v2 并保留 v1 报告及观察项；当前指针前移，历史不可修改删除 | PASS |
| UI-RES-01 | 访问 `#/opd-results` | 创建结果、更正、确认或处置危急值 | 真实 API 驱动；报告版本、观察项、参考范围、医嘱来源和 AI 边界可见 | PASS |
| MED-01 | 患者有与处方成分一致的活动过敏 | 预检或签署药品医嘱 | `ACTIVE_INGREDIENT_ALLERGY` 硬阻断；安全评估/发现不可变留痕，执行任务为 0 | PASS |
| MED-02 | 单次剂量低于下限、超过上限或单位不匹配 | 使用当前药品目录发布版预检 | 返回对应阻断码；目录更新不改写已开医嘱的目录版本与成分快照 | PASS |
| MED-03 | 同一次就诊已有相同活动成分医嘱 | 开立并签署第二张处方 | `ACTIVE_INGREDIENT_DUPLICATE` 硬阻断，不依赖显示名或 AI 判断 | PASS |
| MED-04 | 药品安全预检通过 | 签署医嘱 | 服务端在签署事务前后复检同一规则水位；事实变化则失败关闭 | PASS |
| UI-MED-01 | 访问 `#/opd-orders` 或 `#/ip-orders` | 结构化开立药品并安全预检 | 显示剂量/单位/途径/频次、阻断证据和 AI 边界；阻断时不调用生效主链 | PASS |
| TASK-01 | 医嘱签署生成执行任务 | 查询当前患者/就诊统一任务 | 按稳定来源键生成一条任务；患者、就诊、来源、风险、时限和回跳路由完整 | PASS |
| TASK-02 | 待处理任务版本为 v1 | 查看后再使用正确版本接手，并以旧版本并发接手 | `PENDING→VIEWED→CLAIMED`；查看不等于完成；旧版本 409 且责任不被覆盖 | PASS |
| TASK-03 | 已查看/接手医嘱任务 | 尝试普通完成后在来源执行或取消医嘱 | 无普通完成端点；执行完成由来源推进 `COMPLETED`，取消/停撤待执行任务推进 `WITHDRAWN` | PASS |
| TASK-04 | 危急结果产生接收任务 | 确认接收并完成临床处置 | 接收任务完成后新建独立处置任务；两阶段均由危急值来源状态收口 | PASS |
| TASK-05 | 任务已产生协作及来源事件 | 尝试更新/删除任务事件 | PostgreSQL 触发器拒绝；每个合法版本均进入 Outbox | PASS |
| TASK-06 | 已接手任务和同院区有效岗位人员 | 委托、目标接手、转派、升级和再接手 | 只有当前责任人可变更责任；委托时限和全部人员链不可变；无关人员/无效岗位被拒绝；协作后业务仍为 `PENDING`，只有来源可完成 | PASS |
| UI-TASK-01 | 访问 `#/tasks` | 切换门诊/住院、筛选、查看、接手和回到来源 | 真实 API 驱动；风险/时限/来源明确；页面不提供通用“完成任务”动作 | PASS |
| UI-TASK-02 | 门诊就诊无统一任务 | 在真实浏览器创建并签署合成检验医嘱 | 任务中心即时出现唯一执行任务；查看后业务状态仍为 `PENDING`，接手后只更新责任与版本，来源回跳为 `#/opd-orders` | PASS |
| UI-TASK-03 | 当前人是任务责任人 | 在真实浏览器展开协作面板并委托 8 小时 | 任务转为 `ASSIGNED`、显示目标人员且业务仍为 `PENDING`；原责任人不再看到“标记查看/接手”；控制台 0 error / 0 warning | PASS |

## 4. AI Evals 分层结论

### 4.1 已执行的确定性工程评测

- Provider：`DETERMINISTIC_FAKE`，CI 不访问外部模型。
- 已验证：Schema、租约与患者隔离、只读 Tool、引用定位、禁止直接签署/改写、人工接受/拒绝、SSE 序列恢复、Provider 不可用降级。
- 100 条黄金集位于 `evals/datasets/clinical-ai-golden-v1.json`；当前门禁验证数据完整性与预期安全不变量。

### 4.2 未执行，禁止虚报分数

- 未接真实基座模型，因此 **Faithfulness、Context Precision、临床事实准确率、幻觉率、Token 成本、P95/P99 延迟均无有效分数**。
- 未运行 Promptfoo/Ragas 或医生双盲评审；任何真实模型或 Prompt 版本上线前，必须用同一 100 条集加专科扩展集重新评估。
- 首批真实模型门禁建议：跨患者泄漏 0、未批准临床副作用 0、引用可定位率 100%、Schema 合法率 100%；临床事实正确率阈值须由临床专家委员会批准，不由工程团队自行设定。

## 5. S009 高频遗漏检查结果

| 检查类别 | 结果 |
|---|---|
| 前编检/重复声明/缺导入 | Java、TS 全量编译通过 |
| CSS 防叠 | 1280+ 浏览器快照通过；移动端触控专项待自动化 |
| 数据管线一致性 | 备份恢复指纹、合成导入幂等通过 |
| API 认证/错误/冲突 | 401/403/409 主链覆盖；429 未实现 |
| 路由导航 | 164/164 原型路由；生产 Web 已有门诊纵切、住院入院/工作清单首切、独立 `#/record`、`#/record-qc`、`#/record-versions` 和 `#/record-diff/*`；未知路由安全失败关闭 |
| 生命周期 | 首屏异步加载、API 错误、AI busy 锁通过 |
| 空态/边界 | loading/error/QC empty/AI empty/blocked/degraded 已设计并部分实测 |
| 限频与安全 | 前端 busy 防连点；服务端速率限制未实现，P1 |
| 数据库迁移 | V1–V21 空 schema 正向执行通过 |
| 文件修改安全 | 生成物可重建并执行漂移检查 |
| 可访问性 | 语义区、accessible name、focus-visible、reduced-motion 已覆盖；WCAG 自动扫描待补 |
| 代码质量 | Modulith 边界测试、Java/TS 编译通过 |

## 6. 遗留缺口与修复优先级

| 优先级 | 缺口 | 生产影响 | 下一门禁 |
|---|---|---|---|
| P0 | 真实 OIDC/MFA/统一身份尚未接入，生产 profile 当前 fail closed | 无法生产登录 | OIDC 集成、岗位撤销即时测试、break-glass 审计 |
| P0 | CA/可信时间戳未接入，签名状态为 `PENDING_CA_EVIDENCE` | 不满足正式电子签名证据闭环 | CA 联调、证书过期/吊销/时间戳验真 |
| P0 | 真实模型与专科医生 Evals 未执行 | 不可启用临床 AI | 100 基线+各专科黄金集，双盲评审 |
| P1 | Outbox 核心 Dispatcher/同库消费者去重已完成；LIS/PACS 等外部连接器 Inbox、远端幂等和对账未实现 | 跨系统传播仍未闭环 | 每个连接器的断线、重复、乱序、远端成功本地超时和重放测试 |
| P1 | API 速率限制/资源配额未实现 | DoS 与成本风险 | 租户/用户/AI use-case 限流、429 用例 |
| P1 | 浏览器自动化尚为 CLI 证据，未固化为 CI spec | UI 回归易漏 | Playwright CI 主链、弱网、200% 缩放、键盘测试 |
| P1 | 住院、诊断、医嘱、结果/危急值、用药硬规则及医嘱/危急值统一任务、委托/转派/升级首切已完成；药物相互作用、儿童/肝肾剂量、特殊用药权限、任务过期/通知故障/团队视图和文书/会诊/路径等来源、丰富护理及完整 LIS/PACS 仍未完成 | 尚非完整 EMR | 按 O01/I01/N01 后续原子切片及 X01 依次实现 |

## 7. 证据索引

- 根回归：`scripts/verify.sh`
- Java 结果：`build/test-results/test/`
- 前端构建：`web/dist/`
- 浏览器截图：`artifacts/playwright/clinical-ai-accepted.png`、`artifacts/playwright/v19-clinical-tasks-empty.png`、`artifacts/playwright/v19-clinical-tasks-populated.png`、`artifacts/playwright/v20-clinical-task-collaboration.png`、`artifacts/playwright/v21-record-center-route.png`、`artifacts/playwright/v21-unknown-route-safe.png`、`artifacts/playwright/v22-record-version-evidence.png`、`artifacts/playwright/v22-record-version-diff.png`、`artifacts/playwright/v23-record-governance-not-run.png`、`artifacts/playwright/v23-record-governance-passed.png`
- 合成黄金集：`evals/datasets/clinical-ai-golden-v1.json`
- 数据库恢复脚本：`scripts/backup-restore-verify.sh`
- 需求/路由追踪：`prototype/app/traceability-matrix.csv`、`docs/design/ui-delivery/route-map.csv`
- 合成演示交付包：`release/haoemr-0.1.0-SNAPSHOT-synthetic.tar.gz`
- 部署与生产门禁：`docs/process/operations/2026-08-14-haoemr-s011-devops.md`
