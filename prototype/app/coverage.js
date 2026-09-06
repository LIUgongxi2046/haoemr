const coveragePages=[
  {id:'clinical',label:'临床业务门户',icon:'⌂',group:'临床工作域'},
  {id:'outpatient',label:'门诊工作台',icon:'◫',group:'临床工作域',count:'6'},
  {id:'emergency',label:'急诊工作台',icon:'✚',group:'临床工作域',count:'6'},
  {id:'inpatient',label:'住院工作站',icon:'▥',group:'临床工作域',count:'5'},
  {id:'record',label:'病历中心',icon:'▤',group:'病历与质量',count:'3'},
  {id:'quality-center',label:'医疗质量中心',icon:'◈',group:'病历与质量',count:'7'},
  {id:'archive-assets',label:'病案资产中心',icon:'▣',group:'病历与质量',count:'3'},
  {id:'care-operations',label:'诊疗执行中心',icon:'✚',group:'业务协同',count:'12'},
  {id:'clinical-tasks',label:'任务与临床路径',icon:'☑',group:'业务协同',count:'9'},
  {id:'data-center',label:'数据中心',icon:'⌁',group:'平台中心',count:'6'},
  {id:'ai-center',label:'AI 中心',icon:'✦',group:'平台中心',count:'4'},
  {id:'workflow',label:'业务配置',icon:'⌘',group:'管理与配置'},
  {id:'admin',label:'系统管理',icon:'⚙',group:'管理与配置',count:'7'}
];
pages.splice(0,pages.length,...coveragePages);

const clinicalFoundationRoutes=['clinical','login-context','unified-home','patient-registry','patient-merge','patient-timeline','emergency-access','appointment-registration','admission-bed'];
const careOperationRoutes=['care-operations','billing','outpatient-pharmacy','inpatient-pharmacy','lab-workbench','pathology-workbench','imaging-workbench','therapy-workbench','surgery-schedule','anesthesia-workbench','device-monitoring','transfusion'];
const qualityCenterRoutes=['quality-center','department-qc','quality-rating','infection-events','credentials'];
const dataCenterRoutes=['data-center','integration','integration-connectors','integration-mapping','integration-messages','migration','data-quality','devices','research','cohort-builder','research-stats','research-dataset'];
const aiPlatformRoutes=['ai-center','ai-assistant','ai-reminder-detail','ai-capture','ai-action-review','ai-assistant-policy','models','model-connection','model-routing','model-evaluation','agent-catalog','agent','agent-context','tool-catalog','skill-catalog','agent-compose','agent-evals','aiops'];
const configurationRoutes=['workflow','capability-pack','specialty-coverage','form-designer','rule-center','scope-designer','config-release','config-upgrade'];
const operationRoutes=['install','backup','operations','release-gates','opensource'];

const sectionNav=(title,active,items)=>`<div class="center-nav"><b>${title}</b>${items.map(x=>`<button class="${active===x[0]?'active':''}" data-jump="${x[0]}">${x[1]}</button>`).join('')}</div>`;
const clinicalFoundationNav=active=>sectionNav('临床通用',['clinical'].includes(active)?'clinical':active,[['clinical','业务门户'],['unified-home','统一首页'],['patient-registry','患者登记'],['patient-timeline','患者时间线'],['appointment-registration','预约挂号'],['admission-bed','入院床位'],['emergency-access','紧急访问']]);
const careOperationNav=active=>sectionNav('诊疗执行',active,[['care-operations','执行总览'],['billing','费用结算'],['outpatient-pharmacy','门诊药房'],['inpatient-pharmacy','住院药房'],['lab-workbench','检验'],['pathology-workbench','病理'],['imaging-workbench','检查影像'],['therapy-workbench','治疗'],['surgery-schedule','手术'],['anesthesia-workbench','麻醉'],['transfusion','输血'],['device-monitoring','设备监护']]);
const qualityCenterNav=active=>sectionNav('医疗质量中心',active,[['quality-center','质量总览'],['department-qc','院科质控'],['quality-rating','评级取证'],['infection-events','院感事件'],['credentials','临床资质']]);
const dataCenterNav=active=>sectionNav('数据中心',active,[['data-center','数据总览'],['integration','集成交换'],['migration','历史迁移'],['data-quality','数据质量'],['devices','设备接入'],['research','科研统计']]);
const aiCenterNav=active=>sectionNav('AI 中心',active,[['ai-center','AI 总览'],['ai-assistant','AI医助小南'],['ai-assistant-policy','小南策略'],['models','模型路由'],['agent-catalog','Agent 设计'],['skill-catalog','Skill'],['tool-catalog','Tool'],['agent-evals','评估发布'],['aiops','运行治理']]);
const configCenterNav=active=>sectionNav('业务配置',active,[['workflow','流程设计'],['capability-pack','能力包'],['specialty-coverage','科室适配'],['form-designer','表单模板'],['rule-center','规则时限'],['scope-designer','职责范围'],['config-release','验证发布'],['config-upgrade','升级冲突']]);

