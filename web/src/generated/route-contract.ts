// Generated from docs/design/ui-delivery/route-design-map.csv and prototype/traceability.csv. Do not edit.
export const generatedRouteContract = {
  "schema_version": 1,
  "generated_from": [
    "docs/design/ui-delivery/route-design-map.csv",
    "prototype/traceability.csv"
  ],
  "route_count": 196,
  "routes": [
    {
      "route_id": "admin",
      "path": "#/admin",
      "screen_id": "HASH-116",
      "title": "系统管理工作台",
      "primary_domain": "ADMIN",
      "roles": [
        "ADMIN_OR_AUTHORIZED_GOVERNANCE_ROLE"
      ],
      "fr_refs": [
        "FR-108"
      ],
      "ac_refs": [
        "AC-108"
      ],
      "scr_refs": [
        "SCR-091"
      ],
      "requirement_refs": [
        "FR-108",
        "AC-108",
        "SCR-091"
      ],
      "api_refs": [
        "Admin/Config API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "permission",
        "timeout",
        "conflict",
        "approval",
        "publishing",
        "failed",
        "rollback",
        "success"
      ],
      "guards": [
        "SESSION",
        "ROLE",
        "SCOPE",
        "SEPARATION_OF_DUTIES"
      ],
      "layout": "治理与管理",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/admin.png"
    },
    {
      "route_id": "admin-audit",
      "path": "#/admin-audit",
      "screen_id": "HASH-127",
      "title": "管理审计与权限复核",
      "primary_domain": "ADMIN",
      "roles": [
        "ADMIN_OR_AUTHORIZED_GOVERNANCE_ROLE"
      ],
      "fr_refs": [
        "FR-017",
        "FR-119"
      ],
      "ac_refs": [
        "AC-017",
        "AC-119"
      ],
      "scr_refs": [
        "SCR-010",
        "SCR-102"
      ],
      "requirement_refs": [
        "FR-017",
        "AC-017",
        "SCR-010",
        "FR-119",
        "AC-119",
        "SCR-102"
      ],
      "api_refs": [
        "Admin/Config API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "permission",
        "timeout",
        "conflict",
        "approval",
        "publishing",
        "failed",
        "rollback",
        "success"
      ],
      "guards": [
        "SESSION",
        "ROLE",
        "SCOPE",
        "SEPARATION_OF_DUTIES"
      ],
      "layout": "治理与管理",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/admin-audit.png"
    },
    {
      "route_id": "admin-auth",
      "path": "#/admin-auth",
      "screen_id": "HASH-121",
      "title": "认证与账户安全策略",
      "primary_domain": "ADMIN",
      "roles": [
        "ADMIN_OR_AUTHORIZED_GOVERNANCE_ROLE"
      ],
      "fr_refs": [
        "FR-113"
      ],
      "ac_refs": [
        "AC-113"
      ],
      "scr_refs": [
        "SCR-096"
      ],
      "requirement_refs": [
        "FR-113",
        "AC-113",
        "SCR-096"
      ],
      "api_refs": [
        "Admin/Config API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "permission",
        "timeout",
        "conflict",
        "approval",
        "publishing",
        "failed",
        "rollback",
        "success"
      ],
      "guards": [
        "SESSION",
        "ROLE",
        "SCOPE",
        "SEPARATION_OF_DUTIES"
      ],
      "layout": "治理与管理",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/admin-auth.png"
    },
    {
      "route_id": "admin-dictionaries",
      "path": "#/admin-dictionaries",
      "screen_id": "HASH-122",
      "title": "字典、术语与值集中心",
      "primary_domain": "ADMIN",
      "roles": [
        "ADMIN_OR_AUTHORIZED_GOVERNANCE_ROLE"
      ],
      "fr_refs": [
        "FR-008",
        "FR-114"
      ],
      "ac_refs": [
        "AC-008",
        "AC-114"
      ],
      "scr_refs": [
        "SCR-009",
        "SCR-097"
      ],
      "requirement_refs": [
        "FR-008",
        "AC-008",
        "SCR-009",
        "FR-114",
        "AC-114",
        "SCR-097"
      ],
      "api_refs": [
        "Admin/Config API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "permission",
        "timeout",
        "conflict",
        "approval",
        "publishing",
        "failed",
        "rollback",
        "success"
      ],
      "guards": [
        "SESSION",
        "ROLE",
        "SCOPE",
        "SEPARATION_OF_DUTIES"
      ],
      "layout": "治理与管理",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/admin-dictionaries.png"
    },
    {
      "route_id": "admin-jobs",
      "path": "#/admin-jobs",
      "screen_id": "HASH-126",
      "title": "通知、调度与批量任务",
      "primary_domain": "ADMIN",
      "roles": [
        "ADMIN_OR_AUTHORIZED_GOVERNANCE_ROLE"
      ],
      "fr_refs": [
        "FR-118"
      ],
      "ac_refs": [
        "AC-118"
      ],
      "scr_refs": [
        "SCR-101"
      ],
      "requirement_refs": [
        "FR-118",
        "AC-118",
        "SCR-101"
      ],
      "api_refs": [
        "Admin/Config API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "permission",
        "timeout",
        "conflict",
        "approval",
        "publishing",
        "failed",
        "rollback",
        "success"
      ],
      "guards": [
        "SESSION",
        "ROLE",
        "SCOPE",
        "SEPARATION_OF_DUTIES"
      ],
      "layout": "治理与管理",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/admin-jobs.png"
    },
    {
      "route_id": "admin-master-data",
      "path": "#/admin-master-data",
      "screen_id": "HASH-123",
      "title": "医院主数据管理",
      "primary_domain": "ADMIN",
      "roles": [
        "ADMIN_OR_AUTHORIZED_GOVERNANCE_ROLE"
      ],
      "fr_refs": [
        "FR-024",
        "FR-115"
      ],
      "ac_refs": [
        "AC-024",
        "AC-115"
      ],
      "scr_refs": [
        "SCR-009",
        "SCR-098"
      ],
      "requirement_refs": [
        "FR-024",
        "AC-024",
        "SCR-009",
        "FR-115",
        "AC-115",
        "SCR-098"
      ],
      "api_refs": [
        "Admin/Config API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "permission",
        "timeout",
        "conflict",
        "approval",
        "publishing",
        "failed",
        "rollback",
        "success"
      ],
      "guards": [
        "SESSION",
        "ROLE",
        "SCOPE",
        "SEPARATION_OF_DUTIES"
      ],
      "layout": "治理与管理",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/admin-master-data.png"
    },
    {
      "route_id": "admin-org",
      "path": "#/admin-org",
      "screen_id": "HASH-117",
      "title": "组织机构与工作单元",
      "primary_domain": "ADMIN",
      "roles": [
        "ADMIN_OR_AUTHORIZED_GOVERNANCE_ROLE"
      ],
      "fr_refs": [
        "FR-001",
        "FR-109"
      ],
      "ac_refs": [
        "AC-001",
        "AC-109"
      ],
      "scr_refs": [
        "SCR-007",
        "SCR-092"
      ],
      "requirement_refs": [
        "FR-001",
        "AC-001",
        "SCR-007",
        "FR-109",
        "AC-109",
        "SCR-092"
      ],
      "api_refs": [
        "Admin/Config API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "permission",
        "timeout",
        "conflict",
        "approval",
        "publishing",
        "failed",
        "rollback",
        "success"
      ],
      "guards": [
        "SESSION",
        "ROLE",
        "SCOPE",
        "SEPARATION_OF_DUTIES"
      ],
      "layout": "治理与管理",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/admin-org.png"
    },
    {
      "route_id": "admin-parameters",
      "path": "#/admin-parameters",
      "screen_id": "HASH-125",
      "title": "系统参数与功能开关",
      "primary_domain": "ADMIN",
      "roles": [
        "ADMIN_OR_AUTHORIZED_GOVERNANCE_ROLE"
      ],
      "fr_refs": [
        "FR-117"
      ],
      "ac_refs": [
        "AC-117"
      ],
      "scr_refs": [
        "SCR-100"
      ],
      "requirement_refs": [
        "FR-117",
        "AC-117",
        "SCR-100"
      ],
      "api_refs": [
        "Admin/Config API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "permission",
        "timeout",
        "conflict",
        "approval",
        "publishing",
        "failed",
        "rollback",
        "success"
      ],
      "guards": [
        "SESSION",
        "ROLE",
        "SCOPE",
        "SEPARATION_OF_DUTIES"
      ],
      "layout": "治理与管理",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/admin-parameters.png"
    },
    {
      "route_id": "admin-permissions",
      "path": "#/admin-permissions",
      "screen_id": "HASH-120",
      "title": "权限策略与访问模拟",
      "primary_domain": "ADMIN",
      "roles": [
        "ADMIN_OR_AUTHORIZED_GOVERNANCE_ROLE"
      ],
      "fr_refs": [
        "FR-003",
        "FR-112"
      ],
      "ac_refs": [
        "AC-003",
        "AC-112"
      ],
      "scr_refs": [
        "SCR-008",
        "SCR-095"
      ],
      "requirement_refs": [
        "FR-003",
        "AC-003",
        "SCR-008",
        "FR-112",
        "AC-112",
        "SCR-095"
      ],
      "api_refs": [
        "Admin/Config API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "permission",
        "timeout",
        "conflict",
        "approval",
        "publishing",
        "failed",
        "rollback",
        "success"
      ],
      "guards": [
        "SESSION",
        "ROLE",
        "SCOPE",
        "SEPARATION_OF_DUTIES"
      ],
      "layout": "治理与管理",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/admin-permissions.png"
    },
    {
      "route_id": "admin-roles",
      "path": "#/admin-roles",
      "screen_id": "HASH-119",
      "title": "角色、工作组与职责分离",
      "primary_domain": "ADMIN",
      "roles": [
        "ADMIN_OR_AUTHORIZED_GOVERNANCE_ROLE"
      ],
      "fr_refs": [
        "FR-111"
      ],
      "ac_refs": [
        "AC-111"
      ],
      "scr_refs": [
        "SCR-094"
      ],
      "requirement_refs": [
        "FR-111",
        "AC-111",
        "SCR-094"
      ],
      "api_refs": [
        "Admin/Config API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "permission",
        "timeout",
        "conflict",
        "approval",
        "publishing",
        "failed",
        "rollback",
        "success"
      ],
      "guards": [
        "SESSION",
        "ROLE",
        "SCOPE",
        "SEPARATION_OF_DUTIES"
      ],
      "layout": "治理与管理",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/admin-roles.png"
    },
    {
      "route_id": "admin-templates",
      "path": "#/admin-templates",
      "screen_id": "HASH-124",
      "title": "模板、编号与输出管理",
      "primary_domain": "ADMIN",
      "roles": [
        "ADMIN_OR_AUTHORIZED_GOVERNANCE_ROLE"
      ],
      "fr_refs": [
        "FR-116"
      ],
      "ac_refs": [
        "AC-116"
      ],
      "scr_refs": [
        "SCR-099"
      ],
      "requirement_refs": [
        "FR-116",
        "AC-116",
        "SCR-099"
      ],
      "api_refs": [
        "Admin/Config API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "permission",
        "timeout",
        "conflict",
        "approval",
        "publishing",
        "failed",
        "rollback",
        "success"
      ],
      "guards": [
        "SESSION",
        "ROLE",
        "SCOPE",
        "SEPARATION_OF_DUTIES"
      ],
      "layout": "治理与管理",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/admin-templates.png"
    },
    {
      "route_id": "admin-users",
      "path": "#/admin-users",
      "screen_id": "HASH-118",
      "title": "用户、人员与账户管理",
      "primary_domain": "ADMIN",
      "roles": [
        "ADMIN_OR_AUTHORIZED_GOVERNANCE_ROLE"
      ],
      "fr_refs": [
        "FR-002",
        "FR-110"
      ],
      "ac_refs": [
        "AC-002",
        "AC-110"
      ],
      "scr_refs": [
        "SCR-007",
        "SCR-093"
      ],
      "requirement_refs": [
        "FR-002",
        "AC-002",
        "SCR-007",
        "FR-110",
        "AC-110",
        "SCR-093"
      ],
      "api_refs": [
        "Admin/Config API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "permission",
        "timeout",
        "conflict",
        "approval",
        "publishing",
        "failed",
        "rollback",
        "success"
      ],
      "guards": [
        "SESSION",
        "ROLE",
        "SCOPE",
        "SEPARATION_OF_DUTIES"
      ],
      "layout": "治理与管理",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/admin-users.png"
    },
    {
      "route_id": "admission-bed",
      "path": "#/admission-bed",
      "screen_id": "HASH-009",
      "title": "入院、病区与床位管理",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [
        "FR-021"
      ],
      "ac_refs": [
        "AC-021"
      ],
      "scr_refs": [
        "SCR-018"
      ],
      "requirement_refs": [
        "FR-021",
        "AC-021",
        "SCR-018"
      ],
      "api_refs": [
        "Platform API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "offline",
        "permission",
        "session-expired",
        "patient-switch",
        "conflict",
        "blocked",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "ROLE"
      ],
      "layout": "临床工作域",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/admission-bed.png"
    },
    {
      "route_id": "agent",
      "path": "#/agent",
      "screen_id": "HASH-092",
      "title": "Agent 受控运行",
      "primary_domain": "AI",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [
        "FR-075"
      ],
      "ac_refs": [
        "AC-075"
      ],
      "scr_refs": [
        "SCR-053"
      ],
      "requirement_refs": [
        "FR-075",
        "AC-075",
        "SCR-053"
      ],
      "api_refs": [
        "Platform API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "permission",
        "timeout",
        "conflict",
        "approval",
        "publishing",
        "failed",
        "rollback",
        "success"
      ],
      "guards": [
        "SESSION",
        "ROLE"
      ],
      "layout": "治理与管理",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/agent.png"
    },
    {
      "route_id": "agent-catalog",
      "path": "#/agent-catalog",
      "screen_id": "HASH-091",
      "title": "Agent 目录与定义编辑器",
      "primary_domain": "AI",
      "roles": [
        "AUTHORIZED_CLINICAL_OR_AI_GOVERNANCE_ROLE"
      ],
      "fr_refs": [
        "FR-074"
      ],
      "ac_refs": [
        "AC-074"
      ],
      "scr_refs": [
        "SCR-052"
      ],
      "requirement_refs": [
        "FR-074",
        "AC-074",
        "SCR-052"
      ],
      "api_refs": [
        "ContextLease",
        "AIRun",
        "AIProposal"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "permission",
        "timeout",
        "conflict",
        "approval",
        "publishing",
        "failed",
        "rollback",
        "success"
      ],
      "guards": [
        "SESSION",
        "PURPOSE",
        "MODEL_POLICY",
        "AI_USE_CASE"
      ],
      "layout": "治理与管理",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/agent-catalog.png"
    },
    {
      "route_id": "agent-compose",
      "path": "#/agent-compose",
      "screen_id": "HASH-096",
      "title": "Agent、Skill 与 Tool 组合画布",
      "primary_domain": "AI",
      "roles": [
        "AUTHORIZED_CLINICAL_OR_AI_GOVERNANCE_ROLE"
      ],
      "fr_refs": [
        "FR-079"
      ],
      "ac_refs": [
        "AC-079"
      ],
      "scr_refs": [
        "SCR-057"
      ],
      "requirement_refs": [
        "FR-079",
        "AC-079",
        "SCR-057"
      ],
      "api_refs": [
        "ContextLease",
        "AIRun",
        "AIProposal"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "permission",
        "timeout",
        "conflict",
        "approval",
        "publishing",
        "failed",
        "rollback",
        "success"
      ],
      "guards": [
        "SESSION",
        "PURPOSE",
        "MODEL_POLICY",
        "AI_USE_CASE"
      ],
      "layout": "治理与管理",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/agent-compose.png"
    },
    {
      "route_id": "agent-context",
      "path": "#/agent-context",
      "screen_id": "HASH-093",
      "title": "Agent 上下文与记忆策略",
      "primary_domain": "AI",
      "roles": [
        "AUTHORIZED_CLINICAL_OR_AI_GOVERNANCE_ROLE"
      ],
      "fr_refs": [
        "FR-076"
      ],
      "ac_refs": [
        "AC-076"
      ],
      "scr_refs": [
        "SCR-054"
      ],
      "requirement_refs": [
        "FR-076",
        "AC-076",
        "SCR-054"
      ],
      "api_refs": [
        "ContextLease",
        "AIRun",
        "AIProposal"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "permission",
        "timeout",
        "conflict",
        "approval",
        "publishing",
        "failed",
        "rollback",
        "success"
      ],
      "guards": [
        "SESSION",
        "PURPOSE",
        "MODEL_POLICY",
        "AI_USE_CASE"
      ],
      "layout": "治理与管理",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/agent-context.png"
    },
    {
      "route_id": "agent-evals",
      "path": "#/agent-evals",
      "screen_id": "HASH-097",
      "title": "Agent 与 Skill 评估发布中心",
      "primary_domain": "AI",
      "roles": [
        "AUTHORIZED_CLINICAL_OR_AI_GOVERNANCE_ROLE"
      ],
      "fr_refs": [
        "FR-080"
      ],
      "ac_refs": [
        "AC-080"
      ],
      "scr_refs": [
        "SCR-058"
      ],
      "requirement_refs": [
        "FR-080",
        "AC-080",
        "SCR-058"
      ],
      "api_refs": [
        "ContextLease",
        "AIRun",
        "AIProposal"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "permission",
        "timeout",
        "conflict",
        "approval",
        "publishing",
        "failed",
        "rollback",
        "success"
      ],
      "guards": [
        "SESSION",
        "PURPOSE",
        "MODEL_POLICY",
        "AI_USE_CASE"
      ],
      "layout": "治理与管理",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/agent-evals.png"
    },
    {
      "route_id": "ai-assistant",
      "path": "#/ai-assistant",
      "screen_id": "HASH-082",
      "title": "AI医助 EVA",
      "primary_domain": "AI",
      "roles": [
        "AUTHORIZED_CLINICAL_OR_AI_GOVERNANCE_ROLE"
      ],
      "fr_refs": [
        "FR-031",
        "FR-120"
      ],
      "ac_refs": [
        "AC-031",
        "AC-120"
      ],
      "scr_refs": [
        "GLOBAL-AI",
        "SCR-017"
      ],
      "requirement_refs": [
        "FR-031",
        "AC-031",
        "SCR-017",
        "FR-120",
        "AC-120",
        "GLOBAL-AI"
      ],
      "api_refs": [
        "ContextLease",
        "AIRun",
        "AIProposal"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "permission",
        "timeout",
        "conflict",
        "approval",
        "publishing",
        "failed",
        "rollback",
        "success"
      ],
      "guards": [
        "SESSION",
        "PURPOSE",
        "MODEL_POLICY",
        "AI_USE_CASE"
      ],
      "layout": "治理与管理",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/ai-assistant.png"
    },
    {
      "route_id": "ai-capture",
      "path": "#/ai-capture",
      "screen_id": "HASH-084",
      "title": "环境记录与转写审阅",
      "primary_domain": "AI",
      "roles": [
        "AUTHORIZED_CLINICAL_OR_AI_GOVERNANCE_ROLE"
      ],
      "fr_refs": [
        "FR-122"
      ],
      "ac_refs": [
        "AC-122"
      ],
      "scr_refs": [
        "GLOBAL-AI/#ai-capture"
      ],
      "requirement_refs": [
        "FR-122",
        "AC-122",
        "GLOBAL-AI/#ai-capture"
      ],
      "api_refs": [
        "ContextLease",
        "AIRun",
        "AIProposal"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "permission",
        "timeout",
        "conflict",
        "approval",
        "publishing",
        "failed",
        "rollback",
        "success"
      ],
      "guards": [
        "SESSION",
        "PURPOSE",
        "MODEL_POLICY",
        "AI_USE_CASE"
      ],
      "layout": "治理与管理",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/ai-capture.png"
    },
    {
      "route_id": "ai-center",
      "path": "#/ai-center",
      "screen_id": "HASH-081",
      "title": "ai-center",
      "primary_domain": "AI",
      "roles": [
        "AUTHORIZED_CLINICAL_OR_AI_GOVERNANCE_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "ContextLease",
        "AIRun",
        "AIProposal"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "permission",
        "timeout",
        "conflict",
        "approval",
        "publishing",
        "failed",
        "rollback",
        "success"
      ],
      "guards": [
        "SESSION",
        "PURPOSE",
        "MODEL_POLICY",
        "AI_USE_CASE"
      ],
      "layout": "治理与管理",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/ai-center.png"
    },
    {
      "route_id": "ai-reminder-detail",
      "path": "#/ai-reminder-detail",
      "screen_id": "HASH-083",
      "title": "AI 提醒证据详情",
      "primary_domain": "AI",
      "roles": [
        "AUTHORIZED_CLINICAL_OR_AI_GOVERNANCE_ROLE"
      ],
      "fr_refs": [
        "FR-121"
      ],
      "ac_refs": [
        "AC-121"
      ],
      "scr_refs": [
        "GLOBAL-AI/#ai-reminder-detail"
      ],
      "requirement_refs": [
        "FR-121",
        "AC-121",
        "GLOBAL-AI/#ai-reminder-detail"
      ],
      "api_refs": [
        "ContextLease",
        "AIRun",
        "AIProposal"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "permission",
        "timeout",
        "conflict",
        "approval",
        "publishing",
        "failed",
        "rollback",
        "success"
      ],
      "guards": [
        "SESSION",
        "PURPOSE",
        "MODEL_POLICY",
        "AI_USE_CASE"
      ],
      "layout": "治理与管理",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/ai-reminder-detail.png"
    },
    {
      "route_id": "aiops",
      "path": "#/aiops",
      "screen_id": "HASH-098",
      "title": "AI 运行、预算与事件中心",
      "primary_domain": "AI",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [
        "FR-030",
        "FR-081"
      ],
      "ac_refs": [
        "AC-030",
        "AC-081"
      ],
      "scr_refs": [
        "SCR-059"
      ],
      "requirement_refs": [
        "FR-030",
        "AC-030",
        "SCR-059",
        "FR-081",
        "AC-081"
      ],
      "api_refs": [
        "Platform API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "permission",
        "timeout",
        "conflict",
        "approval",
        "publishing",
        "failed",
        "rollback",
        "success"
      ],
      "guards": [
        "SESSION",
        "ROLE"
      ],
      "layout": "治理与管理",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/aiops.png"
    },
    {
      "route_id": "anesthesia-workbench",
      "path": "#/anesthesia-workbench",
      "screen_id": "HASH-060",
      "title": "麻醉时间轴与复苏",
      "primary_domain": "COLLABORATION",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [
        "FR-047"
      ],
      "ac_refs": [
        "AC-047"
      ],
      "scr_refs": [
        "SCR-029"
      ],
      "requirement_refs": [
        "FR-047",
        "AC-047",
        "SCR-029"
      ],
      "api_refs": [
        "Platform API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "timeout",
        "duplicate",
        "verification",
        "hard-block",
        "success",
        "compensation"
      ],
      "guards": [
        "SESSION",
        "ROLE"
      ],
      "layout": "诊疗执行",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/anesthesia-workbench.png"
    },
    {
      "route_id": "appointment-registration",
      "path": "#/appointment-registration",
      "screen_id": "HASH-008",
      "title": "预约、挂号、分诊、叫号与留观",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [
        "FR-039"
      ],
      "ac_refs": [
        "AC-039"
      ],
      "scr_refs": [
        "SCR-011"
      ],
      "requirement_refs": [
        "FR-039",
        "AC-039",
        "SCR-011"
      ],
      "api_refs": [
        "Platform API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "offline",
        "permission",
        "session-expired",
        "patient-switch",
        "conflict",
        "blocked",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "ROLE"
      ],
      "layout": "临床工作域",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/appointment-registration.png"
    },
    {
      "route_id": "archive-assets",
      "path": "#/archive-assets",
      "screen_id": "HASH-044",
      "title": "病案资产与共享文档中心",
      "primary_domain": "RECORD",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [
        "FR-018",
        "FR-094"
      ],
      "ac_refs": [
        "AC-018",
        "AC-094"
      ],
      "scr_refs": [
        "SCR-033",
        "SCR-072"
      ],
      "requirement_refs": [
        "FR-018",
        "AC-018",
        "SCR-033",
        "FR-094",
        "AC-094",
        "SCR-072"
      ],
      "api_refs": [
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "autosaving",
        "saved",
        "offline",
        "permission",
        "concurrent-conflict",
        "rule-blocked",
        "signed",
        "corrected",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_OR_PURPOSE"
      ],
      "layout": "病历与病案",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/archive-assets.png"
    },
    {
      "route_id": "archive-borrow",
      "path": "#/archive-borrow",
      "screen_id": "HASH-048",
      "title": "病案借阅、复制与对外提供",
      "primary_domain": "RECORD",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "autosaving",
        "saved",
        "offline",
        "permission",
        "concurrent-conflict",
        "rule-blocked",
        "signed",
        "corrected",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_OR_PURPOSE"
      ],
      "layout": "病历与病案",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/archive-borrow.png"
    },
    {
      "route_id": "archive-catalog",
      "path": "#/archive-catalog",
      "screen_id": "HASH-045",
      "title": "病案目录与完整性",
      "primary_domain": "RECORD",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [
        "FR-105"
      ],
      "ac_refs": [
        "AC-105"
      ],
      "scr_refs": [
        "SCR-085-087"
      ],
      "requirement_refs": [
        "FR-105",
        "AC-105",
        "SCR-085-087"
      ],
      "api_refs": [
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "autosaving",
        "saved",
        "offline",
        "permission",
        "concurrent-conflict",
        "rule-blocked",
        "signed",
        "corrected",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_OR_PURPOSE"
      ],
      "layout": "病历与病案",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/archive-catalog.png"
    },
    {
      "route_id": "archive-integrity",
      "path": "#/archive-integrity",
      "screen_id": "HASH-047",
      "title": "病案完整性、签名与长期验真",
      "primary_domain": "RECORD",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "autosaving",
        "saved",
        "offline",
        "permission",
        "concurrent-conflict",
        "rule-blocked",
        "signed",
        "corrected",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_OR_PURPOSE"
      ],
      "layout": "病历与病案",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/archive-integrity.png"
    },
    {
      "route_id": "archive-preservation",
      "path": "#/archive-preservation",
      "screen_id": "HASH-049",
      "title": "病案长期保存与恢复验证",
      "primary_domain": "RECORD",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "autosaving",
        "saved",
        "offline",
        "permission",
        "concurrent-conflict",
        "rule-blocked",
        "signed",
        "corrected",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_OR_PURPOSE"
      ],
      "layout": "病历与病案",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/archive-preservation.png"
    },
    {
      "route_id": "archive-scan",
      "path": "#/archive-scan",
      "screen_id": "HASH-046",
      "title": "纸质病历扫描与智能编目",
      "primary_domain": "RECORD",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "autosaving",
        "saved",
        "offline",
        "permission",
        "concurrent-conflict",
        "rule-blocked",
        "signed",
        "corrected",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_OR_PURPOSE"
      ],
      "layout": "病历与病案",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/archive-scan.png"
    },
    {
      "route_id": "asset-detail",
      "path": "#/asset-detail",
      "screen_id": "HASH-050",
      "title": "病案资产证据详情",
      "primary_domain": "RECORD",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "Platform API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "autosaving",
        "saved",
        "offline",
        "permission",
        "concurrent-conflict",
        "rule-blocked",
        "signed",
        "corrected",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "ROLE"
      ],
      "layout": "病历与病案",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/asset-detail.png"
    },
    {
      "route_id": "backup",
      "path": "#/backup",
      "screen_id": "HASH-112",
      "title": "备份、恢复与完整性报告",
      "primary_domain": "CONFIG",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [
        "FR-026"
      ],
      "ac_refs": [
        "AC-026"
      ],
      "scr_refs": [
        "SCR-061"
      ],
      "requirement_refs": [
        "FR-026",
        "AC-026",
        "SCR-061"
      ],
      "api_refs": [
        "Platform API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "permission",
        "timeout",
        "conflict",
        "approval",
        "publishing",
        "failed",
        "rollback",
        "success"
      ],
      "guards": [
        "SESSION",
        "ROLE"
      ],
      "layout": "治理与管理",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/backup.png"
    },
    {
      "route_id": "billing",
      "path": "#/billing",
      "screen_id": "HASH-052",
      "title": "费用、收退费、预交与结算",
      "primary_domain": "COLLABORATION",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [
        "FR-040"
      ],
      "ac_refs": [
        "AC-040"
      ],
      "scr_refs": [
        "SCR-022"
      ],
      "requirement_refs": [
        "FR-040",
        "AC-040",
        "SCR-022"
      ],
      "api_refs": [
        "Platform API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "timeout",
        "duplicate",
        "verification",
        "hard-block",
        "success",
        "compensation"
      ],
      "guards": [
        "SESSION",
        "ROLE"
      ],
      "layout": "诊疗执行",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/billing.png"
    },
    {
      "route_id": "capability-pack",
      "path": "#/capability-pack",
      "screen_id": "HASH-104",
      "title": "机构能力包与继承解析",
      "primary_domain": "CONFIG",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [
        "FR-062"
      ],
      "ac_refs": [
        "AC-062"
      ],
      "scr_refs": [
        "SCR-040"
      ],
      "requirement_refs": [
        "FR-062",
        "AC-062",
        "SCR-040"
      ],
      "api_refs": [
        "Platform API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "permission",
        "timeout",
        "conflict",
        "approval",
        "publishing",
        "failed",
        "rollback",
        "success"
      ],
      "guards": [
        "SESSION",
        "ROLE"
      ],
      "layout": "治理与管理",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/capability-pack.png"
    },
    {
      "route_id": "care-operations",
      "path": "#/care-operations",
      "screen_id": "HASH-051",
      "title": "诊疗执行中心",
      "primary_domain": "COLLABORATION",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "Platform API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "timeout",
        "duplicate",
        "verification",
        "hard-block",
        "success",
        "compensation"
      ],
      "guards": [
        "SESSION",
        "ROLE"
      ],
      "layout": "诊疗执行",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/care-operations.png"
    },
    {
      "route_id": "clinical",
      "path": "#/clinical",
      "screen_id": "HASH-001",
      "title": "临床业务门户",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [
        "FR-088",
        "FR-127"
      ],
      "ac_refs": [
        "AC-088",
        "AC-127"
      ],
      "scr_refs": [
        "GLOBAL-ROUTE-RULE",
        "SCR-069"
      ],
      "requirement_refs": [
        "FR-088",
        "AC-088",
        "SCR-069",
        "FR-127",
        "AC-127",
        "GLOBAL-ROUTE-RULE"
      ],
      "api_refs": [
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "offline",
        "permission",
        "session-expired",
        "patient-switch",
        "conflict",
        "blocked",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_OR_PURPOSE"
      ],
      "layout": "临床工作域",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/clinical.png"
    },
    {
      "route_id": "clinical-tasks",
      "path": "#/clinical-tasks",
      "screen_id": "HASH-063",
      "title": "统一临床任务与临床路径",
      "primary_domain": "COLLABORATION",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [
        "FR-051",
        "FR-092",
        "FR-095"
      ],
      "ac_refs": [
        "AC-051",
        "AC-092",
        "AC-095"
      ],
      "scr_refs": [
        "SCR-021",
        "SCR-071"
      ],
      "requirement_refs": [
        "FR-051",
        "AC-051",
        "SCR-021",
        "FR-092",
        "AC-092",
        "SCR-071",
        "FR-095",
        "AC-095"
      ],
      "api_refs": [
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "timeout",
        "duplicate",
        "verification",
        "hard-block",
        "success",
        "compensation"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_OR_PURPOSE"
      ],
      "layout": "诊疗执行",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/clinical-tasks.png"
    },
    {
      "route_id": "cohort-builder",
      "path": "#/cohort-builder",
      "screen_id": "HASH-078",
      "title": "科研队列构建器",
      "primary_domain": "DATA",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [
        "FR-106"
      ],
      "ac_refs": [
        "AC-106"
      ],
      "scr_refs": [
        "SCR-088/089"
      ],
      "requirement_refs": [
        "FR-106",
        "AC-106",
        "SCR-088/089"
      ],
      "api_refs": [
        "Platform API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "permission",
        "timeout",
        "conflict",
        "approval",
        "publishing",
        "failed",
        "rollback",
        "success"
      ],
      "guards": [
        "SESSION",
        "ROLE"
      ],
      "layout": "治理与管理",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/cohort-builder.png"
    },
    {
      "route_id": "config-release",
      "path": "#/config-release",
      "screen_id": "HASH-109",
      "title": "配置差异、验证、审批与发布",
      "primary_domain": "CONFIG",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [
        "FR-068"
      ],
      "ac_refs": [
        "AC-068"
      ],
      "scr_refs": [
        "SCR-046"
      ],
      "requirement_refs": [
        "FR-068",
        "AC-068",
        "SCR-046"
      ],
      "api_refs": [
        "Platform API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "permission",
        "timeout",
        "conflict",
        "approval",
        "publishing",
        "failed",
        "rollback",
        "success"
      ],
      "guards": [
        "SESSION",
        "ROLE"
      ],
      "layout": "治理与管理",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/config-release.png"
    },
    {
      "route_id": "config-upgrade",
      "path": "#/config-upgrade",
      "screen_id": "HASH-110",
      "title": "配置包与产品升级冲突中心",
      "primary_domain": "CONFIG",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [
        "FR-069"
      ],
      "ac_refs": [
        "AC-069"
      ],
      "scr_refs": [
        "SCR-047"
      ],
      "requirement_refs": [
        "FR-069",
        "AC-069",
        "SCR-047"
      ],
      "api_refs": [
        "Platform API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "permission",
        "timeout",
        "conflict",
        "approval",
        "publishing",
        "failed",
        "rollback",
        "success"
      ],
      "guards": [
        "SESSION",
        "ROLE"
      ],
      "layout": "治理与管理",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/config-upgrade.png"
    },
    {
      "route_id": "credentials",
      "path": "#/credentials",
      "screen_id": "HASH-068",
      "title": "临床资质与医疗授权中心",
      "primary_domain": "QUALITY",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [
        "FR-096",
        "FR-097"
      ],
      "ac_refs": [
        "AC-096",
        "AC-097"
      ],
      "scr_refs": [
        "SCR-073"
      ],
      "requirement_refs": [
        "FR-096",
        "AC-096",
        "SCR-073",
        "FR-097",
        "AC-097"
      ],
      "api_refs": [
        "Platform API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "permission",
        "timeout",
        "conflict",
        "approval",
        "publishing",
        "failed",
        "rollback",
        "success"
      ],
      "guards": [
        "SESSION",
        "ROLE"
      ],
      "layout": "治理与管理",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/credentials.png"
    },
    {
      "route_id": "data-center",
      "path": "#/data-center",
      "screen_id": "HASH-069",
      "title": "数据中心",
      "primary_domain": "DATA",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "Platform API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "permission",
        "timeout",
        "conflict",
        "approval",
        "publishing",
        "failed",
        "rollback",
        "success"
      ],
      "guards": [
        "SESSION",
        "ROLE"
      ],
      "layout": "治理与管理",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/data-center.png"
    },
    {
      "route_id": "data-quality",
      "path": "#/data-quality",
      "screen_id": "HASH-075",
      "title": "全院数据质量与问题工单",
      "primary_domain": "DATA",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [
        "FR-054"
      ],
      "ac_refs": [
        "AC-054"
      ],
      "scr_refs": [
        "SCR-038"
      ],
      "requirement_refs": [
        "FR-054",
        "AC-054",
        "SCR-038"
      ],
      "api_refs": [
        "Platform API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "permission",
        "timeout",
        "conflict",
        "approval",
        "publishing",
        "failed",
        "rollback",
        "success"
      ],
      "guards": [
        "SESSION",
        "ROLE"
      ],
      "layout": "治理与管理",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/data-quality.png"
    },
    {
      "route_id": "dental-care",
      "path": "#/dental-care",
      "screen_id": "HASH-182",
      "title": "口腔科关键诊疗流程",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_SPECIALTY_CLINICAL_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "SpecialtySupport",
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "device-offline",
        "identity-or-site-conflict",
        "hard-block",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_CONTEXT",
        "DEPARTMENT_SUPPORT"
      ],
      "layout": "核心专科",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/dental-care.png"
    },
    {
      "route_id": "dental-evidence",
      "path": "#/dental-evidence",
      "screen_id": "HASH-180",
      "title": "口腔科检查、设备与来源证据",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_SPECIALTY_CLINICAL_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "SpecialtySupport",
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "device-offline",
        "identity-or-site-conflict",
        "hard-block",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_CONTEXT",
        "DEPARTMENT_SUPPORT"
      ],
      "layout": "核心专科",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/dental-evidence.png"
    },
    {
      "route_id": "dental-followup",
      "path": "#/dental-followup",
      "screen_id": "HASH-183",
      "title": "口腔科随访、转诊与交接",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_SPECIALTY_CLINICAL_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "SpecialtySupport",
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "device-offline",
        "identity-or-site-conflict",
        "hard-block",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_CONTEXT",
        "DEPARTMENT_SUPPORT"
      ],
      "layout": "核心专科",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/dental-followup.png"
    },
    {
      "route_id": "dental-qc",
      "path": "#/dental-qc",
      "screen_id": "HASH-184",
      "title": "口腔科质控与安全",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_SPECIALTY_CLINICAL_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "SpecialtySupport",
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "device-offline",
        "identity-or-site-conflict",
        "hard-block",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_CONTEXT",
        "DEPARTMENT_SUPPORT"
      ],
      "layout": "核心专科",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/dental-qc.png"
    },
    {
      "route_id": "dental-record",
      "path": "#/dental-record",
      "screen_id": "HASH-179",
      "title": "口腔科专科病历",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_SPECIALTY_CLINICAL_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "SpecialtySupport",
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "device-offline",
        "identity-or-site-conflict",
        "hard-block",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_CONTEXT",
        "DEPARTMENT_SUPPORT"
      ],
      "layout": "核心专科",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/dental-record.png"
    },
    {
      "route_id": "dental-treatment",
      "path": "#/dental-treatment",
      "screen_id": "HASH-181",
      "title": "口腔科诊疗执行中心",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_SPECIALTY_CLINICAL_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "SpecialtySupport",
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "device-offline",
        "identity-or-site-conflict",
        "hard-block",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_CONTEXT",
        "DEPARTMENT_SUPPORT"
      ],
      "layout": "核心专科",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/dental-treatment.png"
    },
    {
      "route_id": "dental-workbench",
      "path": "#/dental-workbench",
      "screen_id": "HASH-178",
      "title": "口腔科工作台",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_SPECIALTY_CLINICAL_ROLE"
      ],
      "fr_refs": [
        "FR-136"
      ],
      "ac_refs": [
        "AC-136"
      ],
      "scr_refs": [
        "SCR-134–137/SCR-167–169"
      ],
      "requirement_refs": [
        "FR-136",
        "AC-136",
        "SCR-134–137/SCR-167–169"
      ],
      "api_refs": [
        "SpecialtySupport",
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "device-offline",
        "identity-or-site-conflict",
        "hard-block",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_CONTEXT",
        "DEPARTMENT_SUPPORT"
      ],
      "layout": "核心专科",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/dental-workbench.png"
    },
    {
      "route_id": "department-qc",
      "path": "#/department-qc",
      "screen_id": "HASH-065",
      "title": "院科病历质控与整改",
      "primary_domain": "QUALITY",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [
        "FR-016",
        "FR-036",
        "FR-061"
      ],
      "ac_refs": [
        "AC-016",
        "AC-036",
        "AC-061"
      ],
      "scr_refs": [
        "SCR-032"
      ],
      "requirement_refs": [
        "FR-016",
        "AC-016",
        "SCR-032",
        "FR-036",
        "AC-036",
        "FR-061",
        "AC-061"
      ],
      "api_refs": [
        "Platform API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "permission",
        "timeout",
        "conflict",
        "approval",
        "publishing",
        "failed",
        "rollback",
        "success"
      ],
      "guards": [
        "SESSION",
        "ROLE"
      ],
      "layout": "治理与管理",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/department-qc.png"
    },
    {
      "route_id": "dermatology-care",
      "path": "#/dermatology-care",
      "screen_id": "HASH-189",
      "title": "皮肤科关键诊疗流程",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_SPECIALTY_CLINICAL_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "SpecialtySupport",
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "device-offline",
        "identity-or-site-conflict",
        "hard-block",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_CONTEXT",
        "DEPARTMENT_SUPPORT"
      ],
      "layout": "核心专科",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/dermatology-care.png"
    },
    {
      "route_id": "dermatology-evidence",
      "path": "#/dermatology-evidence",
      "screen_id": "HASH-187",
      "title": "皮肤科检查、设备与来源证据",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_SPECIALTY_CLINICAL_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "SpecialtySupport",
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "device-offline",
        "identity-or-site-conflict",
        "hard-block",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_CONTEXT",
        "DEPARTMENT_SUPPORT"
      ],
      "layout": "核心专科",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/dermatology-evidence.png"
    },
    {
      "route_id": "dermatology-followup",
      "path": "#/dermatology-followup",
      "screen_id": "HASH-190",
      "title": "皮肤科随访、转诊与交接",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_SPECIALTY_CLINICAL_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "SpecialtySupport",
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "device-offline",
        "identity-or-site-conflict",
        "hard-block",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_CONTEXT",
        "DEPARTMENT_SUPPORT"
      ],
      "layout": "核心专科",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/dermatology-followup.png"
    },
    {
      "route_id": "dermatology-qc",
      "path": "#/dermatology-qc",
      "screen_id": "HASH-191",
      "title": "皮肤科质控与安全",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_SPECIALTY_CLINICAL_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "SpecialtySupport",
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "device-offline",
        "identity-or-site-conflict",
        "hard-block",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_CONTEXT",
        "DEPARTMENT_SUPPORT"
      ],
      "layout": "核心专科",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/dermatology-qc.png"
    },
    {
      "route_id": "dermatology-record",
      "path": "#/dermatology-record",
      "screen_id": "HASH-186",
      "title": "皮肤科专科病历",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_SPECIALTY_CLINICAL_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "SpecialtySupport",
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "device-offline",
        "identity-or-site-conflict",
        "hard-block",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_CONTEXT",
        "DEPARTMENT_SUPPORT"
      ],
      "layout": "核心专科",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/dermatology-record.png"
    },
    {
      "route_id": "dermatology-treatment",
      "path": "#/dermatology-treatment",
      "screen_id": "HASH-188",
      "title": "皮肤科诊疗执行中心",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_SPECIALTY_CLINICAL_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "SpecialtySupport",
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "device-offline",
        "identity-or-site-conflict",
        "hard-block",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_CONTEXT",
        "DEPARTMENT_SUPPORT"
      ],
      "layout": "核心专科",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/dermatology-treatment.png"
    },
    {
      "route_id": "dermatology-workbench",
      "path": "#/dermatology-workbench",
      "screen_id": "HASH-185",
      "title": "皮肤科工作台",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_SPECIALTY_CLINICAL_ROLE"
      ],
      "fr_refs": [
        "FR-137"
      ],
      "ac_refs": [
        "AC-137"
      ],
      "scr_refs": [
        "SCR-138–141/SCR-170–172"
      ],
      "requirement_refs": [
        "FR-137",
        "AC-137",
        "SCR-138–141/SCR-170–172"
      ],
      "api_refs": [
        "SpecialtySupport",
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "device-offline",
        "identity-or-site-conflict",
        "hard-block",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_CONTEXT",
        "DEPARTMENT_SUPPORT"
      ],
      "layout": "核心专科",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/dermatology-workbench.png"
    },
    {
      "route_id": "device-monitoring",
      "path": "#/device-monitoring",
      "screen_id": "HASH-061",
      "title": "监护绑定、趋势与告警",
      "primary_domain": "COLLABORATION",
      "roles": [
        "AUTHORIZED_CLINICAL_OR_INTEGRATION_ROLE"
      ],
      "fr_refs": [
        "FR-048"
      ],
      "ac_refs": [
        "AC-048"
      ],
      "scr_refs": [
        "SCR-030"
      ],
      "requirement_refs": [
        "FR-048",
        "AC-048",
        "SCR-030"
      ],
      "api_refs": [
        "Integration/Result/DICOM API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "timeout",
        "duplicate",
        "verification",
        "hard-block",
        "success",
        "compensation"
      ],
      "guards": [
        "SESSION",
        "ROLE",
        "SOURCE_STATUS"
      ],
      "layout": "诊疗执行",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/device-monitoring.png"
    },
    {
      "route_id": "devices",
      "path": "#/devices",
      "screen_id": "HASH-076",
      "title": "医疗设备目录与接入状态",
      "primary_domain": "CONFIG",
      "roles": [
        "AUTHORIZED_CLINICAL_OR_INTEGRATION_ROLE"
      ],
      "fr_refs": [
        "FR-059"
      ],
      "ac_refs": [
        "AC-059"
      ],
      "scr_refs": [
        "SCR-039"
      ],
      "requirement_refs": [
        "FR-059",
        "AC-059",
        "SCR-039"
      ],
      "api_refs": [
        "Integration/Result/DICOM API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "permission",
        "timeout",
        "conflict",
        "approval",
        "publishing",
        "failed",
        "rollback",
        "success"
      ],
      "guards": [
        "SESSION",
        "ROLE",
        "SOURCE_STATUS"
      ],
      "layout": "治理与管理",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/devices.png"
    },
    {
      "route_id": "emergency",
      "path": "#/emergency",
      "screen_id": "HASH-017",
      "title": "急诊工作台",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [
        "FR-091"
      ],
      "ac_refs": [
        "AC-091"
      ],
      "scr_refs": [
        "SCR-070"
      ],
      "requirement_refs": [
        "FR-091",
        "AC-091",
        "SCR-070"
      ],
      "api_refs": [
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "offline",
        "permission",
        "session-expired",
        "patient-switch",
        "conflict",
        "blocked",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_OR_PURPOSE"
      ],
      "layout": "临床工作域",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/emergency.png"
    },
    {
      "route_id": "emergency-access",
      "path": "#/emergency-access",
      "screen_id": "HASH-007",
      "title": "紧急访问与待核身份",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [
        "FR-015"
      ],
      "ac_refs": [
        "AC-015"
      ],
      "scr_refs": [
        "SCR-006"
      ],
      "requirement_refs": [
        "FR-015",
        "AC-015",
        "SCR-006"
      ],
      "api_refs": [
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "offline",
        "permission",
        "session-expired",
        "patient-switch",
        "conflict",
        "blocked",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_OR_PURPOSE"
      ],
      "layout": "临床工作域",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/emergency-access.png"
    },
    {
      "route_id": "ent-care",
      "path": "#/ent-care",
      "screen_id": "HASH-175",
      "title": "耳鼻咽喉科关键诊疗流程",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_SPECIALTY_CLINICAL_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "SpecialtySupport",
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "device-offline",
        "identity-or-site-conflict",
        "hard-block",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_CONTEXT",
        "DEPARTMENT_SUPPORT"
      ],
      "layout": "核心专科",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/ent-care.png"
    },
    {
      "route_id": "ent-evidence",
      "path": "#/ent-evidence",
      "screen_id": "HASH-173",
      "title": "耳鼻咽喉科检查、设备与来源证据",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_SPECIALTY_CLINICAL_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "SpecialtySupport",
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "device-offline",
        "identity-or-site-conflict",
        "hard-block",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_CONTEXT",
        "DEPARTMENT_SUPPORT"
      ],
      "layout": "核心专科",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/ent-evidence.png"
    },
    {
      "route_id": "ent-followup",
      "path": "#/ent-followup",
      "screen_id": "HASH-176",
      "title": "耳鼻咽喉科随访、转诊与交接",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_SPECIALTY_CLINICAL_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "SpecialtySupport",
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "device-offline",
        "identity-or-site-conflict",
        "hard-block",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_CONTEXT",
        "DEPARTMENT_SUPPORT"
      ],
      "layout": "核心专科",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/ent-followup.png"
    },
    {
      "route_id": "ent-qc",
      "path": "#/ent-qc",
      "screen_id": "HASH-177",
      "title": "耳鼻咽喉科质控与安全",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_SPECIALTY_CLINICAL_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "SpecialtySupport",
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "device-offline",
        "identity-or-site-conflict",
        "hard-block",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_CONTEXT",
        "DEPARTMENT_SUPPORT"
      ],
      "layout": "核心专科",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/ent-qc.png"
    },
    {
      "route_id": "ent-record",
      "path": "#/ent-record",
      "screen_id": "HASH-172",
      "title": "耳鼻咽喉科专科病历",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_SPECIALTY_CLINICAL_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "SpecialtySupport",
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "device-offline",
        "identity-or-site-conflict",
        "hard-block",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_CONTEXT",
        "DEPARTMENT_SUPPORT"
      ],
      "layout": "核心专科",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/ent-record.png"
    },
    {
      "route_id": "ent-treatment",
      "path": "#/ent-treatment",
      "screen_id": "HASH-174",
      "title": "耳鼻咽喉科诊疗执行中心",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_SPECIALTY_CLINICAL_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "SpecialtySupport",
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "device-offline",
        "identity-or-site-conflict",
        "hard-block",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_CONTEXT",
        "DEPARTMENT_SUPPORT"
      ],
      "layout": "核心专科",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/ent-treatment.png"
    },
    {
      "route_id": "ent-workbench",
      "path": "#/ent-workbench",
      "screen_id": "HASH-171",
      "title": "耳鼻咽喉科工作台",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_SPECIALTY_CLINICAL_ROLE"
      ],
      "fr_refs": [
        "FR-135"
      ],
      "ac_refs": [
        "AC-135"
      ],
      "scr_refs": [
        "SCR-130–133/SCR-164–166"
      ],
      "requirement_refs": [
        "FR-135",
        "AC-135",
        "SCR-130–133/SCR-164–166"
      ],
      "api_refs": [
        "SpecialtySupport",
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "device-offline",
        "identity-or-site-conflict",
        "hard-block",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_CONTEXT",
        "DEPARTMENT_SUPPORT"
      ],
      "layout": "核心专科",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/ent-workbench.png"
    },
    {
      "route_id": "er-handoff",
      "path": "#/er-handoff",
      "screen_id": "HASH-022",
      "title": "急诊会诊、交接与转运",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "Platform API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "offline",
        "permission",
        "session-expired",
        "patient-switch",
        "conflict",
        "blocked",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "ROLE"
      ],
      "layout": "临床工作域",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/er-handoff.png"
    },
    {
      "route_id": "er-nursing",
      "path": "#/er-nursing",
      "screen_id": "HASH-020",
      "title": "急诊护理、输液与执行",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "offline",
        "permission",
        "session-expired",
        "patient-switch",
        "conflict",
        "blocked",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_OR_PURPOSE"
      ],
      "layout": "临床工作域",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/er-nursing.png"
    },
    {
      "route_id": "er-observation",
      "path": "#/er-observation",
      "screen_id": "HASH-021",
      "title": "急诊抢救留观与去向",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "Platform API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "offline",
        "permission",
        "session-expired",
        "patient-switch",
        "conflict",
        "blocked",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "ROLE"
      ],
      "layout": "临床工作域",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/er-observation.png"
    },
    {
      "route_id": "er-record",
      "path": "#/er-record",
      "screen_id": "HASH-019",
      "title": "急诊病历与抢救记录",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "offline",
        "permission",
        "session-expired",
        "patient-switch",
        "conflict",
        "blocked",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_OR_PURPOSE"
      ],
      "layout": "临床工作域",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/er-record.png"
    },
    {
      "route_id": "er-triage",
      "path": "#/er-triage",
      "screen_id": "HASH-018",
      "title": "急诊预检分诊与分区",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "Platform API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "offline",
        "permission",
        "session-expired",
        "patient-switch",
        "conflict",
        "blocked",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "ROLE"
      ],
      "layout": "临床工作域",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/er-triage.png"
    },
    {
      "route_id": "form-designer",
      "path": "#/form-designer",
      "screen_id": "HASH-106",
      "title": "表单与病历模板设计器",
      "primary_domain": "CONFIG",
      "roles": [
        "ADMIN_OR_AUTHORIZED_GOVERNANCE_ROLE"
      ],
      "fr_refs": [
        "FR-064"
      ],
      "ac_refs": [
        "AC-064"
      ],
      "scr_refs": [
        "SCR-042"
      ],
      "requirement_refs": [
        "FR-064",
        "AC-064",
        "SCR-042"
      ],
      "api_refs": [
        "Admin/Config API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "permission",
        "timeout",
        "conflict",
        "approval",
        "publishing",
        "failed",
        "rollback",
        "success"
      ],
      "guards": [
        "SESSION",
        "ROLE",
        "SCOPE",
        "SEPARATION_OF_DUTIES"
      ],
      "layout": "治理与管理",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/form-designer.png"
    },
    {
      "route_id": "imaging-workbench",
      "path": "#/imaging-workbench",
      "screen_id": "HASH-057",
      "title": "检查影像工作台",
      "primary_domain": "COLLABORATION",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [
        "FR-044"
      ],
      "ac_refs": [
        "AC-044"
      ],
      "scr_refs": [
        "SCR-026"
      ],
      "requirement_refs": [
        "FR-044",
        "AC-044",
        "SCR-026"
      ],
      "api_refs": [
        "Platform API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "timeout",
        "duplicate",
        "verification",
        "hard-block",
        "success",
        "compensation"
      ],
      "guards": [
        "SESSION",
        "ROLE"
      ],
      "layout": "诊疗执行",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/imaging-workbench.png"
    },
    {
      "route_id": "infection-events",
      "path": "#/infection-events",
      "screen_id": "HASH-067",
      "title": "院感、传染病与不良事件",
      "primary_domain": "QUALITY",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [
        "FR-052"
      ],
      "ac_refs": [
        "AC-052"
      ],
      "scr_refs": [
        "SCR-035"
      ],
      "requirement_refs": [
        "FR-052",
        "AC-052",
        "SCR-035"
      ],
      "api_refs": [
        "Platform API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "permission",
        "timeout",
        "conflict",
        "approval",
        "publishing",
        "failed",
        "rollback",
        "success"
      ],
      "guards": [
        "SESSION",
        "ROLE"
      ],
      "layout": "治理与管理",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/infection-events.png"
    },
    {
      "route_id": "inpatient",
      "path": "#/inpatient",
      "screen_id": "HASH-023",
      "title": "住院医生工作站",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [
        "FR-022",
        "FR-082",
        "FR-090"
      ],
      "ac_refs": [
        "AC-022",
        "AC-082",
        "AC-090"
      ],
      "scr_refs": [
        "SCR-019",
        "SCR-065"
      ],
      "requirement_refs": [
        "FR-022",
        "AC-022",
        "SCR-019",
        "FR-082",
        "AC-082",
        "SCR-065",
        "FR-090",
        "AC-090"
      ],
      "api_refs": [
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "offline",
        "permission",
        "session-expired",
        "patient-switch",
        "conflict",
        "blocked",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_OR_PURPOSE"
      ],
      "layout": "临床工作域",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/inpatient.png"
    },
    {
      "route_id": "inpatient-course",
      "path": "#/inpatient-course",
      "screen_id": "HASH-025",
      "title": "住院病程与文书中心",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [
        "FR-084"
      ],
      "ac_refs": [
        "AC-084"
      ],
      "scr_refs": [
        "SCR-067"
      ],
      "requirement_refs": [
        "FR-084",
        "AC-084",
        "SCR-067"
      ],
      "api_refs": [
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "offline",
        "permission",
        "session-expired",
        "patient-switch",
        "conflict",
        "blocked",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_OR_PURPOSE"
      ],
      "layout": "临床工作域",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/inpatient-course.png"
    },
    {
      "route_id": "inpatient-discharge",
      "path": "#/inpatient-discharge",
      "screen_id": "HASH-033",
      "title": "出院病历与病案归档闭环",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [
        "FR-087"
      ],
      "ac_refs": [
        "AC-087"
      ],
      "scr_refs": [
        "SCR-068"
      ],
      "requirement_refs": [
        "FR-087",
        "AC-087",
        "SCR-068"
      ],
      "api_refs": [
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "offline",
        "permission",
        "session-expired",
        "patient-switch",
        "conflict",
        "blocked",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_OR_PURPOSE"
      ],
      "layout": "临床工作域",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/inpatient-discharge.png"
    },
    {
      "route_id": "inpatient-doc-editor",
      "path": "#/inpatient-doc-editor",
      "screen_id": "HASH-026",
      "title": "住院病历 · 专注编辑",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [
        "FR-085"
      ],
      "ac_refs": [
        "AC-085"
      ],
      "scr_refs": [
        "SCR-067"
      ],
      "requirement_refs": [
        "FR-085",
        "AC-085",
        "SCR-067"
      ],
      "api_refs": [
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "offline",
        "permission",
        "session-expired",
        "patient-switch",
        "conflict",
        "blocked",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_OR_PURPOSE"
      ],
      "layout": "临床工作域",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/inpatient-doc-editor.png"
    },
    {
      "route_id": "inpatient-doc-qc",
      "path": "#/inpatient-doc-qc",
      "screen_id": "HASH-027",
      "title": "住院病历质控与分级审签",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [
        "FR-086"
      ],
      "ac_refs": [
        "AC-086"
      ],
      "scr_refs": [
        "SCR-067"
      ],
      "requirement_refs": [
        "FR-086",
        "AC-086",
        "SCR-067"
      ],
      "api_refs": [
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "offline",
        "permission",
        "session-expired",
        "patient-switch",
        "conflict",
        "blocked",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_OR_PURPOSE"
      ],
      "layout": "临床工作域",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/inpatient-doc-qc.png"
    },
    {
      "route_id": "inpatient-doc-versions",
      "path": "#/inpatient-doc-versions",
      "screen_id": "HASH-028",
      "title": "住院文书版本与查房证据",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "offline",
        "permission",
        "session-expired",
        "patient-switch",
        "conflict",
        "blocked",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_OR_PURPOSE"
      ],
      "layout": "临床工作域",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/inpatient-doc-versions.png"
    },
    {
      "route_id": "inpatient-overview",
      "path": "#/inpatient-overview",
      "screen_id": "HASH-024",
      "title": "住院患者总览",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [
        "FR-083"
      ],
      "ac_refs": [
        "AC-083"
      ],
      "scr_refs": [
        "SCR-066"
      ],
      "requirement_refs": [
        "FR-083",
        "AC-083",
        "SCR-066"
      ],
      "api_refs": [
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "offline",
        "permission",
        "session-expired",
        "patient-switch",
        "conflict",
        "blocked",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_OR_PURPOSE"
      ],
      "layout": "临床工作域",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/inpatient-overview.png"
    },
    {
      "route_id": "inpatient-pharmacy",
      "path": "#/inpatient-pharmacy",
      "screen_id": "HASH-054",
      "title": "住院药房、配液与床旁给药",
      "primary_domain": "COLLABORATION",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [
        "FR-042"
      ],
      "ac_refs": [
        "AC-042"
      ],
      "scr_refs": [
        "SCR-024"
      ],
      "requirement_refs": [
        "FR-042",
        "AC-042",
        "SCR-024"
      ],
      "api_refs": [
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "timeout",
        "duplicate",
        "verification",
        "hard-block",
        "success",
        "compensation"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_OR_PURPOSE"
      ],
      "layout": "诊疗执行",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/inpatient-pharmacy.png"
    },
    {
      "route_id": "install",
      "path": "#/install",
      "screen_id": "HASH-111",
      "title": "安装向导与首次健康检查",
      "primary_domain": "CONFIG",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [
        "FR-025"
      ],
      "ac_refs": [
        "AC-025"
      ],
      "scr_refs": [
        "SCR-060"
      ],
      "requirement_refs": [
        "FR-025",
        "AC-025",
        "SCR-060"
      ],
      "api_refs": [
        "Platform API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "permission",
        "timeout",
        "conflict",
        "approval",
        "publishing",
        "failed",
        "rollback",
        "success"
      ],
      "guards": [
        "SESSION",
        "ROLE"
      ],
      "layout": "治理与管理",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/install.png"
    },
    {
      "route_id": "integration",
      "path": "#/integration",
      "screen_id": "HASH-070",
      "title": "外部系统集成与互操作中心",
      "primary_domain": "CONFIG",
      "roles": [
        "AUTHORIZED_CLINICAL_OR_INTEGRATION_ROLE"
      ],
      "fr_refs": [
        "FR-102"
      ],
      "ac_refs": [
        "AC-102"
      ],
      "scr_refs": [
        "SCR-081/082"
      ],
      "requirement_refs": [
        "FR-102",
        "AC-102",
        "SCR-081/082"
      ],
      "api_refs": [
        "Integration/Result/DICOM API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "permission",
        "timeout",
        "conflict",
        "approval",
        "publishing",
        "failed",
        "rollback",
        "success"
      ],
      "guards": [
        "SESSION",
        "ROLE",
        "SOURCE_STATUS"
      ],
      "layout": "治理与管理",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/integration.png"
    },
    {
      "route_id": "integration-connectors",
      "path": "#/integration-connectors",
      "screen_id": "HASH-071",
      "title": "连接器目录与能力卡",
      "primary_domain": "CONFIG",
      "roles": [
        "AUTHORIZED_CLINICAL_OR_INTEGRATION_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "Integration/Result/DICOM API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "permission",
        "timeout",
        "conflict",
        "approval",
        "publishing",
        "failed",
        "rollback",
        "success"
      ],
      "guards": [
        "SESSION",
        "ROLE",
        "SOURCE_STATUS"
      ],
      "layout": "治理与管理",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/integration-connectors.png"
    },
    {
      "route_id": "integration-mapping",
      "path": "#/integration-mapping",
      "screen_id": "HASH-072",
      "title": "字段映射与集成路由",
      "primary_domain": "CONFIG",
      "roles": [
        "AUTHORIZED_CLINICAL_OR_INTEGRATION_ROLE"
      ],
      "fr_refs": [
        "FR-067"
      ],
      "ac_refs": [
        "AC-067"
      ],
      "scr_refs": [
        "SCR-045"
      ],
      "requirement_refs": [
        "FR-067",
        "AC-067",
        "SCR-045"
      ],
      "api_refs": [
        "Integration/Result/DICOM API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "permission",
        "timeout",
        "conflict",
        "approval",
        "publishing",
        "failed",
        "rollback",
        "success"
      ],
      "guards": [
        "SESSION",
        "ROLE",
        "SOURCE_STATUS"
      ],
      "layout": "治理与管理",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/integration-mapping.png"
    },
    {
      "route_id": "integration-messages",
      "path": "#/integration-messages",
      "screen_id": "HASH-073",
      "title": "集成消息追踪与业务对账",
      "primary_domain": "CONFIG",
      "roles": [
        "AUTHORIZED_CLINICAL_OR_INTEGRATION_ROLE"
      ],
      "fr_refs": [
        "FR-023",
        "FR-104"
      ],
      "ac_refs": [
        "AC-023",
        "AC-104"
      ],
      "scr_refs": [
        "SCR-036",
        "SCR-083/084"
      ],
      "requirement_refs": [
        "FR-023",
        "AC-023",
        "SCR-036",
        "FR-104",
        "AC-104",
        "SCR-083/084"
      ],
      "api_refs": [
        "Integration/Result/DICOM API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "permission",
        "timeout",
        "conflict",
        "approval",
        "publishing",
        "failed",
        "rollback",
        "success"
      ],
      "guards": [
        "SESSION",
        "ROLE",
        "SOURCE_STATUS"
      ],
      "layout": "治理与管理",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/integration-messages.png"
    },
    {
      "route_id": "ip-consult",
      "path": "#/ip-consult",
      "screen_id": "HASH-031",
      "title": "住院查房、会诊与协同",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "offline",
        "permission",
        "session-expired",
        "patient-switch",
        "conflict",
        "blocked",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_OR_PURPOSE"
      ],
      "layout": "临床工作域",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/ip-consult.png"
    },
    {
      "route_id": "ip-orders",
      "path": "#/ip-orders",
      "screen_id": "HASH-029",
      "title": "住院医嘱与用药中心",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "offline",
        "permission",
        "session-expired",
        "patient-switch",
        "conflict",
        "blocked",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_OR_PURPOSE"
      ],
      "layout": "临床工作域",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/ip-orders.png"
    },
    {
      "route_id": "ip-pathway",
      "path": "#/ip-pathway",
      "screen_id": "HASH-032",
      "title": "住院临床路径执行中心",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "offline",
        "permission",
        "session-expired",
        "patient-switch",
        "conflict",
        "blocked",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_OR_PURPOSE"
      ],
      "layout": "临床工作域",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/ip-pathway.png"
    },
    {
      "route_id": "ip-results",
      "path": "#/ip-results",
      "screen_id": "HASH-030",
      "title": "住院检查检验与危急值",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "offline",
        "permission",
        "session-expired",
        "patient-switch",
        "conflict",
        "blocked",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_OR_PURPOSE"
      ],
      "layout": "临床工作域",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/ip-results.png"
    },
    {
      "route_id": "knowledge-center",
      "path": "#/knowledge-center",
      "screen_id": "HASH-099",
      "title": "路径知识库",
      "primary_domain": "CONFIG",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "Platform API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "permission",
        "timeout",
        "conflict",
        "approval",
        "publishing",
        "failed",
        "rollback",
        "success"
      ],
      "guards": [
        "SESSION",
        "ROLE"
      ],
      "layout": "治理与管理",
      "source_status": "PLANNED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/"
    },
    {
      "route_id": "lab-workbench",
      "path": "#/lab-workbench",
      "screen_id": "HASH-055",
      "title": "检验工作台",
      "primary_domain": "COLLABORATION",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [
        "FR-043"
      ],
      "ac_refs": [
        "AC-043"
      ],
      "scr_refs": [
        "SCR-025"
      ],
      "requirement_refs": [
        "FR-043",
        "AC-043",
        "SCR-025"
      ],
      "api_refs": [
        "Platform API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "timeout",
        "duplicate",
        "verification",
        "hard-block",
        "success",
        "compensation"
      ],
      "guards": [
        "SESSION",
        "ROLE"
      ],
      "layout": "诊疗执行",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/lab-workbench.png"
    },
    {
      "route_id": "lis-report",
      "path": "#/lis-report",
      "screen_id": "HASH-042",
      "title": "LIS 检验报告调阅",
      "primary_domain": "RECORD",
      "roles": [
        "AUTHORIZED_CLINICAL_OR_INTEGRATION_ROLE"
      ],
      "fr_refs": [
        "FR-103"
      ],
      "ac_refs": [
        "AC-103"
      ],
      "scr_refs": [
        "SCR-079/080"
      ],
      "requirement_refs": [
        "FR-103",
        "AC-103",
        "SCR-079/080"
      ],
      "api_refs": [
        "Integration/Result/DICOM API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "autosaving",
        "saved",
        "offline",
        "permission",
        "concurrent-conflict",
        "rule-blocked",
        "signed",
        "corrected",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "ROLE",
        "SOURCE_STATUS"
      ],
      "layout": "病历与病案",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/lis-report.png"
    },
    {
      "route_id": "login-context",
      "path": "#/login-context",
      "screen_id": "HASH-002",
      "title": "登录、锁屏恢复与工作上下文",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "Platform API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "offline",
        "permission",
        "session-expired",
        "patient-switch",
        "conflict",
        "blocked",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "ROLE"
      ],
      "layout": "临床工作域",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/login-context.png"
    },
    {
      "route_id": "mental-care",
      "path": "#/mental-care",
      "screen_id": "HASH-161",
      "title": "精神心理科关键诊疗流程",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_SPECIALTY_CLINICAL_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "SpecialtySupport",
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "device-offline",
        "identity-or-site-conflict",
        "hard-block",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_CONTEXT",
        "DEPARTMENT_SUPPORT"
      ],
      "layout": "核心专科",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/mental-care.png"
    },
    {
      "route_id": "mental-evidence",
      "path": "#/mental-evidence",
      "screen_id": "HASH-159",
      "title": "精神心理科检查、设备与来源证据",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_SPECIALTY_CLINICAL_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "SpecialtySupport",
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "device-offline",
        "identity-or-site-conflict",
        "hard-block",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_CONTEXT",
        "DEPARTMENT_SUPPORT"
      ],
      "layout": "核心专科",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/mental-evidence.png"
    },
    {
      "route_id": "mental-followup",
      "path": "#/mental-followup",
      "screen_id": "HASH-162",
      "title": "精神心理科随访、转诊与交接",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_SPECIALTY_CLINICAL_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "SpecialtySupport",
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "device-offline",
        "identity-or-site-conflict",
        "hard-block",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_CONTEXT",
        "DEPARTMENT_SUPPORT"
      ],
      "layout": "核心专科",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/mental-followup.png"
    },
    {
      "route_id": "mental-qc",
      "path": "#/mental-qc",
      "screen_id": "HASH-163",
      "title": "精神心理科质控与安全",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_SPECIALTY_CLINICAL_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "SpecialtySupport",
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "device-offline",
        "identity-or-site-conflict",
        "hard-block",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_CONTEXT",
        "DEPARTMENT_SUPPORT"
      ],
      "layout": "核心专科",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/mental-qc.png"
    },
    {
      "route_id": "mental-record",
      "path": "#/mental-record",
      "screen_id": "HASH-158",
      "title": "精神心理科专科病历",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_SPECIALTY_CLINICAL_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "SpecialtySupport",
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "device-offline",
        "identity-or-site-conflict",
        "hard-block",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_CONTEXT",
        "DEPARTMENT_SUPPORT"
      ],
      "layout": "核心专科",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/mental-record.png"
    },
    {
      "route_id": "mental-treatment",
      "path": "#/mental-treatment",
      "screen_id": "HASH-160",
      "title": "精神心理科诊疗执行中心",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_SPECIALTY_CLINICAL_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "SpecialtySupport",
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "device-offline",
        "identity-or-site-conflict",
        "hard-block",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_CONTEXT",
        "DEPARTMENT_SUPPORT"
      ],
      "layout": "核心专科",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/mental-treatment.png"
    },
    {
      "route_id": "mental-workbench",
      "path": "#/mental-workbench",
      "screen_id": "HASH-157",
      "title": "精神心理科工作台",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_SPECIALTY_CLINICAL_ROLE"
      ],
      "fr_refs": [
        "FR-133"
      ],
      "ac_refs": [
        "AC-133"
      ],
      "scr_refs": [
        "SCR-122–125/SCR-158–160"
      ],
      "requirement_refs": [
        "FR-133",
        "AC-133",
        "SCR-122–125/SCR-158–160"
      ],
      "api_refs": [
        "SpecialtySupport",
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "device-offline",
        "identity-or-site-conflict",
        "hard-block",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_CONTEXT",
        "DEPARTMENT_SUPPORT"
      ],
      "layout": "核心专科",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/mental-workbench.png"
    },
    {
      "route_id": "migration",
      "path": "#/migration",
      "screen_id": "HASH-074",
      "title": "历史数据迁移与上线切换",
      "primary_domain": "CONFIG",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [
        "FR-038"
      ],
      "ac_refs": [
        "AC-038"
      ],
      "scr_refs": [
        "SCR-037"
      ],
      "requirement_refs": [
        "FR-038",
        "AC-038",
        "SCR-037"
      ],
      "api_refs": [
        "Platform API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "permission",
        "timeout",
        "conflict",
        "approval",
        "publishing",
        "failed",
        "rollback",
        "success"
      ],
      "guards": [
        "SESSION",
        "ROLE"
      ],
      "layout": "治理与管理",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/migration.png"
    },
    {
      "route_id": "model-connection",
      "path": "#/model-connection",
      "screen_id": "HASH-088",
      "title": "模型连接与数据边界",
      "primary_domain": "AI",
      "roles": [
        "AUTHORIZED_CLINICAL_OR_AI_GOVERNANCE_ROLE"
      ],
      "fr_refs": [
        "FR-071"
      ],
      "ac_refs": [
        "AC-071"
      ],
      "scr_refs": [
        "SCR-049"
      ],
      "requirement_refs": [
        "FR-071",
        "AC-071",
        "SCR-049"
      ],
      "api_refs": [
        "ContextLease",
        "AIRun",
        "AIProposal"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "permission",
        "timeout",
        "conflict",
        "approval",
        "publishing",
        "failed",
        "rollback",
        "success"
      ],
      "guards": [
        "SESSION",
        "PURPOSE",
        "MODEL_POLICY",
        "AI_USE_CASE"
      ],
      "layout": "治理与管理",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/model-connection.png"
    },
    {
      "route_id": "model-evaluation",
      "path": "#/model-evaluation",
      "screen_id": "HASH-090",
      "title": "模型评估、影子、灰度与隔离",
      "primary_domain": "AI",
      "roles": [
        "AUTHORIZED_CLINICAL_OR_AI_GOVERNANCE_ROLE"
      ],
      "fr_refs": [
        "FR-073"
      ],
      "ac_refs": [
        "AC-073"
      ],
      "scr_refs": [
        "SCR-051"
      ],
      "requirement_refs": [
        "FR-073",
        "AC-073",
        "SCR-051"
      ],
      "api_refs": [
        "ContextLease",
        "AIRun",
        "AIProposal"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "permission",
        "timeout",
        "conflict",
        "approval",
        "publishing",
        "failed",
        "rollback",
        "success"
      ],
      "guards": [
        "SESSION",
        "PURPOSE",
        "MODEL_POLICY",
        "AI_USE_CASE"
      ],
      "layout": "治理与管理",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/model-evaluation.png"
    },
    {
      "route_id": "model-routing",
      "path": "#/model-routing",
      "screen_id": "HASH-089",
      "title": "模型路由、主备与人工降级",
      "primary_domain": "AI",
      "roles": [
        "AUTHORIZED_CLINICAL_OR_AI_GOVERNANCE_ROLE"
      ],
      "fr_refs": [
        "FR-072"
      ],
      "ac_refs": [
        "AC-072"
      ],
      "scr_refs": [
        "SCR-050"
      ],
      "requirement_refs": [
        "FR-072",
        "AC-072",
        "SCR-050"
      ],
      "api_refs": [
        "ContextLease",
        "AIRun",
        "AIProposal"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "permission",
        "timeout",
        "conflict",
        "approval",
        "publishing",
        "failed",
        "rollback",
        "success"
      ],
      "guards": [
        "SESSION",
        "PURPOSE",
        "MODEL_POLICY",
        "AI_USE_CASE"
      ],
      "layout": "治理与管理",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/model-routing.png"
    },
    {
      "route_id": "models",
      "path": "#/models",
      "screen_id": "HASH-087",
      "title": "基座模型目录与路由",
      "primary_domain": "AI",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [
        "FR-070"
      ],
      "ac_refs": [
        "AC-070"
      ],
      "scr_refs": [
        "SCR-048"
      ],
      "requirement_refs": [
        "FR-070",
        "AC-070",
        "SCR-048"
      ],
      "api_refs": [
        "Platform API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "permission",
        "timeout",
        "conflict",
        "approval",
        "publishing",
        "failed",
        "rollback",
        "success"
      ],
      "guards": [
        "SESSION",
        "ROLE"
      ],
      "layout": "治理与管理",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/models.png"
    },
    {
      "route_id": "neonatal-care",
      "path": "#/neonatal-care",
      "screen_id": "HASH-154",
      "title": "新生儿科关键诊疗流程",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_SPECIALTY_CLINICAL_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "SpecialtySupport",
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "device-offline",
        "identity-or-site-conflict",
        "hard-block",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_CONTEXT",
        "DEPARTMENT_SUPPORT"
      ],
      "layout": "核心专科",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/neonatal-care.png"
    },
    {
      "route_id": "neonatal-evidence",
      "path": "#/neonatal-evidence",
      "screen_id": "HASH-152",
      "title": "新生儿科检查、设备与来源证据",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_SPECIALTY_CLINICAL_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "SpecialtySupport",
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "device-offline",
        "identity-or-site-conflict",
        "hard-block",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_CONTEXT",
        "DEPARTMENT_SUPPORT"
      ],
      "layout": "核心专科",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/neonatal-evidence.png"
    },
    {
      "route_id": "neonatal-followup",
      "path": "#/neonatal-followup",
      "screen_id": "HASH-155",
      "title": "新生儿科随访、转诊与交接",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_SPECIALTY_CLINICAL_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "SpecialtySupport",
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "device-offline",
        "identity-or-site-conflict",
        "hard-block",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_CONTEXT",
        "DEPARTMENT_SUPPORT"
      ],
      "layout": "核心专科",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/neonatal-followup.png"
    },
    {
      "route_id": "neonatal-qc",
      "path": "#/neonatal-qc",
      "screen_id": "HASH-156",
      "title": "新生儿科质控与安全",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_SPECIALTY_CLINICAL_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "SpecialtySupport",
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "device-offline",
        "identity-or-site-conflict",
        "hard-block",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_CONTEXT",
        "DEPARTMENT_SUPPORT"
      ],
      "layout": "核心专科",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/neonatal-qc.png"
    },
    {
      "route_id": "neonatal-record",
      "path": "#/neonatal-record",
      "screen_id": "HASH-151",
      "title": "新生儿科专科病历",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_SPECIALTY_CLINICAL_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "SpecialtySupport",
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "device-offline",
        "identity-or-site-conflict",
        "hard-block",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_CONTEXT",
        "DEPARTMENT_SUPPORT"
      ],
      "layout": "核心专科",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/neonatal-record.png"
    },
    {
      "route_id": "neonatal-treatment",
      "path": "#/neonatal-treatment",
      "screen_id": "HASH-153",
      "title": "新生儿科诊疗执行中心",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_SPECIALTY_CLINICAL_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "SpecialtySupport",
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "device-offline",
        "identity-or-site-conflict",
        "hard-block",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_CONTEXT",
        "DEPARTMENT_SUPPORT"
      ],
      "layout": "核心专科",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/neonatal-treatment.png"
    },
    {
      "route_id": "neonatal-workbench",
      "path": "#/neonatal-workbench",
      "screen_id": "HASH-150",
      "title": "新生儿科工作台",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_SPECIALTY_CLINICAL_ROLE"
      ],
      "fr_refs": [
        "FR-132"
      ],
      "ac_refs": [
        "AC-132"
      ],
      "scr_refs": [
        "SCR-118–121/SCR-155–157"
      ],
      "requirement_refs": [
        "FR-132",
        "AC-132",
        "SCR-118–121/SCR-155–157"
      ],
      "api_refs": [
        "SpecialtySupport",
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "device-offline",
        "identity-or-site-conflict",
        "hard-block",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_CONTEXT",
        "DEPARTMENT_SUPPORT"
      ],
      "layout": "核心专科",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/neonatal-workbench.png"
    },
    {
      "route_id": "obgyn-care",
      "path": "#/obgyn-care",
      "screen_id": "HASH-133",
      "title": "妇产科关键诊疗流程",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_SPECIALTY_CLINICAL_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "SpecialtySupport",
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "device-offline",
        "identity-or-site-conflict",
        "hard-block",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_CONTEXT",
        "DEPARTMENT_SUPPORT"
      ],
      "layout": "核心专科",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/obgyn-care.png"
    },
    {
      "route_id": "obgyn-evidence",
      "path": "#/obgyn-evidence",
      "screen_id": "HASH-131",
      "title": "妇产科检查、设备与来源证据",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_SPECIALTY_CLINICAL_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "SpecialtySupport",
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "device-offline",
        "identity-or-site-conflict",
        "hard-block",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_CONTEXT",
        "DEPARTMENT_SUPPORT"
      ],
      "layout": "核心专科",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/obgyn-evidence.png"
    },
    {
      "route_id": "obgyn-followup",
      "path": "#/obgyn-followup",
      "screen_id": "HASH-134",
      "title": "妇产科随访、转诊与交接",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_SPECIALTY_CLINICAL_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "SpecialtySupport",
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "device-offline",
        "identity-or-site-conflict",
        "hard-block",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_CONTEXT",
        "DEPARTMENT_SUPPORT"
      ],
      "layout": "核心专科",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/obgyn-followup.png"
    },
    {
      "route_id": "obgyn-qc",
      "path": "#/obgyn-qc",
      "screen_id": "HASH-135",
      "title": "妇产科质控与安全",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_SPECIALTY_CLINICAL_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "SpecialtySupport",
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "device-offline",
        "identity-or-site-conflict",
        "hard-block",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_CONTEXT",
        "DEPARTMENT_SUPPORT"
      ],
      "layout": "核心专科",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/obgyn-qc.png"
    },
    {
      "route_id": "obgyn-record",
      "path": "#/obgyn-record",
      "screen_id": "HASH-130",
      "title": "妇产科专科病历",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_SPECIALTY_CLINICAL_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "SpecialtySupport",
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "device-offline",
        "identity-or-site-conflict",
        "hard-block",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_CONTEXT",
        "DEPARTMENT_SUPPORT"
      ],
      "layout": "核心专科",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/obgyn-record.png"
    },
    {
      "route_id": "obgyn-treatment",
      "path": "#/obgyn-treatment",
      "screen_id": "HASH-132",
      "title": "妇产科诊疗执行中心",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_SPECIALTY_CLINICAL_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "SpecialtySupport",
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "device-offline",
        "identity-or-site-conflict",
        "hard-block",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_CONTEXT",
        "DEPARTMENT_SUPPORT"
      ],
      "layout": "核心专科",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/obgyn-treatment.png"
    },
    {
      "route_id": "obgyn-workbench",
      "path": "#/obgyn-workbench",
      "screen_id": "HASH-129",
      "title": "妇产科工作台",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_SPECIALTY_CLINICAL_ROLE"
      ],
      "fr_refs": [
        "FR-129"
      ],
      "ac_refs": [
        "AC-129"
      ],
      "scr_refs": [
        "SCR-106–109/SCR-146–148"
      ],
      "requirement_refs": [
        "FR-129",
        "AC-129",
        "SCR-106–109/SCR-146–148"
      ],
      "api_refs": [
        "SpecialtySupport",
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "device-offline",
        "identity-or-site-conflict",
        "hard-block",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_CONTEXT",
        "DEPARTMENT_SUPPORT"
      ],
      "layout": "核心专科",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/obgyn-workbench.png"
    },
    {
      "route_id": "opd-consult",
      "path": "#/opd-consult",
      "screen_id": "HASH-015",
      "title": "门诊会诊、转诊与协同",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "offline",
        "permission",
        "session-expired",
        "patient-switch",
        "conflict",
        "blocked",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_OR_PURPOSE"
      ],
      "layout": "临床工作域",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/opd-consult.png"
    },
    {
      "route_id": "opd-diagnosis",
      "path": "#/opd-diagnosis",
      "screen_id": "HASH-012",
      "title": "门诊诊断与问题清单",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [
        "FR-012",
        "FR-033"
      ],
      "ac_refs": [
        "AC-012",
        "AC-033"
      ],
      "scr_refs": [
        "SCR-015"
      ],
      "requirement_refs": [
        "FR-012",
        "AC-012",
        "SCR-015",
        "FR-033",
        "AC-033"
      ],
      "api_refs": [
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "offline",
        "permission",
        "session-expired",
        "patient-switch",
        "conflict",
        "blocked",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_OR_PURPOSE"
      ],
      "layout": "临床工作域",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/opd-diagnosis.png"
    },
    {
      "route_id": "opd-followup",
      "path": "#/opd-followup",
      "screen_id": "HASH-016",
      "title": "门诊随访与终诊",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "offline",
        "permission",
        "session-expired",
        "patient-switch",
        "conflict",
        "blocked",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_OR_PURPOSE"
      ],
      "layout": "临床工作域",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/opd-followup.png"
    },
    {
      "route_id": "opd-orders",
      "path": "#/opd-orders",
      "screen_id": "HASH-013",
      "title": "门诊医嘱与处方",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [
        "FR-013",
        "FR-034"
      ],
      "ac_refs": [
        "AC-013",
        "AC-034"
      ],
      "scr_refs": [
        "SCR-015"
      ],
      "requirement_refs": [
        "FR-013",
        "AC-013",
        "SCR-015",
        "FR-034",
        "AC-034"
      ],
      "api_refs": [
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "offline",
        "permission",
        "session-expired",
        "patient-switch",
        "conflict",
        "blocked",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_OR_PURPOSE"
      ],
      "layout": "临床工作域",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/opd-orders.png"
    },
    {
      "route_id": "opd-record",
      "path": "#/opd-record",
      "screen_id": "HASH-011",
      "title": "门诊病历",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [
        "FR-128"
      ],
      "ac_refs": [
        "AC-128"
      ],
      "scr_refs": [
        "SCR-105"
      ],
      "requirement_refs": [
        "FR-128",
        "AC-128",
        "SCR-105"
      ],
      "api_refs": [
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "offline",
        "permission",
        "session-expired",
        "patient-switch",
        "conflict",
        "blocked",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_OR_PURPOSE"
      ],
      "layout": "临床工作域",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/opd-record.png"
    },
    {
      "route_id": "opd-results",
      "path": "#/opd-results",
      "screen_id": "HASH-014",
      "title": "门诊检查检验与结果",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [
        "FR-035"
      ],
      "ac_refs": [
        "AC-035"
      ],
      "scr_refs": [
        "SCR-016"
      ],
      "requirement_refs": [
        "FR-035",
        "AC-035",
        "SCR-016"
      ],
      "api_refs": [
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "offline",
        "permission",
        "session-expired",
        "patient-switch",
        "conflict",
        "blocked",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_OR_PURPOSE"
      ],
      "layout": "临床工作域",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/opd-results.png"
    },
    {
      "route_id": "opensource",
      "path": "#/opensource",
      "screen_id": "HASH-115",
      "title": "开源指标、文档与贡献入口",
      "primary_domain": "DATA",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [
        "FR-027",
        "FR-029"
      ],
      "ac_refs": [
        "AC-027",
        "AC-029"
      ],
      "scr_refs": [
        "SCR-064"
      ],
      "requirement_refs": [
        "FR-027",
        "AC-027",
        "SCR-064",
        "FR-029",
        "AC-029"
      ],
      "api_refs": [
        "Platform API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "permission",
        "timeout",
        "conflict",
        "approval",
        "publishing",
        "failed",
        "rollback",
        "success"
      ],
      "guards": [
        "SESSION",
        "ROLE"
      ],
      "layout": "治理与管理",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/opensource.png"
    },
    {
      "route_id": "operations",
      "path": "#/operations",
      "screen_id": "HASH-113",
      "title": "生产运行、灾备与停机续运",
      "primary_domain": "CONFIG",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [
        "FR-057",
        "FR-058"
      ],
      "ac_refs": [
        "AC-057",
        "AC-058"
      ],
      "scr_refs": [
        "SCR-062"
      ],
      "requirement_refs": [
        "FR-057",
        "AC-057",
        "SCR-062",
        "FR-058",
        "AC-058"
      ],
      "api_refs": [
        "Platform API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "permission",
        "timeout",
        "conflict",
        "approval",
        "publishing",
        "failed",
        "rollback",
        "success"
      ],
      "guards": [
        "SESSION",
        "ROLE"
      ],
      "layout": "治理与管理",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/operations.png"
    },
    {
      "route_id": "ophthalmology-care",
      "path": "#/ophthalmology-care",
      "screen_id": "HASH-168",
      "title": "眼科关键诊疗流程",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_SPECIALTY_CLINICAL_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "SpecialtySupport",
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "device-offline",
        "identity-or-site-conflict",
        "hard-block",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_CONTEXT",
        "DEPARTMENT_SUPPORT"
      ],
      "layout": "核心专科",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/ophthalmology-care.png"
    },
    {
      "route_id": "ophthalmology-evidence",
      "path": "#/ophthalmology-evidence",
      "screen_id": "HASH-166",
      "title": "眼科检查、设备与来源证据",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_SPECIALTY_CLINICAL_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "SpecialtySupport",
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "device-offline",
        "identity-or-site-conflict",
        "hard-block",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_CONTEXT",
        "DEPARTMENT_SUPPORT"
      ],
      "layout": "核心专科",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/ophthalmology-evidence.png"
    },
    {
      "route_id": "ophthalmology-followup",
      "path": "#/ophthalmology-followup",
      "screen_id": "HASH-169",
      "title": "眼科随访、转诊与交接",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_SPECIALTY_CLINICAL_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "SpecialtySupport",
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "device-offline",
        "identity-or-site-conflict",
        "hard-block",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_CONTEXT",
        "DEPARTMENT_SUPPORT"
      ],
      "layout": "核心专科",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/ophthalmology-followup.png"
    },
    {
      "route_id": "ophthalmology-qc",
      "path": "#/ophthalmology-qc",
      "screen_id": "HASH-170",
      "title": "眼科质控与安全",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_SPECIALTY_CLINICAL_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "SpecialtySupport",
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "device-offline",
        "identity-or-site-conflict",
        "hard-block",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_CONTEXT",
        "DEPARTMENT_SUPPORT"
      ],
      "layout": "核心专科",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/ophthalmology-qc.png"
    },
    {
      "route_id": "ophthalmology-record",
      "path": "#/ophthalmology-record",
      "screen_id": "HASH-165",
      "title": "眼科专科病历",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_SPECIALTY_CLINICAL_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "SpecialtySupport",
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "device-offline",
        "identity-or-site-conflict",
        "hard-block",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_CONTEXT",
        "DEPARTMENT_SUPPORT"
      ],
      "layout": "核心专科",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/ophthalmology-record.png"
    },
    {
      "route_id": "ophthalmology-treatment",
      "path": "#/ophthalmology-treatment",
      "screen_id": "HASH-167",
      "title": "眼科诊疗执行中心",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_SPECIALTY_CLINICAL_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "SpecialtySupport",
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "device-offline",
        "identity-or-site-conflict",
        "hard-block",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_CONTEXT",
        "DEPARTMENT_SUPPORT"
      ],
      "layout": "核心专科",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/ophthalmology-treatment.png"
    },
    {
      "route_id": "ophthalmology-workbench",
      "path": "#/ophthalmology-workbench",
      "screen_id": "HASH-164",
      "title": "眼科工作台",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_SPECIALTY_CLINICAL_ROLE"
      ],
      "fr_refs": [
        "FR-134"
      ],
      "ac_refs": [
        "AC-134"
      ],
      "scr_refs": [
        "SCR-126–129/SCR-161–163"
      ],
      "requirement_refs": [
        "FR-134",
        "AC-134",
        "SCR-126–129/SCR-161–163"
      ],
      "api_refs": [
        "SpecialtySupport",
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "device-offline",
        "identity-or-site-conflict",
        "hard-block",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_CONTEXT",
        "DEPARTMENT_SUPPORT"
      ],
      "layout": "核心专科",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/ophthalmology-workbench.png"
    },
    {
      "route_id": "outpatient",
      "path": "#/outpatient",
      "screen_id": "HASH-010",
      "title": "门诊医生工作台",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [
        "FR-019",
        "FR-020",
        "FR-089"
      ],
      "ac_refs": [
        "AC-019",
        "AC-020",
        "AC-089"
      ],
      "scr_refs": [
        "SCR-012"
      ],
      "requirement_refs": [
        "FR-019",
        "AC-019",
        "SCR-012",
        "FR-020",
        "AC-020",
        "FR-089",
        "AC-089"
      ],
      "api_refs": [
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "offline",
        "permission",
        "session-expired",
        "patient-switch",
        "conflict",
        "blocked",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_OR_PURPOSE"
      ],
      "layout": "临床工作域",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/outpatient.png"
    },
    {
      "route_id": "outpatient-pharmacy",
      "path": "#/outpatient-pharmacy",
      "screen_id": "HASH-053",
      "title": "门诊药房审方与调剂",
      "primary_domain": "COLLABORATION",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [
        "FR-041"
      ],
      "ac_refs": [
        "AC-041"
      ],
      "scr_refs": [
        "SCR-023"
      ],
      "requirement_refs": [
        "FR-041",
        "AC-041",
        "SCR-023"
      ],
      "api_refs": [
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "timeout",
        "duplicate",
        "verification",
        "hard-block",
        "success",
        "compensation"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_OR_PURPOSE"
      ],
      "layout": "诊疗执行",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/outpatient-pharmacy.png"
    },
    {
      "route_id": "pacs-viewer",
      "path": "#/pacs-viewer",
      "screen_id": "HASH-043",
      "title": "PACS 影像调阅",
      "primary_domain": "RECORD",
      "roles": [
        "AUTHORIZED_CLINICAL_OR_INTEGRATION_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "Integration/Result/DICOM API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "autosaving",
        "saved",
        "offline",
        "permission",
        "concurrent-conflict",
        "rule-blocked",
        "signed",
        "corrected",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "ROLE",
        "SOURCE_STATUS"
      ],
      "layout": "病历与病案",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/pacs-viewer.png"
    },
    {
      "route_id": "pathology-workbench",
      "path": "#/pathology-workbench",
      "screen_id": "HASH-056",
      "title": "病理科工作台",
      "primary_domain": "COLLABORATION",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [
        "FR-126"
      ],
      "ac_refs": [
        "AC-126"
      ],
      "scr_refs": [
        "SCR-104"
      ],
      "requirement_refs": [
        "FR-126",
        "AC-126",
        "SCR-104"
      ],
      "api_refs": [
        "Platform API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "timeout",
        "duplicate",
        "verification",
        "hard-block",
        "success",
        "compensation"
      ],
      "guards": [
        "SESSION",
        "ROLE"
      ],
      "layout": "诊疗执行",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/pathology-workbench.png"
    },
    {
      "route_id": "pathway-graph",
      "path": "#/pathway-graph",
      "screen_id": "HASH-100",
      "title": "知识图谱",
      "primary_domain": "CONFIG",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "Platform API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "error",
        "permission",
        "offline",
        "conflict",
        "blocked",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "ROLE"
      ],
      "layout": "通用",
      "source_status": "PLANNED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/"
    },
    {
      "route_id": "pathway-review",
      "path": "#/pathway-review",
      "screen_id": "HASH-101",
      "title": "审核队列",
      "primary_domain": "CONFIG",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "Platform API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "error",
        "permission",
        "offline",
        "conflict",
        "blocked",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "ROLE"
      ],
      "layout": "通用",
      "source_status": "PLANNED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/"
    },
    {
      "route_id": "pathway-versions",
      "path": "#/pathway-versions",
      "screen_id": "HASH-102",
      "title": "版本历史",
      "primary_domain": "CONFIG",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "Platform API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "error",
        "permission",
        "offline",
        "conflict",
        "blocked",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "ROLE"
      ],
      "layout": "通用",
      "source_status": "PLANNED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/"
    },
    {
      "route_id": "patient-merge",
      "path": "#/patient-merge",
      "screen_id": "HASH-005",
      "title": "重复患者合并与撤销",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [
        "FR-005"
      ],
      "ac_refs": [
        "AC-005"
      ],
      "scr_refs": [
        "SCR-004"
      ],
      "requirement_refs": [
        "FR-005",
        "AC-005",
        "SCR-004"
      ],
      "api_refs": [
        "Platform API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "offline",
        "permission",
        "session-expired",
        "patient-switch",
        "conflict",
        "blocked",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "ROLE"
      ],
      "layout": "临床工作域",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/patient-merge.png"
    },
    {
      "route_id": "patient-registry",
      "path": "#/patient-registry",
      "screen_id": "HASH-004",
      "title": "患者检索、匹配与登记",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [
        "FR-004",
        "FR-006"
      ],
      "ac_refs": [
        "AC-004",
        "AC-006"
      ],
      "scr_refs": [
        "SCR-003"
      ],
      "requirement_refs": [
        "FR-004",
        "AC-004",
        "SCR-003",
        "FR-006",
        "AC-006"
      ],
      "api_refs": [
        "Platform API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "offline",
        "permission",
        "session-expired",
        "patient-switch",
        "conflict",
        "blocked",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "ROLE"
      ],
      "layout": "临床工作域",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/patient-registry.png"
    },
    {
      "route_id": "patient-timeline",
      "path": "#/patient-timeline",
      "screen_id": "HASH-006",
      "title": "患者临床时间线",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [
        "FR-007",
        "FR-037"
      ],
      "ac_refs": [
        "AC-007",
        "AC-037"
      ],
      "scr_refs": [
        "SCR-005"
      ],
      "requirement_refs": [
        "FR-007",
        "AC-007",
        "SCR-005",
        "FR-037",
        "AC-037"
      ],
      "api_refs": [
        "Platform API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "offline",
        "permission",
        "session-expired",
        "patient-switch",
        "conflict",
        "blocked",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "ROLE"
      ],
      "layout": "临床工作域",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/patient-timeline.png"
    },
    {
      "route_id": "pediatrics-care",
      "path": "#/pediatrics-care",
      "screen_id": "HASH-147",
      "title": "儿科关键诊疗流程",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_SPECIALTY_CLINICAL_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "SpecialtySupport",
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "device-offline",
        "identity-or-site-conflict",
        "hard-block",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_CONTEXT",
        "DEPARTMENT_SUPPORT"
      ],
      "layout": "核心专科",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/pediatrics-care.png"
    },
    {
      "route_id": "pediatrics-evidence",
      "path": "#/pediatrics-evidence",
      "screen_id": "HASH-145",
      "title": "儿科检查、设备与来源证据",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_SPECIALTY_CLINICAL_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "SpecialtySupport",
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "device-offline",
        "identity-or-site-conflict",
        "hard-block",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_CONTEXT",
        "DEPARTMENT_SUPPORT"
      ],
      "layout": "核心专科",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/pediatrics-evidence.png"
    },
    {
      "route_id": "pediatrics-followup",
      "path": "#/pediatrics-followup",
      "screen_id": "HASH-148",
      "title": "儿科随访、转诊与交接",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_SPECIALTY_CLINICAL_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "SpecialtySupport",
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "device-offline",
        "identity-or-site-conflict",
        "hard-block",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_CONTEXT",
        "DEPARTMENT_SUPPORT"
      ],
      "layout": "核心专科",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/pediatrics-followup.png"
    },
    {
      "route_id": "pediatrics-qc",
      "path": "#/pediatrics-qc",
      "screen_id": "HASH-149",
      "title": "儿科质控与安全",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_SPECIALTY_CLINICAL_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "SpecialtySupport",
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "device-offline",
        "identity-or-site-conflict",
        "hard-block",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_CONTEXT",
        "DEPARTMENT_SUPPORT"
      ],
      "layout": "核心专科",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/pediatrics-qc.png"
    },
    {
      "route_id": "pediatrics-record",
      "path": "#/pediatrics-record",
      "screen_id": "HASH-144",
      "title": "儿科专科病历",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_SPECIALTY_CLINICAL_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "SpecialtySupport",
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "device-offline",
        "identity-or-site-conflict",
        "hard-block",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_CONTEXT",
        "DEPARTMENT_SUPPORT"
      ],
      "layout": "核心专科",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/pediatrics-record.png"
    },
    {
      "route_id": "pediatrics-treatment",
      "path": "#/pediatrics-treatment",
      "screen_id": "HASH-146",
      "title": "儿科诊疗执行中心",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_SPECIALTY_CLINICAL_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "SpecialtySupport",
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "device-offline",
        "identity-or-site-conflict",
        "hard-block",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_CONTEXT",
        "DEPARTMENT_SUPPORT"
      ],
      "layout": "核心专科",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/pediatrics-treatment.png"
    },
    {
      "route_id": "pediatrics-workbench",
      "path": "#/pediatrics-workbench",
      "screen_id": "HASH-143",
      "title": "儿科工作台",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_SPECIALTY_CLINICAL_ROLE"
      ],
      "fr_refs": [
        "FR-131"
      ],
      "ac_refs": [
        "AC-131"
      ],
      "scr_refs": [
        "SCR-114–117/SCR-152–154"
      ],
      "requirement_refs": [
        "FR-131",
        "AC-131",
        "SCR-114–117/SCR-152–154"
      ],
      "api_refs": [
        "SpecialtySupport",
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "device-offline",
        "identity-or-site-conflict",
        "hard-block",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_CONTEXT",
        "DEPARTMENT_SUPPORT"
      ],
      "layout": "核心专科",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/pediatrics-workbench.png"
    },
    {
      "route_id": "quality-center",
      "path": "#/quality-center",
      "screen_id": "HASH-064",
      "title": "医疗质量中心",
      "primary_domain": "QUALITY",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "Platform API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "permission",
        "timeout",
        "conflict",
        "approval",
        "publishing",
        "failed",
        "rollback",
        "success"
      ],
      "guards": [
        "SESSION",
        "ROLE"
      ],
      "layout": "治理与管理",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/quality-center.png"
    },
    {
      "route_id": "quality-rating",
      "path": "#/quality-rating",
      "screen_id": "HASH-066",
      "title": "医疗质量与电子病历评级看板",
      "primary_domain": "QUALITY",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [
        "FR-055"
      ],
      "ac_refs": [
        "AC-055"
      ],
      "scr_refs": [
        "SCR-034"
      ],
      "requirement_refs": [
        "FR-055",
        "AC-055",
        "SCR-034"
      ],
      "api_refs": [
        "Platform API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "permission",
        "timeout",
        "conflict",
        "approval",
        "publishing",
        "failed",
        "rollback",
        "success"
      ],
      "guards": [
        "SESSION",
        "ROLE"
      ],
      "layout": "治理与管理",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/quality-rating.png"
    },
    {
      "route_id": "record",
      "path": "#/record",
      "screen_id": "HASH-035",
      "title": "全院病历中心",
      "primary_domain": "RECORD",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [
        "FR-098"
      ],
      "ac_refs": [
        "AC-098"
      ],
      "scr_refs": [
        "SCR-074"
      ],
      "requirement_refs": [
        "FR-098",
        "AC-098",
        "SCR-074"
      ],
      "api_refs": [
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "autosaving",
        "saved",
        "offline",
        "permission",
        "concurrent-conflict",
        "rule-blocked",
        "signed",
        "corrected",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_OR_PURPOSE"
      ],
      "layout": "病历与病案",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/record.png"
    },
    {
      "route_id": "record-diff",
      "path": "#/record-diff",
      "screen_id": "HASH-041",
      "title": "病历版本差异",
      "primary_domain": "RECORD",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "autosaving",
        "saved",
        "offline",
        "permission",
        "concurrent-conflict",
        "rule-blocked",
        "signed",
        "corrected",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_OR_PURPOSE"
      ],
      "layout": "病历与病案",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/record-diff.png"
    },
    {
      "route_id": "record-editor",
      "path": "#/record-editor",
      "screen_id": "HASH-036",
      "title": "门诊病历 · 专注编辑",
      "primary_domain": "RECORD",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [
        "FR-009",
        "FR-032",
        "FR-060",
        "FR-099"
      ],
      "ac_refs": [
        "AC-009",
        "AC-032",
        "AC-060",
        "AC-099"
      ],
      "scr_refs": [
        "SCR-013",
        "SCR-075"
      ],
      "requirement_refs": [
        "FR-009",
        "AC-009",
        "SCR-013",
        "FR-032",
        "AC-032",
        "FR-060",
        "AC-060",
        "FR-099",
        "AC-099",
        "SCR-075"
      ],
      "api_refs": [
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "autosaving",
        "saved",
        "offline",
        "permission",
        "concurrent-conflict",
        "rule-blocked",
        "signed",
        "corrected",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_OR_PURPOSE"
      ],
      "layout": "病历与病案",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/record-editor.png"
    },
    {
      "route_id": "record-qc",
      "path": "#/record-qc",
      "screen_id": "HASH-038",
      "title": "病历质控与审签中心",
      "primary_domain": "RECORD",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "autosaving",
        "saved",
        "offline",
        "permission",
        "concurrent-conflict",
        "rule-blocked",
        "signed",
        "corrected",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_OR_PURPOSE"
      ],
      "layout": "病历与病案",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/record-qc.png"
    },
    {
      "route_id": "record-sign",
      "path": "#/record-sign",
      "screen_id": "HASH-039",
      "title": "病历签署确认",
      "primary_domain": "RECORD",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [
        "FR-010",
        "FR-053"
      ],
      "ac_refs": [
        "AC-010",
        "AC-053"
      ],
      "scr_refs": [
        "SCR-014"
      ],
      "requirement_refs": [
        "FR-010",
        "AC-010",
        "SCR-014",
        "FR-053",
        "AC-053"
      ],
      "api_refs": [
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "autosaving",
        "saved",
        "offline",
        "permission",
        "concurrent-conflict",
        "rule-blocked",
        "signed",
        "corrected",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_OR_PURPOSE"
      ],
      "layout": "病历与病案",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/record-sign.png"
    },
    {
      "route_id": "record-sources",
      "path": "#/record-sources",
      "screen_id": "HASH-037",
      "title": "病历来源与证据中心",
      "primary_domain": "RECORD",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [
        "FR-100"
      ],
      "ac_refs": [
        "AC-100"
      ],
      "scr_refs": [
        "SCR-076/077"
      ],
      "requirement_refs": [
        "FR-100",
        "AC-100",
        "SCR-076/077"
      ],
      "api_refs": [
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "autosaving",
        "saved",
        "offline",
        "permission",
        "concurrent-conflict",
        "rule-blocked",
        "signed",
        "corrected",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_OR_PURPOSE"
      ],
      "layout": "病历与病案",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/record-sources.png"
    },
    {
      "route_id": "record-versions",
      "path": "#/record-versions",
      "screen_id": "HASH-040",
      "title": "病历版本与法律证据",
      "primary_domain": "RECORD",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [
        "FR-011",
        "FR-101"
      ],
      "ac_refs": [
        "AC-011",
        "AC-101"
      ],
      "scr_refs": [
        "SCR-014",
        "SCR-078"
      ],
      "requirement_refs": [
        "FR-011",
        "AC-011",
        "SCR-014",
        "FR-101",
        "AC-101",
        "SCR-078"
      ],
      "api_refs": [
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "autosaving",
        "saved",
        "offline",
        "permission",
        "concurrent-conflict",
        "rule-blocked",
        "signed",
        "corrected",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_OR_PURPOSE"
      ],
      "layout": "病历与病案",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/record-versions.png"
    },
    {
      "route_id": "release-gates",
      "path": "#/release-gates",
      "screen_id": "HASH-114",
      "title": "Release 门禁与制品发布",
      "primary_domain": "CONFIG",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [
        "FR-028"
      ],
      "ac_refs": [
        "AC-028"
      ],
      "scr_refs": [
        "SCR-063"
      ],
      "requirement_refs": [
        "FR-028",
        "AC-028",
        "SCR-063"
      ],
      "api_refs": [
        "Platform API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "permission",
        "timeout",
        "conflict",
        "approval",
        "publishing",
        "failed",
        "rollback",
        "success"
      ],
      "guards": [
        "SESSION",
        "ROLE"
      ],
      "layout": "治理与管理",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/release-gates.png"
    },
    {
      "route_id": "reproductive-care",
      "path": "#/reproductive-care",
      "screen_id": "HASH-140",
      "title": "生殖医学关键诊疗流程",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_SPECIALTY_CLINICAL_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "SpecialtySupport",
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "device-offline",
        "identity-or-site-conflict",
        "hard-block",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_CONTEXT",
        "DEPARTMENT_SUPPORT"
      ],
      "layout": "核心专科",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/reproductive-care.png"
    },
    {
      "route_id": "reproductive-evidence",
      "path": "#/reproductive-evidence",
      "screen_id": "HASH-138",
      "title": "生殖医学检查、设备与来源证据",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_SPECIALTY_CLINICAL_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "SpecialtySupport",
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "device-offline",
        "identity-or-site-conflict",
        "hard-block",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_CONTEXT",
        "DEPARTMENT_SUPPORT"
      ],
      "layout": "核心专科",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/reproductive-evidence.png"
    },
    {
      "route_id": "reproductive-followup",
      "path": "#/reproductive-followup",
      "screen_id": "HASH-141",
      "title": "生殖医学随访、转诊与交接",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_SPECIALTY_CLINICAL_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "SpecialtySupport",
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "device-offline",
        "identity-or-site-conflict",
        "hard-block",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_CONTEXT",
        "DEPARTMENT_SUPPORT"
      ],
      "layout": "核心专科",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/reproductive-followup.png"
    },
    {
      "route_id": "reproductive-qc",
      "path": "#/reproductive-qc",
      "screen_id": "HASH-142",
      "title": "生殖医学质控与安全",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_SPECIALTY_CLINICAL_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "SpecialtySupport",
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "device-offline",
        "identity-or-site-conflict",
        "hard-block",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_CONTEXT",
        "DEPARTMENT_SUPPORT"
      ],
      "layout": "核心专科",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/reproductive-qc.png"
    },
    {
      "route_id": "reproductive-record",
      "path": "#/reproductive-record",
      "screen_id": "HASH-137",
      "title": "生殖医学专科病历",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_SPECIALTY_CLINICAL_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "SpecialtySupport",
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "device-offline",
        "identity-or-site-conflict",
        "hard-block",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_CONTEXT",
        "DEPARTMENT_SUPPORT"
      ],
      "layout": "核心专科",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/reproductive-record.png"
    },
    {
      "route_id": "reproductive-treatment",
      "path": "#/reproductive-treatment",
      "screen_id": "HASH-139",
      "title": "生殖医学诊疗执行中心",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_SPECIALTY_CLINICAL_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "SpecialtySupport",
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "device-offline",
        "identity-or-site-conflict",
        "hard-block",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_CONTEXT",
        "DEPARTMENT_SUPPORT"
      ],
      "layout": "核心专科",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/reproductive-treatment.png"
    },
    {
      "route_id": "reproductive-workbench",
      "path": "#/reproductive-workbench",
      "screen_id": "HASH-136",
      "title": "生殖医学工作台",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_SPECIALTY_CLINICAL_ROLE"
      ],
      "fr_refs": [
        "FR-130"
      ],
      "ac_refs": [
        "AC-130"
      ],
      "scr_refs": [
        "SCR-110–113/SCR-149–151"
      ],
      "requirement_refs": [
        "FR-130",
        "AC-130",
        "SCR-110–113/SCR-149–151"
      ],
      "api_refs": [
        "SpecialtySupport",
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "device-offline",
        "identity-or-site-conflict",
        "hard-block",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_CONTEXT",
        "DEPARTMENT_SUPPORT"
      ],
      "layout": "核心专科",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/reproductive-workbench.png"
    },
    {
      "route_id": "research",
      "path": "#/research",
      "screen_id": "HASH-077",
      "title": "科研项目与统计中心",
      "primary_domain": "DATA",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "Platform API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "permission",
        "timeout",
        "conflict",
        "approval",
        "publishing",
        "failed",
        "rollback",
        "success"
      ],
      "guards": [
        "SESSION",
        "ROLE"
      ],
      "layout": "治理与管理",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/research.png"
    },
    {
      "route_id": "research-dataset",
      "path": "#/research-dataset",
      "screen_id": "HASH-080",
      "title": "科研数据申请与受控交付",
      "primary_domain": "DATA",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [
        "FR-107"
      ],
      "ac_refs": [
        "AC-107"
      ],
      "scr_refs": [
        "SCR-090"
      ],
      "requirement_refs": [
        "FR-107",
        "AC-107",
        "SCR-090"
      ],
      "api_refs": [
        "Platform API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "permission",
        "timeout",
        "conflict",
        "approval",
        "publishing",
        "failed",
        "rollback",
        "success"
      ],
      "guards": [
        "SESSION",
        "ROLE"
      ],
      "layout": "治理与管理",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/research-dataset.png"
    },
    {
      "route_id": "research-stats",
      "path": "#/research-stats",
      "screen_id": "HASH-079",
      "title": "科研统计分析",
      "primary_domain": "DATA",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "Platform API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "permission",
        "timeout",
        "conflict",
        "approval",
        "publishing",
        "failed",
        "rollback",
        "success"
      ],
      "guards": [
        "SESSION",
        "ROLE"
      ],
      "layout": "治理与管理",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/research-stats.png"
    },
    {
      "route_id": "rule-center",
      "path": "#/rule-center",
      "screen_id": "HASH-107",
      "title": "规则、时限与提示中心",
      "primary_domain": "CONFIG",
      "roles": [
        "ADMIN_OR_AUTHORIZED_GOVERNANCE_ROLE"
      ],
      "fr_refs": [
        "FR-056",
        "FR-065"
      ],
      "ac_refs": [
        "AC-056",
        "AC-065"
      ],
      "scr_refs": [
        "SCR-043"
      ],
      "requirement_refs": [
        "FR-056",
        "AC-056",
        "SCR-043",
        "FR-065",
        "AC-065"
      ],
      "api_refs": [
        "Admin/Config API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "permission",
        "timeout",
        "conflict",
        "approval",
        "publishing",
        "failed",
        "rollback",
        "success"
      ],
      "guards": [
        "SESSION",
        "ROLE",
        "SCOPE",
        "SEPARATION_OF_DUTIES"
      ],
      "layout": "治理与管理",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/rule-center.png"
    },
    {
      "route_id": "scope-designer",
      "path": "#/scope-designer",
      "screen_id": "HASH-108",
      "title": "角色、职责与数据范围设计",
      "primary_domain": "CONFIG",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [
        "FR-066"
      ],
      "ac_refs": [
        "AC-066"
      ],
      "scr_refs": [
        "SCR-044"
      ],
      "requirement_refs": [
        "FR-066",
        "AC-066",
        "SCR-044"
      ],
      "api_refs": [
        "Platform API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "permission",
        "timeout",
        "conflict",
        "approval",
        "publishing",
        "failed",
        "rollback",
        "success"
      ],
      "guards": [
        "SESSION",
        "ROLE"
      ],
      "layout": "治理与管理",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/scope-designer.png"
    },
    {
      "route_id": "skill-catalog",
      "path": "#/skill-catalog",
      "screen_id": "HASH-095",
      "title": "Skill 目录与定义编辑器",
      "primary_domain": "AI",
      "roles": [
        "AUTHORIZED_CLINICAL_OR_AI_GOVERNANCE_ROLE"
      ],
      "fr_refs": [
        "FR-078"
      ],
      "ac_refs": [
        "AC-078"
      ],
      "scr_refs": [
        "SCR-056"
      ],
      "requirement_refs": [
        "FR-078",
        "AC-078",
        "SCR-056"
      ],
      "api_refs": [
        "ContextLease",
        "AIRun",
        "AIProposal"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "permission",
        "timeout",
        "conflict",
        "approval",
        "publishing",
        "failed",
        "rollback",
        "success"
      ],
      "guards": [
        "SESSION",
        "PURPOSE",
        "MODEL_POLICY",
        "AI_USE_CASE"
      ],
      "layout": "治理与管理",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/skill-catalog.png"
    },
    {
      "route_id": "specialty-center",
      "path": "#/specialty-center",
      "screen_id": "HASH-128",
      "title": "核心专科工作台",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "Platform API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "device-offline",
        "identity-or-site-conflict",
        "hard-block",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "ROLE"
      ],
      "layout": "核心专科",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/specialty-center.png"
    },
    {
      "route_id": "specialty-coverage",
      "path": "#/specialty-coverage",
      "screen_id": "HASH-105",
      "title": "科室适配与上线门禁",
      "primary_domain": "CONFIG",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [
        "FR-093",
        "FR-125"
      ],
      "ac_refs": [
        "AC-093",
        "AC-125"
      ],
      "scr_refs": [
        "SCR-073",
        "SCR-103"
      ],
      "requirement_refs": [
        "FR-093",
        "AC-093",
        "SCR-073",
        "FR-125",
        "AC-125",
        "SCR-103"
      ],
      "api_refs": [
        "Platform API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "permission",
        "timeout",
        "conflict",
        "approval",
        "publishing",
        "failed",
        "rollback",
        "success"
      ],
      "guards": [
        "SESSION",
        "ROLE"
      ],
      "layout": "治理与管理",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/specialty-coverage.png"
    },
    {
      "route_id": "surgery-schedule",
      "path": "#/surgery-schedule",
      "screen_id": "HASH-059",
      "title": "围手术期排程与安全核查",
      "primary_domain": "COLLABORATION",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [
        "FR-046"
      ],
      "ac_refs": [
        "AC-046"
      ],
      "scr_refs": [
        "SCR-028"
      ],
      "requirement_refs": [
        "FR-046",
        "AC-046",
        "SCR-028"
      ],
      "api_refs": [
        "Platform API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "timeout",
        "duplicate",
        "verification",
        "hard-block",
        "success",
        "compensation"
      ],
      "guards": [
        "SESSION",
        "ROLE"
      ],
      "layout": "诊疗执行",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/surgery-schedule.png"
    },
    {
      "route_id": "tcm-care",
      "path": "#/tcm-care",
      "screen_id": "HASH-196",
      "title": "中医科关键诊疗流程",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_SPECIALTY_CLINICAL_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "SpecialtySupport",
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "device-offline",
        "identity-or-site-conflict",
        "hard-block",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_CONTEXT",
        "DEPARTMENT_SUPPORT"
      ],
      "layout": "核心专科",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/tcm-care.png"
    },
    {
      "route_id": "tcm-evidence",
      "path": "#/tcm-evidence",
      "screen_id": "HASH-194",
      "title": "中医科检查、设备与来源证据",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_SPECIALTY_CLINICAL_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "SpecialtySupport",
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "device-offline",
        "identity-or-site-conflict",
        "hard-block",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_CONTEXT",
        "DEPARTMENT_SUPPORT"
      ],
      "layout": "核心专科",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/tcm-evidence.png"
    },
    {
      "route_id": "tcm-followup",
      "path": "#/tcm-followup",
      "screen_id": "HASH-197",
      "title": "中医科随访、转诊与交接",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_SPECIALTY_CLINICAL_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "SpecialtySupport",
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "device-offline",
        "identity-or-site-conflict",
        "hard-block",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_CONTEXT",
        "DEPARTMENT_SUPPORT"
      ],
      "layout": "核心专科",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/tcm-followup.png"
    },
    {
      "route_id": "tcm-qc",
      "path": "#/tcm-qc",
      "screen_id": "HASH-198",
      "title": "中医科质控与安全",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_SPECIALTY_CLINICAL_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "SpecialtySupport",
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "device-offline",
        "identity-or-site-conflict",
        "hard-block",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_CONTEXT",
        "DEPARTMENT_SUPPORT"
      ],
      "layout": "核心专科",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/tcm-qc.png"
    },
    {
      "route_id": "tcm-record",
      "path": "#/tcm-record",
      "screen_id": "HASH-193",
      "title": "中医科专科病历",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_SPECIALTY_CLINICAL_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "SpecialtySupport",
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "device-offline",
        "identity-or-site-conflict",
        "hard-block",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_CONTEXT",
        "DEPARTMENT_SUPPORT"
      ],
      "layout": "核心专科",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/tcm-record.png"
    },
    {
      "route_id": "tcm-treatment",
      "path": "#/tcm-treatment",
      "screen_id": "HASH-195",
      "title": "中医科诊疗执行中心",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_SPECIALTY_CLINICAL_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "SpecialtySupport",
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "device-offline",
        "identity-or-site-conflict",
        "hard-block",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_CONTEXT",
        "DEPARTMENT_SUPPORT"
      ],
      "layout": "核心专科",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/tcm-treatment.png"
    },
    {
      "route_id": "tcm-workbench",
      "path": "#/tcm-workbench",
      "screen_id": "HASH-192",
      "title": "中医科工作台",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_SPECIALTY_CLINICAL_ROLE"
      ],
      "fr_refs": [
        "FR-138"
      ],
      "ac_refs": [
        "AC-138"
      ],
      "scr_refs": [
        "SCR-142–145/SCR-173–175"
      ],
      "requirement_refs": [
        "FR-138",
        "AC-138",
        "SCR-142–145/SCR-173–175"
      ],
      "api_refs": [
        "SpecialtySupport",
        "Clinical Core API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "device-offline",
        "identity-or-site-conflict",
        "hard-block",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "PATIENT_CONTEXT",
        "ENCOUNTER_CONTEXT",
        "DEPARTMENT_SUPPORT"
      ],
      "layout": "核心专科",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/tcm-workbench.png"
    },
    {
      "route_id": "therapy-workbench",
      "path": "#/therapy-workbench",
      "screen_id": "HASH-058",
      "title": "一般治疗工作台",
      "primary_domain": "COLLABORATION",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [
        "FR-045"
      ],
      "ac_refs": [
        "AC-045"
      ],
      "scr_refs": [
        "SCR-027"
      ],
      "requirement_refs": [
        "FR-045",
        "AC-045",
        "SCR-027"
      ],
      "api_refs": [
        "Platform API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "timeout",
        "duplicate",
        "verification",
        "hard-block",
        "success",
        "compensation"
      ],
      "guards": [
        "SESSION",
        "ROLE"
      ],
      "layout": "诊疗执行",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/therapy-workbench.png"
    },
    {
      "route_id": "tool-catalog",
      "path": "#/tool-catalog",
      "screen_id": "HASH-094",
      "title": "Tool 目录、风险与审批策略",
      "primary_domain": "AI",
      "roles": [
        "AUTHORIZED_CLINICAL_OR_AI_GOVERNANCE_ROLE"
      ],
      "fr_refs": [
        "FR-077"
      ],
      "ac_refs": [
        "AC-077"
      ],
      "scr_refs": [
        "SCR-055"
      ],
      "requirement_refs": [
        "FR-077",
        "AC-077",
        "SCR-055"
      ],
      "api_refs": [
        "ContextLease",
        "AIRun",
        "AIProposal"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "permission",
        "timeout",
        "conflict",
        "approval",
        "publishing",
        "failed",
        "rollback",
        "success"
      ],
      "guards": [
        "SESSION",
        "PURPOSE",
        "MODEL_POLICY",
        "AI_USE_CASE"
      ],
      "layout": "治理与管理",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/tool-catalog.png"
    },
    {
      "route_id": "transfusion",
      "path": "#/transfusion",
      "screen_id": "HASH-062",
      "title": "输血全链工作台",
      "primary_domain": "COLLABORATION",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [
        "FR-049"
      ],
      "ac_refs": [
        "AC-049"
      ],
      "scr_refs": [
        "SCR-031"
      ],
      "requirement_refs": [
        "FR-049",
        "AC-049",
        "SCR-031"
      ],
      "api_refs": [
        "Platform API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "permission",
        "offline",
        "timeout",
        "duplicate",
        "verification",
        "hard-block",
        "success",
        "compensation"
      ],
      "guards": [
        "SESSION",
        "ROLE"
      ],
      "layout": "诊疗执行",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/transfusion.png"
    },
    {
      "route_id": "unified-home",
      "path": "#/unified-home",
      "screen_id": "HASH-003",
      "title": "统一首页与任务中心",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [],
      "ac_refs": [],
      "scr_refs": [],
      "requirement_refs": [
        "Prototype SPEC v0.13"
      ],
      "api_refs": [
        "Platform API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "offline",
        "permission",
        "session-expired",
        "patient-switch",
        "conflict",
        "blocked",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "ROLE"
      ],
      "layout": "临床工作域",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/unified-home.png"
    },
    {
      "route_id": "ward",
      "path": "#/ward",
      "screen_id": "HASH-034",
      "title": "心内科一病区 · 护理工作台",
      "primary_domain": "CLINICAL",
      "roles": [
        "AUTHORIZED_ROLE"
      ],
      "fr_refs": [
        "FR-014",
        "FR-050"
      ],
      "ac_refs": [
        "AC-014",
        "AC-050"
      ],
      "scr_refs": [
        "SCR-020"
      ],
      "requirement_refs": [
        "FR-014",
        "AC-014",
        "SCR-020",
        "FR-050",
        "AC-050"
      ],
      "api_refs": [
        "Platform API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "offline",
        "permission",
        "session-expired",
        "patient-switch",
        "conflict",
        "blocked",
        "success",
        "recovery"
      ],
      "guards": [
        "SESSION",
        "ROLE"
      ],
      "layout": "临床工作域",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/ward.png"
    },
    {
      "route_id": "workflow",
      "path": "#/workflow",
      "screen_id": "HASH-103",
      "title": "流程与状态设计器",
      "primary_domain": "CONFIG",
      "roles": [
        "ADMIN_OR_AUTHORIZED_GOVERNANCE_ROLE"
      ],
      "fr_refs": [
        "FR-063"
      ],
      "ac_refs": [
        "AC-063"
      ],
      "scr_refs": [
        "SCR-041"
      ],
      "requirement_refs": [
        "FR-063",
        "AC-063",
        "SCR-041"
      ],
      "api_refs": [
        "Admin/Config API"
      ],
      "states": [
        "default",
        "loading",
        "empty",
        "partial",
        "stale",
        "permission",
        "timeout",
        "conflict",
        "approval",
        "publishing",
        "failed",
        "rollback",
        "success"
      ],
      "guards": [
        "SESSION",
        "ROLE",
        "SCOPE",
        "SEPARATION_OF_DUTIES"
      ],
      "layout": "治理与管理",
      "source_status": "VERIFIED",
      "policy_status": "INFERRED_BY_ROUTE_POLICY_V1",
      "artifact_path": "docs/design/ui-delivery/screens/workflow.png"
    }
  ]
} as const;
