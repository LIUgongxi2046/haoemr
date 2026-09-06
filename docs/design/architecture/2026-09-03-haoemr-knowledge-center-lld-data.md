# HaoEMR 知识中心 LLD-DATA

> - 文档版本：v0.1
> - 日期：2026-09-03（Asia/Shanghai）
> - 文档状态：`CREATED`（字段级设计，待联合评审；未运行的 DDL 均标 `CREATED`）
> - 需求输入：[知识中心 PRD](../product/prd/2026-09-03-haoemr-knowledge-center-prd.md)、[知识中心 HLD](./2026-09-03-haoemr-knowledge-center-hld.md)
> - 上位数据设计：[haoemr LLD-DATA](./2026-08-14-haoemr-lld-data.md) §4.4/§5/§9

## 0. 事实与状态

| ID | 类型 | 结论 |
|---|---|---|
| FACT-001 | `OBSERVED` | 外部源为本地 Obsidian 库：11 实体分类 × 7 系统 × ~459 表 × ~800 万行，详情页 Markdown（YAML+`[[]]`）+ 全量制表符 TXT。 |
| FACT-002 | `OBSERVED` | 存储为 PostgreSQL 权威 + pgvector（按需），图用邻接表 depth≤2（HLD ADR-2/3）。 |
| FACT-003 | `OBSERVED` | 既有 DDL 约定：`tenant_id + 实体 id` 复合主键、状态 check、不可变触发器、部分唯一索引、幂等 `idempotency_record`、审计 `audit_event` + `outbox_event`。 |
| FACT-004 | `UNKNOWN` | 表级选择矩阵、分块参数、embedding 模型与检索阈值未经实测，本稿为逻辑草案。 |

## 1. 数据源与许可/溯源清单

| 源 | 所有者/类型 | 许可/用途 | 更新 | 敏感级 | 状态 |
|---|---|---|---|---|---|
| 本地 Obsidian 库 `医学知识库/医学结构化数据/图谱详情` | 用户本地 | 开源项目选择性抽取；**排除七巧板/HiTA** | 按源库变更触发重导入 | INTERNAL | 只读 |
| 本地 Obsidian 库 `医学知识库/知识详情`（01/02/03/07/08/09/10/11/13 等） | 用户本地 | 同上，逐领域白名单 | 同上 | INTERNAL | 只读 |
| 主数据字典（术语/编码） | 既有系统管理 | 只读引用，不复制本体 | 既有 | 既有 | 引用 |

**排除项（不导入）**：七巧板医学术语集（`知识详情/04_`）、HiTA 资源（`知识详情/05_` 中 206_ 等）、考试科普（`知识详情/14_`）、各类 PDF 原始文件（临床路径/医保政策 PDF，用结构化 TXT 代替）。

## 2. 外部源选择矩阵（分类级）

> 表级白名单在 S008 以 `knowledge_import` 的 selection 配置落地；未列入白名单的源文件不导入（KC-BR-020）。

| 分类 | 行数（约） | 决策 | 映射 |
|---|---|---|---|
| 疾病 | 2,945,434 | 导入 | 图谱实体 + 疾病/诊断编码 |
| 药品 | 2,760,796 | 导入 | 图谱实体 + 药品目录/编码 |
| 编号表 | 1,232,242 | 导入 | 编码映射（疾病/药品/症状编码） |
| 其他 | 1,252,672 | **逐表评估** | 保留临床有用子集 |
| 检验检查 | 327,459 | 导入 | 图谱实体 + 检验项目 |
| 系统字典 | 193,582 | 导入 | 字典 |
| 症状 | 154,098 | 导入 | 图谱实体 |
| 医保目录 | 64,175 | 导入 | 医保 |
| 问诊题库 | 25,079 | 导入（可选） | 问诊知识 |
| 临床路径 | 12,192 | 导入 | 临床路径 |
| 科室机构 | 4,507 | 导入 | 机构 |

知识详情（14 领域）：导入 01 医保用药规则、02 医保诊疗项目规则、03 质控规则/Schema、07 药品目录、08 诊疗项目/价格、09 疾病/诊断编码、10 手术编码、11 检验项目、13 医用耗材、12 医学术语（剔除七巧板相关内容）；排除 04、14、05 中 HiTA 与 PDF 原始文件。

## 3. 物理模型（逻辑 DDL，`CREATED`）

### 3.1 来源与导入