const domainRows={
  patient:[['患者候选','陈建国 · 68岁 · 10028451','高相似候选 2','待确认'],['身份来源','身份证/医保卡/院内号','3 个标识一致','通过'],['当前就诊','心内科门诊 OP20260813-0842','诊疗中','有效']],
  pharmacy:[['氨氯地平片 5mg','口服 qd · 30片','库存 1,286','待审方'],['阿托伐他汀钙片 20mg','口服 qn · 30片','库存 842','相互作用已核验'],['青霉素类处方','患者严重过敏','禁止调剂','硬阻断']],
  medical:[['陈建国 · OP0842','血钾/肌钙蛋白','09:18','报告待审核'],['李桂兰 · 02床','胸部 CT','09:05','图像已到/报告中'],['王秀兰 · ER0121','床旁血气','08:56','危急值待确认']],
  safety:[['高风险待办','7','较昨日 +2','需处理'],['规则覆盖率','98.6%','3 个来源延迟','降级'],['本月闭环率','94.2%','目标 ≥96%','观察']],
  ai:[['门诊病历助手','Agent v1.6','MedBase-L 2.1','已批准'],['结果趋势摘要','Agent v1.2','MedBase-S 1.8','观察'],['病历质控 Skill','Skill v2.3','硬规则 + 模型','健康']],
  ops:[['生产核心服务','23/23','P95 186ms','健康'],['灾备复制','延迟 3.2s','RPO 5min','健康'],['待升级组件','4','2 个安全更新','待评审']]
};

function coveragePage(cfg){
  const rows=domainRows[cfg.rows||'ops'];
  return pageHead(cfg.title,cfg.sub,`<button class="btn">${cfg.secondary||'导出清单'}</button><button class="btn primary">${cfg.primary||'创建处理任务'}</button>`)+
  `<div class="metric-grid compact-metrics">${(cfg.metrics||[['待处理','12'],['今日完成','86'],['风险阻断','3'],['数据完整','99.8%']]).map((x,i)=>`<div class="metric"><div class="name">${x[0]}</div><div class="value ${i===2?'danger-text':''}">${x[1]}</div><div class="trend">${x[2]||'可下钻到业务对象'}</div></div>`).join('')}</div>
  <div class="grid coverage-layout"><section class="card"><div class="card-head">${cfg.listTitle||'当前工作队列'} <span class="sub">合成业务数据</span></div><table class="table"><thead><tr><th>对象</th><th>关键资料</th><th>进度/来源</th><th>状态</th></tr></thead><tbody>${rows.map((x,i)=>`<tr><td><b>${x[0]}</b></td><td>${x[1]}</td><td>${x[2]}</td><td><span class="status ${i===2?'red':i===0?'amber':'green'}">${x[3]}</span></td></tr>`).join('')}</tbody></table><div class="coverage-flow">${(cfg.flow||['选择业务对象','核验上下文','执行处理','复核结果','完成或恢复']).map((x,i)=>`<span><i>${i+1}</i>${x}</span>`).join('<b>›</b>')}</div></section>
  <aside class="card"><div class="card-head">${cfg.riskTitle||'安全与恢复'}</div><div class="card-body"><div class="notice hard"><div class="notice-title">${cfg.risk||'发现 1 项需要人工核验的高风险差异'}</div>${cfg.riskBody||'系统保留原对象与处理证据，不以页面操作直接覆盖临床事实。'}</div>${(cfg.facts||[['责任角色','已按岗位授权'],['当前范围','本机构 / 当前业务对象'],['版本','v2026.08'],['审计','完整'],['失败恢复','可重试/回退']]).map(x=>`<div class="folder-row">${x[0]}<span>${x[1]}</span></div>`).join('')}<button class="btn primary full-btn" ${cfg.detailJump?`data-jump="${cfg.detailJump}"`:''}>${cfg.detail||'进入处理详情'}</button></div></aside></div>`;
}

function hubPage(cfg){return pageHead(cfg.title,cfg.sub,`<button class="btn">角色工作台</button><button class="btn primary">查看全部待办</button>`)+`<div class="hub-hero"><div><span>${cfg.eyebrow}</span><h2>${cfg.hero}</h2><p>${cfg.desc}</p></div><div class="hub-score"><b>${cfg.score}</b><span>${cfg.scoreLabel}</span></div></div><div class="hub-module-grid">${cfg.modules.map(x=>`<button class="hub-module" data-jump="${x[2]}"><span>${x[3]}</span><b>${x[0]}</b><p>${x[1]}</p><i>进入 →</i></button>`).join('')}</div>`}

