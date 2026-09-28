<script setup lang="ts">
import { computed } from 'vue';

type RailMode = 'worklist' | 'overview' | 'course' | 'orders' | 'results' | 'consult' | 'ward' | 'discharge';

const props = withDefaults(defineProps<{
  mode: RailMode;
  patientName?: string;
  bedLabel?: string;
  wardName?: string;
  pendingCount?: number;
  completedCount?: number;
  overdueCount?: number;
  totalCount?: number;
  openCount?: number;
  status?: string;
  opinion?: string | null;
  recommendation?: string | null;
}>(), {
  patientName: '当前住院患者',
  bedLabel: '—',
  wardName: '当前病区',
  pendingCount: 0,
  completedCount: 0,
  overdueCount: 0,
  totalCount: 0,
  openCount: 0,
  status: '在院',
  opinion: null,
  recommendation: null,
});

const completion = computed(() => {
  const total = props.pendingCount + props.completedCount;
  return total ? Math.round((props.completedCount / total) * 100) : 100;
});
</script>

<template>
  <aside class="card scroll-card prototype-right-rail" :aria-label="`${mode}业务右侧栏`">
    <template v-if="mode === 'worklist'">
      <div class="card-head">{{ bedLabel }}床 · {{ patientName }} <span class="risk red">重点</span></div>
      <div class="card-body prototype-rail-body">
        <div class="section-title">病情与任务摘要</div>
        <p class="prototype-summary">{{ wardName }}当前在院患者，今日诊疗任务已按时限和风险排序。处置前请确认当前患者。</p>
        <div v-if="overdueCount" class="notice hard"><div class="notice-title">已超时 {{ overdueCount }} 项</div>请优先完成逾期文书及审签，逾期事实会永久留痕。</div>
        <div v-else class="notice info"><div class="notice-title">当前无逾期文书</div>继续关注新结果、医嘱变更和会诊意见。</div>
        <div class="section-title">近 12 小时变化</div>
        <div class="prototype-timeline"><div><i></i><span><b>文书待办 {{ pendingCount }} 项</b><small>时限会自动更新</small></span></div><div><i></i><span><b>当前岗位已确认</b><small>请核对患者和病区</small></span></div><div><i></i><span><b>数据已更新</b><small>请以最新信息处理</small></span></div></div>
        <RouterLink class="button primary prototype-rail-action" to="/inpatient-overview">打开住院患者总览</RouterLink>
      </div>
    </template>

    <template v-else-if="mode === 'overview'">
      <div class="card-head">文书与诊疗待办 <span class="status red">{{ overdueCount }} 阻断</span></div>
      <div class="card-body prototype-rail-body">
        <div class="completion-ring" :style="{ background: `conic-gradient(#e99d23 ${completion}%, #e8edf2 0)` }" :class="{ warning: completion < 80 }"><b>{{ completion }}%</b><span>病历完整度</span></div>
        <div v-if="pendingCount" class="notice hard"><div class="notice-title">住院文书待完成</div>当前还有 {{ pendingCount }} 项任务未闭环。<RouterLink to="/inpatient-course">立即处理</RouterLink></div>
        <div class="notice rule"><div class="notice-title">临床变化须形成记录</div>异常体征、新结果与医嘱调整应在当日病程中可追溯。</div>
        <div class="section-title">今日任务</div>
        <div class="prototype-queue"><div><b>病程与查房</b><span class="status amber">{{ pendingCount }} 项</span></div><div><b>检查检验复核</b><RouterLink to="/ip-results">去查看</RouterLink></div><div><b>会诊意见处理</b><RouterLink to="/ip-consult">去处理</RouterLink></div><div><b>出院准备</b><RouterLink to="/inpatient-discharge">去校验</RouterLink></div></div>
        <div class="notice ai"><div class="notice-title">AI 病情变化建议</div>可按已授权来源起草病程，但必须由医生逐条核实并签署。</div>
      </div>
    </template>

    <template v-else-if="mode === 'course'">
      <div class="card-head">质控、来源与审签 <span class="status amber">{{ pendingCount }} 项</span></div>
      <div class="tabs"><button class="tab active" type="button">质控</button><RouterLink class="tab" to="/inpatient-doc-versions">来源</RouterLink><RouterLink class="tab" to="/inpatient-doc-versions">审签</RouterLink></div>
      <div class="card-body prototype-rail-body">
        <div v-if="overdueCount" class="notice hard"><div class="notice-title">法规时限 · 已逾期</div>{{ overdueCount }} 项文书超时，逾期事实保留，签署时需记录说明。</div>
        <div class="notice rule"><div class="notice-title">结构完整性</div>诊断依据、鉴别计划、处置和复评必须形成可执行记录。</div>
        <div class="notice ai"><div class="notice-title">AI 一致性建议</div>用药计划应与最新体征、检验和会诊意见交叉核对。<RouterLink to="/ip-results">查看来源</RouterLink></div>
        <div class="section-title">三级查房责任链</div>
        <div class="review-chain"><div class="done">住院医师<small>记录与修订</small></div><div>主治医师<small>审阅与退回</small></div><div class="muted">主任医师<small>按规则终审</small></div></div>
      </div>
    </template>

    <template v-else-if="mode === 'orders'">
      <div class="card-head">用药安全与执行 <span class="status red">{{ openCount }} 项待执行</span></div>
      <div class="card-body prototype-rail-body">
        <div class="notice hard"><div class="notice-title">用药与体征需联合复核</div>活动医嘱执行前须重新核对血压、过敏、肾功能与电解质。</div>
        <div class="notice rule"><div class="notice-title">审核与关联结果</div>药品医嘱在签署时执行确定性硬规则，阻断项不会生成执行任务。</div>
        <div class="section-title">当前执行摘要</div>
        <div class="prototype-queue"><div><b>医嘱总数</b><span>{{ totalCount }}</span></div><div><b>活动医嘱</b><span>{{ openCount }}</span></div><div><b>待执行任务</b><span class="status amber">{{ pendingCount }}</span></div></div>
        <div class="notice info"><div class="notice-title">停止医嘱不是删除</div>停止人、时间、原因、已计划/已执行部分和后续处置全部保留。</div>
      </div>
    </template>

    <template v-else-if="mode === 'results'">
      <div class="card-head">异常与危急值闭环 <span class="status red">{{ openCount }} 开放</span></div>
      <div class="card-body prototype-rail-body">
        <div class="approval-box"><b>{{ patientName }} · {{ bedLabel }}床</b><p class="meta">{{ totalCount }} 份已签发报告 · {{ pendingCount }} 份异常</p><span class="status" :class="openCount ? 'red' : 'green'">{{ openCount ? '危急值尚未闭环' : '当前危急值已闭环' }}</span></div>
        <div class="section-title">闭环字段</div>
        <div class="prototype-checks"><div><span>通知对象</span><b class="green">已关联</b></div><div><span>接收确认</span><b :class="openCount ? 'amber' : 'green'">{{ openCount ? '待确认' : '已完成' }}</b></div><div><span>临床判断</span><b :class="openCount ? 'red' : 'green'">{{ openCount ? '待填写' : '已留痕' }}</b></div><div><span>处置医嘱</span><b :class="openCount ? 'red' : 'green'">{{ openCount ? '待关联' : '已关联' }}</b></div><div><span>复测计划</span><b :class="openCount ? 'amber' : 'green'">{{ openCount ? '待安排' : '已安排' }}</b></div></div>
        <div class="notice hard"><div class="notice-title">已阅不等于已处置</div>危急值必须形成临床判断、处置、复测和结果闭环。</div>
      </div>
    </template>

    <template v-else-if="mode === 'consult'">
      <div class="card-head">会诊意见与处理 <span class="status blue">{{ status }}</span></div>
      <div class="card-body prototype-rail-body">
        <div class="approval-box"><b>{{ opinion || '等待独立临床岗位签署会诊意见' }}</b><p class="meta">签署后不可覆盖，更正必须追加新证据</p><p>{{ recommendation || '完成接诊后填写专业判断与后续处置建议。' }}</p></div>
        <div class="section-title">意见处理</div>
        <div class="prototype-queue"><div><b>会诊申请</b><span class="status green">已留痕</span></div><div><b>独立医生接诊</b><span>{{ status }}</span></div><div><b>会诊意见</b><span :class="opinion ? 'status green' : 'status amber'">{{ opinion ? '已签署' : '待签署' }}</span></div><div><b>申请方闭环</b><span :class="status === 'COMPLETED' ? 'status green' : 'status blue'">{{ status === 'COMPLETED' ? '已完成' : '待确认' }}</span></div></div>
        <RouterLink class="button primary prototype-rail-action" to="/inpatient-course">写入今日病程</RouterLink>
      </div>
    </template>

    <template v-else-if="mode === 'ward'">
      <div class="card-head">{{ bedLabel }}床 · {{ patientName }} <span class="risk red">重点</span></div>
      <div class="card-body prototype-rail-body">
        <div class="section-title">本班待办</div>
        <div class="prototype-queue"><div><b>床旁任务复核</b><span class="status amber">{{ pendingCount }} 项</span></div><div><b>生命体征复测</b><span class="status red">即将到期</span></div><div><b>风险评估</b><span class="status blue">待执行</span></div><div><b>交班摘要</b><span>{{ totalCount }} 条</span></div></div>
        <div class="notice hard"><div class="notice-title">床旁核对要求</div>给药和高风险执行前必须再次核对患者与业务对象，完成后不可删除。</div>
        <div class="section-title">最新观察</div>
        <div class="prototype-timeline"><div><i></i><span><b>当班患者风险已交接</b><small>异常及处置将进入不可变交接证据</small></span></div><div><i></i><span><b>当前病区上下文已校验</b><small>仅接班护士可确认完成</small></span></div></div>
      </div>
    </template>

    <template v-else>
      <div class="card-head">整改与归档责任 <span class="status red">{{ pendingCount }} 阻断</span></div>
      <div class="card-body prototype-rail-body">
        <div class="completion-ring warning" :style="{ background: `conic-gradient(#e99d23 ${completion}%, #e8edf2 0)` }"><b>{{ completion }}%</b><span>出院准备度</span></div>
        <div class="section-title">阻断项责任链</div>
        <div class="prototype-queue"><div><b>住院文书</b><span class="status" :class="pendingCount ? 'red' : 'green'">{{ pendingCount ? `${pendingCount} 项待处理` : '已就绪' }}</span></div><div><b>上级审签</b><span class="status amber">按规则校验</span></div><div><b>结果与医嘱</b><span class="status blue">独立闭环</span></div><div><b>病案归档</b><span class="status gray">出院后校验</span></div></div>
        <div class="notice hard"><div class="notice-title">病案归档不可误提交</div>临床出院、病历完成和病案归档是不同状态，未完成病历不得显示为已归档。</div>
        <div class="notice info"><div class="notice-title">归档后更正</div>必须创建新版本、重新签署并保留原版本和变更原因。</div>
      </div>
    </template>
  </aside>
</template>