```sql
create table knowledge_source_registry (
  tenant_id uuid not null,
  source_id uuid not null,
  source_code varchar(128) not null,
  source_name varchar(256) not null check (length(trim(source_name)) >= 2),
  source_kind varchar(32) not null check (source_kind in ('OBSIDIAN_VAULT','MANUAL','UPLOAD')),
  source_path varchar(1024),
  license varchar(256),
  allowed_use text,
  sensitivity varchar(16) not null check (sensitivity in ('PUBLIC','INTERNAL','SENSITIVE','RESTRICTED')),
  update_frequency varchar(32),
  checksum varchar(64),
  status varchar(16) not null check (status in ('REGISTERED','ACTIVE','RETIRED')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  primary key (tenant_id, source_id),
  unique (tenant_id, source_code)
);

create table knowledge_import_batch (
  tenant_id uuid not null,
  batch_id uuid not null,
  source_id uuid not null,
  source_root varchar(1024) not null,
  selection_matrix_version varchar(64) not null,
  source_manifest_hash varchar(64) not null,
  mode varchar(16) not null check (mode in ('READ_ONLY')),
  imported_row_count bigint not null default 0,
  skipped_row_count bigint not null default 0,
  status varchar(16) not null check (status in ('RUNNING','COMPLETED','FAILED')),
  imported_at timestamptz not null default now(),
  operator uuid not null,
  primary key (tenant_id, batch_id),
  foreign key (tenant_id, source_id) references knowledge_source_registry(tenant_id, source_id)
);

create table knowledge_source_file (
  tenant_id uuid not null,
  file_id uuid not null,
  batch_id uuid not null,
  source_path varchar(1024) not null,
  source_content_hash varchar(64) not null,
  entity_category varchar(64),
  system varchar(64),
  table_name varchar(128),
  included boolean not null,
  primary key (tenant_id, file_id),
  unique (tenant_id, batch_id, source_path),
  foreign key (tenant_id, batch_id) references knowledge_import_batch(tenant_id, batch_id)
);
```

### 3.2 文档与版本

```sql
create table knowledge_document (
  tenant_id uuid not null,
  document_id uuid not null,
  document_code varchar(128) not null,
  content_type varchar(32) not null check (content_type in
    ('GUIDELINE','DRUG_LEAFLET','PATHWAY','QC_BASIS','GRAPH_ENTITY','CATALOG','TERMINOLOGY')),
  title varchar(512) not null check (length(trim(title)) >= 2),
  source_authority varchar(256),
  license varchar(256),
  classification varchar(16) not null check (classification in ('PUBLIC','INTERNAL','SENSITIVE','RESTRICTED')),
  effective_from timestamptz,
  effective_to timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  primary key (tenant_id, document_id),
  unique (tenant_id, document_code)
);

create table knowledge_document_version (
  tenant_id uuid not null,
  doc_version_id uuid not null,
  document_id uuid not null,
  version varchar(64) not null,
  content_hash varchar(64) not null,
  markdown text not null,
  metadata jsonb not null default '{}'::jsonb,
  status varchar(16) not null check (status in ('DRAFT','IN_REVIEW','APPROVED','ACTIVE','RETIRED')),
  effective_from timestamptz,
  effective_to timestamptz,
  published_by uuid,
  created_at timestamptz not null default now(),
  primary key (tenant_id, doc_version_id),
  unique (tenant_id, document_id, version),
  check (effective_to is null or effective_to >= effective_from),
  foreign key (tenant_id, document_id) references knowledge_document(tenant_id, document_id)
);

create unique index knowledge_doc_version_one_active_idx
  on knowledge_document_version (tenant_id, document_id) where status = 'ACTIVE';
```

### 3.3 分块、概念与关系

```sql
create extension if not exists vector;

create table knowledge_chunk (
  tenant_id uuid not null,
  chunk_id uuid not null,
  doc_version_id uuid not null,
  section_path varchar(256),
  section_title varchar(512),
  text text not null,
  token_count int not null,
  language varchar(16) not null default 'zh-CN',
  clinical_tags jsonb not null default '[]'::jsonb,
  embedding vector(768),
  embedding_model_id uuid,
  embedding_version varchar(32),
  content_hash varchar(64) not null,
  source_locator jsonb not null default '{}'::jsonb,
  primary key (tenant_id, chunk_id),
  foreign key (tenant_id, doc_version_id) references knowledge_document_version(tenant_id, doc_version_id)
);

create index knowledge_chunk_doc_version_idx on knowledge_chunk (tenant_id, doc_version_id);
create index knowledge_chunk_fts_idx on knowledge_chunk using gin (to_tsvector('simple', text));

create table knowledge_concept (
  tenant_id uuid not null,
  concept_id uuid not null,
  source_type varchar(16) not null check (source_type in ('DICTIONARY','EXTRACTED')),
  source_id varchar(128),
  system varchar(64),
  code varchar(128),
  display varchar(512) not null,
  primary key (tenant_id, concept_id),
  unique (tenant_id, source_type, system, code)
);

create table knowledge_relation (
  tenant_id uuid not null,
  relation_id uuid not null,
  from_concept uuid not null,
  to_concept uuid not null,
  rel_type varchar(32) not null check (rel_type in ('MAPS_TO','MENTIONS','SUPPORTED_BY','CONSTRAINS')),
  version varchar(64),
  primary key (tenant_id, relation_id),
  foreign key (tenant_id, from_concept) references knowledge_concept(tenant_id, concept_id),
  foreign key (tenant_id, to_concept) references knowledge_concept(tenant_id, concept_id)
);

create index knowledge_relation_from_idx on knowledge_relation (tenant_id, from_concept);
```