function careOperations(){return hubPage({title:'诊疗执行中心',sub:'收费、药学、医技、治疗、手麻、输血与设备执行的统一入口',eyebrow:'DIAGNOSIS & TREATMENT OPERATIONS',hero:'让医嘱从开立到执行、结果与费用形成同一条可对账证据链',desc:'每个专业工作台保留自己的资质、核查与状态机；中心只聚合任务和风险，不用统一“完成”绕过专业核验。',score:'12',scoreLabel:'高风险待办',modules:[['费用与结算','临床来源、预交、支付、退费冲正、日结对账','billing','¥'],['门诊药房','审方、调剂、发药、退药、库存与召回','outpatient-pharmacy','药'],['住院药学','摆药、配液、床旁给药和用药重整','inpatient-pharmacy','剂'],['检验工作台','申请、采样、标本、结果、危急值和更正','lab-workbench','检'],['病理工作台','申请、取材、制片、诊断、会诊、报告与更正','pathology-workbench','病'],['检查影像','预约、准备、执行、图像、报告与更正','imaging-workbench','影'],['治疗执行','资质、核对、执行、中止与不良事件','therapy-workbench','治'],['围手术期','排程、术前核查、植入物和手术记录','surgery-schedule','术'],['麻醉复苏','麻醉事件轴、监护、用药和复苏去向','anesthesia-workbench','麻'],['输血全链','申请、标本、血袋、双人核对、反应和回收','transfusion','血'],['设备监护','设备绑定、趋势、告警、断线与补传','device-monitoring','监']]})}
function qualityCenter(){return hubPage({title:'医疗质量中心',sub:'院科病历质量、评级证据、院感事件和临床资质统一治理',eyebrow:'QUALITY & SAFETY',hero:'把每个质量指标落回患者、文书、规则、缺陷和整改证据',desc:'功能存在不等于有效应用；中心同时呈现覆盖范围、数据质量、问题工单、整改复核与评级取证快照。具体病历和病案证据使用明确跨域入口查看。',score:'39/39',scoreLabel:'评价项目映射',modules:[['院科病历质控','运行与终末质控、抽查、整改和复核','department-qc','质'],['评级取证','39 项功能、应用范围、质量和证据快照','quality-rating','级'],['院感与不良事件','线索、排除、上报、重试和闭环','infection-events','感'],['临床资质','处方、手术、技术和临时授权','credentials','权'],['跨域：病历中心','进入患者病历创作、来源、审签和版本','record','历'],['跨域：病案资产中心','进入目录、扫描、验真、借阅和长期保存','archive-assets','案']]})}
function dataCenter(){return hubPage({title:'数据中心',sub:'统一数据接入、迁移、质量、设备、标准化与科研利用',eyebrow:'TRUSTED DATA CENTER',hero:'技术消息、临床对象、数据质量和利用授权在同一治理链上可追溯',desc:'数据中心不成为绕过临床权限的超级入口；每个页面继续执行患者关系、项目用途、最小字段和导出审批。',score:'99.93%',scoreLabel:'核心对象一致性',modules:[['集成交换','LIS、PACS、HIS、CA、医保与消息对账','integration','接'],['历史迁移','画像、映射、试迁移、增量、切换和回退','migration','迁'],['数据质量','MPI、关联、单位、迟到、隔离与整改','data-quality','质'],['设备接入','目录、网关、可信状态、绑定和时钟质量','devices','备'],['科研统计','项目、队列、统计、脱敏和受控交付','research','研']]})}
function aiCenter(){return hubPage({title:'AI 中心',sub:'AI医助小南、模型、Agent、Skill、Tool、评估与运行治理',eyebrow:'CLINICAL AI PLATFORM',hero:'AI 深嵌工作流，但不获得独立临床权力',desc:'从随处可见的AI医助小南，到模型路由和 Agent 运行，每个建议、来源、动作、审批、版本和停用策略均可追溯。',score:'0',scoreLabel:'未批准临床副作用',modules:[['AI医助小南','随问随答、主动提醒、来源核验和任务草拟','ai-assistant','✦'],['模型目录与路由','模型画像、边界、主备、灰度和回人工','models','模'],['Agent 设计','目标、上下文、步骤、预算、停止和审批','agent-catalog','A'],['Skill 目录','输入输出、依赖、评估、版本和许可证','skill-catalog','S'],['Tool 治理','查询/副作用契约、鉴权、幂等与补偿','tool-catalog','T'],['评估与发布','离线评估、红队、影子、灰度与回滚','agent-evals','评'],['运行事件','质量、安全、延迟、预算和单组件停用','aiops','运']]})}

function aiAssistantWorkspace(){return pageHead('AI医助小南','跨页面连续但按患者、就诊和任务严格隔离 · 当前：陈建国 / 门诊 OP0842','<button class="btn">小南设置</button><button class="btn danger">清空当前上下文</button>')+patientStrip()+`<div class="grid ai-workspace-layout"><aside class="card"><div class="card-head">本次诊疗建议 <span class="status amber">3 项</span></div><div class="card-body"><div class="ai-proactive-card high"><b>签署前核验</b><p>现病史写有“无胸痛”，但今日分诊记录存在“一过性胸闷”。</p><span>依据：分诊记录 08:43 · 当前病历草稿 v4</span><button class="btn sm">查看差异来源</button></div><div class="ai-proactive-card"><b>结果处置提醒</b><p>血钾 3.4 mmol/L 较上次下降，报告已确认但尚未记录处置。</p><span>依据：LIS 报告 LAB-88213 v2</span><button class="btn sm">加入诊疗计划草稿</button></div><div class="ai-proactive-card"><b>复诊计划缺项</b><p>调整降压方案后尚未填写家庭血压目标和复诊时间。</p><span>依据：高血压随访规则 v6</span><button class="btn sm">生成候选文本</button></div></div></aside><section class="card ai-conversation"><div class="card-head">与AI医助小南协作 <span class="status green">患者上下文已锁定</span></div><div class="ai-thread"><div class="ai-message user">帮我总结这位患者近 3 个月血压变化，并指出今天签病历前还缺什么。</div><div class="ai-message assistant"><b>近 3 个月趋势</b><p>门诊血压从 148/88 mmHg 上升至今日 156/92 mmHg；家庭最高 168/96 mmHg，夜间下降不足。当前仍使用氨氯地平 5mg qd。</p><div class="ai-citations"><button>门诊记录 07-21</button><button>动态血压 06-30</button><button>今日分诊 08:43</button></div><b>签署前建议核验</b><ol><li>分诊记录的“一过性胸闷”与现病史“无胸痛”并不完全等价，请核对。</li><li>补充血钾偏低的判断和处置。</li><li>补充用药调整后的家庭血压目标、复诊时间和复查项目。</li></ol><div class="notice info">以上为建议草稿，不会自动写入病历、诊断、医嘱或处方。</div></div></div><div class="ai-prompt-box"><div class="ai-quick-actions"><button>生成病历草稿</button><button>解释异常结果</button><button>检查遗漏</button><button>准备随访计划</button></div><textarea placeholder="询问当前患者，或要求生成可核验的草稿……"> </textarea><div><button class="btn">🎙 环境记录</button><button class="btn">添加当前页面</button><button class="btn primary">发送</button></div></div></section><aside class="card"><div class="card-head">上下文与权限</div><div class="card-body">${[['患者/就诊','陈建国 / OP0842'],['当前任务','门诊病历签署前核验'],['允许来源','当前就诊、近 90 天授权记录'],['禁止动作','签署、处方生效、医嘱执行'],['模型路由','route-opd-assistant v3'],['Agent/Skill','Assistant v2.0 / QC v2.3'],['上下文到期','切换患者立即清空']].map(x=>`<div class="folder-row">${x[0]}<span>${x[1]}</span></div>`).join('')}<div class="notice rule"><div class="notice-title">患者切换保护</div>切换患者、就诊或岗位后，本会话建议和未执行草稿全部失效。</div></div></aside></div>`}

const specificRenderers={
  'care-operations':careOperations,'quality-center':qualityCenter,'data-center':dataCenter,'ai-center':aiCenter,'ai-assistant':aiAssistantWorkspace,
  'department-qc':()=>coveragePage({title:'院科病历质控与整改',sub:'按院区、科室、病区、文书类型和责任人管理运行质控、终末质控与整改复核',rows:'safety',metrics:[['待整改缺陷','286'],['阻断缺陷','17'],['逾期工单','24'],['闭环率','94.6%']],primary:'创建质控抽查',secondary:'导出整改清单',flow:['定义抽样范围','运行规则/人工抽查','分派缺陷','临床人员更正文书','质控复核闭环'],risk:'12 份出院病历存在终末质控阻断',riskBody:'此页面只管理缺陷和整改任务；点击具体病历后跨域进入病历中心，不在质量中心直接修改临床原文。',detail:'跨域：进入病历中心整改',detailJump:'record'}),
  'ai-reminder-detail':()=>coveragePage({title:'AI 提醒证据详情',sub:'为什么现在提醒、严重度、患者/就诊、来源版本、触发条件和失效规则',rows:'ai',primary:'回到病历修正',secondary:'标记误报',flow:['业务事件触发','权限与患者核验','来源比对','医生处理','反馈与重算'],risk:'提醒依据存在一次报告更正',riskBody:'当前建议绑定 LIS 报告 v2；若报告再次更正，本次处理立即失效并重新评估。'}),
  'ai-capture':()=>coveragePage({title:'环境记录与转写审阅',sub:'显式录制、患者锁、说话人、时间点、转写、来源片段和草稿生成',rows:'ai',primary:'生成门诊病历草稿',secondary:'结束并丢弃录音',flow:['显式开始录制','确认患者与用途','转写分段','人工审阅','生成可编辑草稿'],risk:'有 2 句无法确认说话人',riskBody:'不确定内容不进入结构化病历字段，需医生回听并人工确认。'}),
  'ai-action-review':()=>coveragePage({title:'AI 候选动作与审批',sub:'患者、业务对象、Tool 参数、风险、影响、幂等和失败恢复的逐次确认',rows:'ai',primary:'批准创建本次草稿',secondary:'修改后批准',flow:['生成候选','预览完整参数','人工修改/批准','执行端重鉴权','核验真实结果'],risk:'批准仅适用于本次患者和可见参数',riskBody:'患者、内容、Tool、权限或业务状态变化后，本次批准自动过期。'}),
  'ai-assistant-policy':()=>coveragePage({title:'AI医助小南策略与效果治理',sub:'按角色、科室和用例管理主动级别、来源、模型、限频、静默、门禁与停用',rows:'ai',metrics:[['今日提醒','18,426'],['医生回源','72.4%'],['重复抑制','3,182'],['严重漏报','0']],primary:'创建小南策略草稿',risk:'神经内科提醒接受率异常升至 96%',riskBody:'高采纳不等于高质量，已触发机械批准与样本抽查，暂停扩大灰度。'}),
  'login-context':()=>coveragePage({title:'登录、锁屏恢复与工作上下文',sub:'专有身份标识、SSO/MFA、机构、岗位与班次上下文',rows:'ops',flow:['身份认证','选择机构','选择岗位','验证任期','进入首页'],risk:'当前岗位资质将在 2 天后到期',riskBody:'到期后禁止新临床写入，既往署名不受影响。'}),
  'unified-home':()=>coveragePage({title:'统一首页与任务中心',sub:'按角色聚合危急值、审签、会诊、路径和管理任务',rows:'safety',primary:'进入最高风险任务',flow:['接收业务任务','按风险排序','进入来源页面','完成业务核验','回写任务终态']}),
  'patient-registry':()=>coveragePage({title:'患者检索、匹配与登记',sub:'MPI 候选、待核身份与内部唯一患者标识',rows:'patient',primary:'确认已有患者',secondary:'创建待核身份',risk:'存在 2 个高相似患者候选',riskBody:'必须比较出生日期、证件、联系方式和既往标识，不能按姓名自动合并。'}),
  'patient-merge':()=>coveragePage({title:'重复患者合并与撤销',sub:'主记录选择、字段差异、双人复核和可逆关联',rows:'patient',primary:'提交双人复核',flow:['选择候选','比较全部差异','确定主记录','双人复核','合并/可撤销'],risk:'诊断与过敏字段存在冲突',riskBody:'冲突内容并列保留，合并操作不删除任何原始临床记录。'}),
  'patient-timeline':()=>coveragePage({title:'患者临床时间线',sub:'跨门急诊、住院、医嘱、结果和病历的授权视图',rows:'patient',primary:'打开本次就诊',flow:['选择时间范围','按权限聚合','核验来源版本','下钻业务对象','返回原位置']}),
  'emergency-access':()=>coveragePage({title:'紧急访问与待核身份',sub:'先救治、后补全；限时最小访问与事后复核',rows:'safety',primary:'申请 2 小时紧急访问',risk:'当前患者身份未完全核验',riskBody:'所有补录保留事件时间和记录时间，访问到期自动回收并进入安全复核。'}),
  'appointment-registration':()=>coveragePage({title:'预约、挂号、分诊、叫号与留观',sub:'号源、到诊、候诊、过号、退号和急危升级',rows:'patient',metrics:[['今日号源','286'],['已到诊','164'],['候诊','38'],['号源冲突','2']],primary:'创建门急诊就诊'}),
  'clinical-doc-editor':()=>coveragePage({title:'通用临床文书编辑器',sub:'结构化字段、自由文本、自动保存、并发和模板版本',rows:'patient',primary:'提交审签',risk:'另一工作站已更新同一草稿',riskBody:'系统保留双方内容并要求字段级比较，不进行静默覆盖。'}),
  'admission-bed':()=>coveragePage({title:'入院、病区与床位管理',sub:'入院通知、床位、医疗组、转科转床和接管责任',rows:'patient',metrics:[['待入院','18'],['空床','26'],['转科待接收','3'],['床位冲突','1']],primary:'确认入院分床'}),
  'billing':()=>coveragePage({title:'费用、收退费、预交与结算',sub:'临床项目来源、划价、支付、医保、冲正、票据和日结',rows:'ops',metrics:[['待结算','42'],['今日收入','¥386k'],['退费复核','6'],['对账差异','2']],risk:'1 笔收费缺少临床来源',riskBody:'禁止直接入账；需回到医嘱/执行对象核验或按批准的补录流程处理。'}),
  'outpatient-pharmacy':()=>coveragePage({title:'门诊药房审方与调剂',sub:'处方审核、库存、调剂、发药、退药和召回',rows:'pharmacy',primary:'进入审方',risk:'患者存在青霉素严重过敏',riskBody:'过敏硬规则独立于 AI，任何配置或助手建议都不能放行冲突处方。'}),
  'inpatient-pharmacy':()=>coveragePage({title:'住院药房、配液与床旁给药',sub:'摆药、配液、发药、退药、五正确与 ADR',rows:'pharmacy',primary:'打开摆药批次'}),
  'lab-workbench':()=>coveragePage({title:'检验工作台',sub:'申请、采样、标本接收、分析、审核、危急值和报告更正',rows:'medical',primary:'审核检验报告',risk:'血气标本患者腕带与申请不一致',riskBody:'标本进入隔离，不允许通过修改患者字段继续检测。'}),
  'pathology-workbench':()=>coveragePage({title:'病理科工作台',sub:'病理申请、标本接收、固定取材、制片染色、诊断会诊、报告签发与更正',rows:'medical',metrics:[['待接收标本','23'],['制片中','46'],['待诊断','31'],['危急/延迟','4']],primary:'进入病理诊断',secondary:'查看标本追踪',flow:['临床申请/送检','标本核对接收','取材制片染色','阅片/会诊','报告签发/更正'],risk:'乳腺标本容器号与申请部位不一致',riskBody:'标本进入隔离并联系送检科室；不得改写申请或容器标识以强行完成接收。',facts:[['患者/申请','王秀兰 / PATH-260814-019'],['标本','左乳肿物切除标本'],['固定时间','2026-08-14 09:16'],['蜡块/切片','3 / 8'],['诊断状态','待上级医师复核'],['报告版本','草稿 v2']]}),
  'imaging-workbench':()=>coveragePage({title:'检查影像工作台',sub:'预约、准备、执行、图像、报告、审核与更正',rows:'medical',primary:'打开待报告检查'}),
  'therapy-workbench':()=>coveragePage({title:'一般治疗工作台',sub:'治疗排程、资质、患者核对、执行、中止和不良事件',rows:'medical',primary:'开始治疗核对'}),
  'surgery-schedule':()=>coveragePage({title:'围手术期排程与安全核查',sub:'手术申请、排程、术前资料、三方核查和植入物',rows:'safety',primary:'打开手术安全核查',risk:'术前知情同意书缺少有效签署',riskBody:'除批准的紧急手术路径外，系统阻止进入手术开始状态。'}),
  'anesthesia-workbench':()=>coveragePage({title:'麻醉时间轴与复苏',sub:'术前访视、麻醉事件、用药、监护、复苏和术后去向',rows:'medical',primary:'进入麻醉事件轴'}),
  'device-monitoring':()=>coveragePage({title:'监护绑定、趋势与告警',sub:'患者—设备绑定、单位/时钟、趋势、断线补传与告警确认',rows:'medical',risk:'监护仪时钟偏差 86 秒',riskBody:'数据保留设备时间与接收时间，修正前不得用于精确事件排序。'}),
  'transfusion':()=>coveragePage({title:'输血全链工作台',sub:'申请、备血、配血、发血、双人核对、输注反应和回收',rows:'safety',primary:'开始床旁双人核对',risk:'血袋号与患者用血申请不匹配',riskBody:'硬阻断发血与输注，并形成输血科安全事件。'}),
  'quality-rating':()=>coveragePage({title:'医疗质量与电子病历评级看板',sub:'39 项评价的功能、应用范围、四维数据质量和证据快照',rows:'safety',metrics:[['项目映射','39/39'],['有效应用达标','31'],['待整改','8'],['证据缺口','3']],primary:'生成本期证据快照'}),
  'infection-events':()=>coveragePage({title:'院感、传染病与不良事件',sub:'智能线索、人工排除、上报时限、重试和整改闭环',rows:'safety',primary:'审核高风险线索'}),
  'data-quality':()=>coveragePage({title:'全院数据质量与问题工单',sub:'患者身份、关联、完整性、一致性、及时性和标准化',rows:'safety',metrics:[['质量规则','468'],['今日异常','1,286'],['高危错配','0'],['闭环率','96.8%']],primary:'创建数据整改工单'}),
  'devices':()=>coveragePage({title:'医疗设备目录与接入状态',sub:'设备身份、网关、可信状态、患者绑定、时钟、单位和固件',rows:'ops',metrics:[['登记设备','864'],['在线','829'],['不可信','2'],['错绑定','0']],primary:'登记设备'}),
  'capability-pack':()=>coveragePage({title:'机构能力包与继承解析',sub:'诊所、基层和医院按能力组合，不分叉临床内核',rows:'ops',primary:'创建能力包草稿'}),
  'specialty-coverage':()=>`<div class="page-head"><div class="page-title"><h1>科室适配与上线门禁</h1><p>按国家诊疗科目和医院实际科室逐项判定通用内核、专业闭环、专科包与替代系统边界</p></div><div class="head-actions"><button class="btn">导出差距清单</button><button class="btn primary">创建专科包计划</button></div></div><div class="metric-grid compact-metrics"><div class="metric"><div class="name">已登记科室组</div><div class="value">18</div><div class="trend">覆盖临床、医技与支持科室</div></div><div class="metric"><div class="name">基础闭环</div><div class="value">9</div><div class="trend">可进入综合医院试点验证</div></div><div class="metric"><div class="name">专科包待交付</div><div class="value">8</div><div class="trend">不宣称完整生产支持</div></div><div class="metric"><div class="name">阻断缺口</div><div class="value danger-text">1</div><div class="trend">体检中心独立流程未设计</div></div></div><section class="card"><div class="card-head">科室适配矩阵 <span class="sub">状态必须绑定版本、样本回放与科室负责人签字</span></div><table class="table"><thead><tr><th>科室组</th><th>通用内核</th><th>专业闭环/专科能力</th><th>当前状态</th><th>上线门禁</th></tr></thead><tbody>${[
    ['全科及内外科各亚专业','门急住、病历、医嘱、结果、会诊、质控','按专病配置模板/量表/路径/AI Skill','通用可用','科室模板、医嘱集和路径验收'],
    ['急诊/留观','独立急诊就诊、抢救、结果、交接','分诊、绿色通道、双时间事件轴','基础闭环','急救全流程与停机演练'],
    ['药学/检验/影像/病理','申请、执行、结果、费用和审计','专业标本/图像/报告状态机','基础闭环','与真实设备/系统契约验证'],
    ['手术/麻醉/输血','医嘱、任务、资质和结果','排台、三方核查、麻醉轴、双人核对','基础闭环','高风险 E2E 与资质组合测试'],
    ['妇产/生殖','通用门住院及病历内核','孕产、母婴、生殖周期、配子胚胎全流程','核心专科原型完成','专业人员评审、真实设备接口与全流程日验收'],
    ['儿科/新生儿','通用门住院及病历内核','监护人、年龄剂量、胎龄、出生事件与母婴关联','核心专科原型完成','儿童剂量、母婴身份与新生儿筛查实测'],
    ['重症/透析/肿瘤放疗','通用住院、医嘱、监护与结果','高频事件、器官支持、透析处方、放疗计划','专科包待交付','与专业系统边界及连续数据验证'],
    ['精神/心理','通用门住院及病历内核','风险评估、保护性医疗、监护人/授权和特殊隐私','核心专科原型完成','精神科专家、危机流程和特殊隐私红队'],
    ['眼科/耳鼻喉/口腔/皮肤','通用门诊、病历、检查和治疗','部位图、牙位、专科设备数据、图像与材料','核心专科原型完成','结构化部位、设备、手术侧别与材料追溯验收'],
    ['中医/民族医','通用就诊、病历和处方内核','四诊、证候、治法方药与中药规则','核心专科原型完成','中医专家、术语、毒性饮片和处方规则验收'],
    ['康复/疼痛/营养','通用医嘱与治疗执行','评估量表、计划、疗程、功能结局','部分可用','疗程状态机和量表版本验收'],
    ['预防保健/公卫/院感','患者、任务、报告和审计','筛查、随访、上报和院感事件','部分可用','上报接口、时限与幂等验证'],
    ['体检中心','患者、检查检验和报告复用','套餐、导检、总检、职业健康与团检','暂不支持','独立需求和工作台完成前禁止上线']
  ].map((x,i)=>`<tr><td><b>${x[0]}</b></td><td>${x[1]}</td><td>${x[2]}</td><td><span class="status ${['基础闭环','通用可用','核心专科原型完成'].includes(x[3])?'green':x[3]==='暂不支持'?'red':'amber'}">${x[3]}</span></td><td>${x[4]}</td></tr>`).join('')}</tbody></table><div class="card-body"><div class="notice hard"><div class="notice-title">支持声明门禁</div>“原型完成”仍不等于该科室可生产上线；只有专业流程、字段、规则、设备/接口、权限、质控、恢复、容量和科室签字全部通过，才能把状态改为“生产支持”。</div></div></section>`,
  'form-designer':()=>coveragePage({title:'表单与病历模板设计器',sub:'字段、布局、校验、计算、签署、打印和标准映射',rows:'ops',primary:'打开可视化设计器'}),
  'rule-center':()=>coveragePage({title:'规则、时限与提示中心',sub:'平台硬规则、机构规则、提醒和 AI 建议分层治理',rows:'safety',primary:'创建规则草稿'}),
  'scope-designer':()=>coveragePage({title:'角色、职责与数据范围设计',sub:'岗位、患者关系、组织范围、临时授权和职责分离',rows:'ops',primary:'运行权限模拟'}),
  'config-release':()=>coveragePage({title:'配置差异、验证、审批与发布',sub:'静态校验、沙箱回放、影响分析、灰度、停止和回滚',rows:'ops',primary:'提交独立审批'}),
  'config-upgrade':()=>coveragePage({title:'配置包与产品升级冲突中心',sub:'来源、签名、依赖、三方差异、兼容性和旧版保留',rows:'ops',primary:'处理三方差异'}),
  'model-connection':()=>coveragePage({title:'模型连接与数据边界',sub:'端点、秘密引用、驻留、允许字段、容量和健康检查',rows:'ai',primary:'运行安全连接测试'}),
  'model-routing':()=>coveragePage({title:'模型路由、主备与人工降级',sub:'逐用例固定版本、同边界故障切换和人工流程',rows:'ai',primary:'创建路由草稿'}),
  'model-evaluation':()=>coveragePage({title:'模型评估、影子、灰度与隔离',sub:'临床质量、安全、格式、延迟、成本和供应方政策门禁',rows:'ai',primary:'启动影子评估'}),
  'agent-catalog':()=>coveragePage({title:'Agent 目录与定义编辑器',sub:'有限目标、角色、上下文、Skills、Tools、预算、停止和输出契约',rows:'ai',primary:'新建 Agent'}),
  'agent-context':()=>coveragePage({title:'Agent 上下文与记忆策略',sub:'患者/就诊隔离、最小字段、过期、撤权和外发边界',rows:'ai',primary:'运行上下文差分测试'}),
  'tool-catalog':()=>coveragePage({title:'Tool 目录、风险与审批策略',sub:'只读/副作用分级、运行时鉴权、幂等、超时和补偿',rows:'ai',primary:'登记 Tool'}),
  'skill-catalog':()=>coveragePage({title:'Skill 目录与定义编辑器',sub:'输入输出、允许上下文、Tools、依赖、评估、版本和许可证',rows:'ai',primary:'创建 Skill'}),
  'agent-compose':()=>coveragePage({title:'Agent、Skill 与 Tool 组合画布',sub:'依赖锁定、权限交集、敏感数据流和失败补偿',rows:'ai',primary:'验证组合'}),
  'agent-evals':()=>coveragePage({title:'Agent 与 Skill 评估发布中心',sub:'供应链扫描、临床 Evals、红队、审批、影子、灰度与回滚',rows:'ai',primary:'提交评估门禁'}),
  'install':()=>coveragePage({title:'安装向导与首次健康检查',sub:'环境、秘密、数据库、对象存储、合成数据和能力包',rows:'ops',primary:'运行健康检查'}),
  'backup':()=>coveragePage({title:'备份、恢复与完整性报告',sub:'备份集、隔离恢复、对象/附件/签名校验和差异报告',rows:'ops',primary:'启动隔离恢复演练'}),
  'operations':()=>coveragePage({title:'生产运行、灾备与停机续运',sub:'容量、HA、故障转移、停机表单、补录和恢复对账',rows:'ops',primary:'启动故障演练'}),
  'release-gates':()=>coveragePage({title:'Release 门禁与制品发布',sub:'测试、安全、迁移、恢复、SBOM、支持矩阵和回滚说明',rows:'ops',primary:'评审候选版本'}),
  'opensource':()=>coveragePage({title:'开源指标、文档与贡献入口',sub:'Stars、有效下载、安装证据、Issue、路线图、贡献和安全披露',rows:'ops',metrics:[['GitHub Stars','8,426'],['稳定版下载','31,284'],['安装验证','82.6%'],['外部贡献者','146']],primary:'打开贡献指南'})
};
Object.assign(renderers,specificRenderers);
const emergencyRendererV09=renderers.emergency;
renderers.emergency=()=>emergencyRendererV09();