### 3.4 命中与反馈

```sql
create table knowledge_retrieval_log (
  tenant_id uuid not null,
  log_id uuid not null,
  use_case varchar(64),
  query_hash varchar(64) not null,
  version_ref uuid,
  result_hash varchar(64),
  actor_user_id uuid,
  authorization_watermark varchar(256),
  retrieved_at timestamptz not null default now(),
  primary key (tenant_id, log_id)
);

create table knowledge_feedback (
  tenant_id uuid not null,
  feedback_id uuid not null,
  use_case varchar(64),
  doc_version_id uuid,
  source_ref varchar(256),
  disposition varchar(16) not null check (disposition in ('ACCEPTED','REJECTED','CORRECTION')),
  comment text,
  actor_user_id uuid,
  created_at timestamptz not null default now(),
  primary key (tenant_id, feedback_id)
);
```

## 4. 导入管道状态机

| 阶段 | 输入 | 输出 | 校验/幂等 | 失败处理 |
|---|---|---|---|---|
| `SCAN` | 源根目录（只读） | 源文件清单 + content hash | 只读打开，无写回 | 权限不足→FAILED |
| `SELECT` | 源文件清单 | 白名单内子集 | 选择矩阵版本 + included 标记 | 未命中白名单→SKIP（记 skipped） |
| `PARSE` | 选定源文件 | 结构化文档/块/关系 | YAML/TXT 解析、编码 | 格式错误→隔离该文件 |
| `LOAD` | 结构化实体 | knowledge_* 表 | source_content_hash 幂等 | 冲突→重导入生成新版本 |
| `EMBED` | 已发布 chunk | pgvector 向量 | embedding_model/version 绑定 | 可重建派生，失败不阻断事实层 |
| `PUBLISH` | 已评审版本 | ACTIVE 版本 | 一文档一 ACTIVE | 未评审不发布 |

幂等：`knowledge_source_file(batch_id, source_path)` 唯一；重导入 = 新 batch + 新 version，不覆盖旧事实。

## 5. RAG/检索契约

- chunk 稳定 ID = `doc_version_id + section_path + normalized_content_hash`。
- 检索路由：数值/编码/药品名优先 SQL/精确；叙述语义走 Dense；禁忌/配伍/映射走 Graph depth≤2。
- `ContextReference` 必含 `source_type/source_id/version/locator/content_hash/authorization_watermark/retrieved_at/score`（延续 LLD-DATA §5.2）。
- 召回后二次授权 + 版本/过期校验；`RESTRICTED` 数据层硬门。
- 删除传播顺序：`事实引用 → FTS → 向量 → 图 → 缓存 → 导出副本`，每消费者写完成水位，幂等可重放。

## 6. 数据质量矩阵

| Rule | 门禁 | 阈值 |
|---|---|---|
| DQ-KC-001 源文件只读 | 源目录零写回、逐文件 hash 可溯源 | 100% |
| DQ-KC-002 选择矩阵 | 排除项（七巧板/HiTA）零落库 | 0 |
| DQ-KC-003 版本不可变 | 已发布版本 content_hash 不可变 | 触发器阻断 |
| DQ-KC-004 引用可寻址 | 检索命中可回表定位 | 100% |
| DQ-KC-005 越权引用 | 跨租户/用途/敏感级命中 | 0 |

## 7. 迁移、生命周期与回滚

- 新增模块，无存量数据迁移；首批由外部源只读选择性导入生成。
- Flyway 迁移号从 V351 起；`pgvector` 扩展 `create extension if not exists vector`（S008 需确认部署具备该扩展，否则降级为仅 FTS+精确）。
- 回退：版本 `RETIRED` 只改命中路由；删除传播完成前不物理删除。

## 8. 交接

- S005-2：`knowledge_*` 命令/查询事务契约、幂等键、错误码。
- S005-3：检索 Tool 输入输出、`ContextReference` 契约、删除传播水位。
- S006：跨层字段与生命周期一致性。
- 未决项：表级选择矩阵、embedding 模型/维度（本稿按 768 占位）、检索 Top-K/阈值（`待基线化`）。