const shellV10=shell;
function aiPanelContent(){
  const clinicalContext=outpatientRoutes.includes(current)||emergencyRoutes.includes(current)||inpatientRoutes.includes(current)||['ward','record','record-editor','record-sources','record-qc','record-sign','record-versions','record-diff','lis-report','pacs-viewer','clinical-tasks'].includes(current)||(typeof specialtyRoutes!=='undefined'&&specialtyRoutes.includes(current)&&current!=='specialty-center');
  const title=clinicalContext?'当前患者AI医助小南':'AI医助小南';
  const context=clinicalContext?'陈建国 · 门诊 OP0842 · 当前页面已纳入上下文':'未选择患者 · 不读取病历正文';
  return `<div class="ai-assistant-panel" id="aiAssistantPanel"><div class="ai-panel-head"><div><span>✦ HaoEMR AI</span><b>${title}</b><small>${context}</small></div><button data-ai-close>×</button></div><div class="ai-panel-body">${clinicalContext?`<div class="ai-alert-card"><b>现在提醒 · 签署前</b><p>分诊记录提到“一过性胸闷”，与当前现病史需要核对。</p><button data-jump="record-sources">查看 2 条来源</button></div><div class="ai-suggestion"><b>你还可以问</b><button>总结近 3 个月病情变化</button><button>检查当前病历遗漏</button><button>解释异常检验结果</button><button>生成复诊计划草稿</button></div>`:`<div class="notice info"><div class="notice-title">安全空上下文</div>选择患者和就诊后才会组装最小必要临床资料。当前可以询问功能使用、配置解释和任务导航。</div><div class="ai-suggestion"><button>我能做什么？</button><button>解释当前页面</button><button>查找功能入口</button></div>`}<div class="ai-mini-thread"><div>AI 建议始终显示来源、版本和失效条件，不会自动签署、开药或执行医嘱。</div></div></div><div class="ai-panel-input"><textarea placeholder="询问当前页面或当前患者……"></textarea><div><button>🎙</button><button data-jump="ai-assistant">完整工作区</button><button class="primary">发送</button></div></div></div><button class="ai-fab" data-ai-toggle aria-label="打开 AI医助小南"><span>✦</span><b>AI医助小南</b><i>3</i></button>${clinicalContext?`<div class="ai-nudge" id="aiNudge"><button data-ai-dismiss>×</button><b>AI 提醒</b><span>签署前有 3 项建议核验</span><a data-ai-toggle>查看</a></div>`:''}`;
}
shell=function(content){return shellV10(content).replace('<div class="top-actions">','<div class="top-actions"><button class="icon-btn ai-top-trigger" data-ai-toggle title="打开 AI医助小南">✦</button>')+aiPanelContent()};

const renderV10=render;
render=function(){
  renderV10();
  const main=document.querySelector('.main');
  if(!main)return;
  let nav='';
  if(clinicalFoundationRoutes.includes(current))nav=clinicalFoundationNav(current);
  else if(careOperationRoutes.includes(current))nav=careOperationNav(current);
  else if(qualityCenterRoutes.includes(current))nav=qualityCenterNav(current);
  else if(dataCenterRoutes.includes(current))nav=dataCenterNav(current);
  else if(aiPlatformRoutes.includes(current))nav=aiCenterNav(current);
  else if(configurationRoutes.includes(current))nav=configCenterNav(current);
  if(nav)main.insertAdjacentHTML('afterbegin',nav);
  document.querySelectorAll('.nav-item.active').forEach(x=>x.classList.remove('active'));
  let parent=current;
  if(clinicalFoundationRoutes.includes(current))parent='clinical';
  else if(outpatientRoutes.includes(current))parent=recordRoutes.includes(current)?'record':'outpatient';
  else if(emergencyRoutes.includes(current))parent='emergency';
  else if(inpatientRoutes.includes(current)||current==='ward')parent='inpatient';
  else if(careOperationRoutes.includes(current))parent='care-operations';
  else if(recordRoutes.includes(current)||current==='clinical-doc-editor')parent='record';
  else if(archiveRoutes.includes(current))parent='archive-assets';
  else if(qualityCenterRoutes.includes(current))parent='quality-center';
  else if(dataCenterRoutes.includes(current))parent='data-center';
  else if(aiPlatformRoutes.includes(current))parent='ai-center';
  else if(configurationRoutes.includes(current))parent='workflow';
  else if(operationRoutes.includes(current))parent='admin';
  else if(adminRoutes.includes(current))parent='admin';
  const parentButton=document.querySelector(`[data-page="${parent}"]`);if(parentButton)parentButton.classList.add('active');
  document.querySelectorAll('[data-jump]').forEach(b=>b.onclick=()=>{current=b.dataset.jump;location.hash=current;render()});
  document.querySelectorAll('[data-ai-toggle]').forEach(b=>b.onclick=()=>document.getElementById('aiAssistantPanel')?.classList.toggle('open'));
  document.querySelectorAll('[data-ai-close]').forEach(b=>b.onclick=()=>document.getElementById('aiAssistantPanel')?.classList.remove('open'));
  document.querySelectorAll('[data-ai-dismiss]').forEach(b=>b.onclick=()=>document.getElementById('aiNudge')?.remove());
};
render();
