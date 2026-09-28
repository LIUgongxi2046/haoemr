<script setup lang="ts">
// @ts-nocheck  // d3 回调类型过于复杂，跳过本文件的 TS 检查
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import * as d3 from 'd3';
import {
  getKnowledgeGraph,
  getKnowledgeGraphEgo,
  getKnowledgeGraphNeighbors,
  getKnowledgeGraphPaths,
  issuePathwayKnowledgeLease,
} from '../../api/pathway-knowledge';
import { importKnowledgeGraph, issueKnowledgeLease, listKnowledgeSources } from '../../api/knowledge';
import type {
  ContextLeaseWire,
  KnowledgeGraphEdgeWire,
  KnowledgeGraphNeighborsWire,
  KnowledgeGraphNodeWire,
  KnowledgeGraphPathWire,
  KnowledgeGraphPathsWire,
  KnowledgeSourceWire,
} from '../../generated/contracts';

const container = ref<HTMLDivElement | null>(null);
const notice = ref('');
const loading = ref(false);
const stats = ref({ nodes: 0, edges: 0 });
const searchQuery = ref('');
const allNodes = ref<KnowledgeGraphNodeWire[]>([]);
const allEdges = ref<KnowledgeGraphEdgeWire[]>([]);
const hiddenTypes = ref<Set<string>>(new Set());
const showEdgeLabels = ref(false);
const importOpen = ref(false);
const importBusy = ref(false);
const importSources = ref<KnowledgeSourceWire[]>([]);
const graphImport = ref({ sourceId: '', sourceDocumentVersionId: '', concepts: '[\n  {"system":"LOCAL","code":"EXAMPLE-A","display":"示例概念 A"},\n  {"system":"LOCAL","code":"EXAMPLE-B","display":"示例概念 B"}\n]', relations: '[\n  {"from_system":"LOCAL","from_code":"EXAMPLE-A","to_system":"LOCAL","to_code":"EXAMPLE-B","relation_type":"SUPPORTED_BY"}\n]' });

// 当前视图数据源：全图 or 某个节点的 ego 子图
const viewNodes = ref<KnowledgeGraphNodeWire[]>([]);
const viewEdges = ref<KnowledgeGraphEdgeWire[]>([]);
const egoCenterId = ref<string | null>(null);
const egoDepth = ref(2);
const egoLoading = ref(false);

// 视图模式：图谱 / 关系列表
const viewMode = ref<'graph' | 'list'>('graph');
const listQuery = ref('');
const showPathwaysOnly = ref(false);

const selectedId = ref<string | null>(null);
const neighbors = ref<KnowledgeGraphNeighborsWire | null>(null);
const neighborLoading = ref(false);

const pathFromText = ref('');
const pathToText = ref('');
const pathFromId = ref<string | null>(null);
const pathToId = ref<string | null>(null);
const pathDepth = ref(3);
const pathResult = ref<KnowledgeGraphPathsWire | null>(null);
const pathLoading = ref(false);
const activePathIndex = ref(0);
const activePath = computed<KnowledgeGraphPathWire | null>(
  () => pathResult.value?.paths[activePathIndex.value] ?? null,
);

type SimNode = KnowledgeGraphNodeWire & { x?: number; y?: number; fx?: number | null; fy?: number | null };
type SimEdge = KnowledgeGraphEdgeWire & { _key?: string };

const edgeKey = (e: { source: string; target: string; predicate: string }) =>
  `${e.source}→${e.target}→${e.predicate}`;

const nodeIndex = computed(() => new Map([...allNodes.value, ...viewNodes.value].map((n) => [n.id, n])));

const searchResults = computed(() => {
  const q = searchQuery.value.trim().toLowerCase();
  if (!q) return [];
  return allNodes.value.filter((n) => n.label.toLowerCase().includes(q)).slice(0, 12);
});

const pathFromResults = computed(() => nodePickResults(pathFromText.value));
const pathToResults = computed(() => nodePickResults(pathToText.value));
function nodePickResults(query: string) {
  const q = query.trim().toLowerCase();
  if (!q) return [];
  return allNodes.value.filter((n) => n.label.toLowerCase().includes(q)).slice(0, 12);
}

const typeColor = (t: string) => ({
  疾病: '#ef4444', 药品: '#10b981', 中成药: '#10b981', 观测操作: '#3b82f6', 操作: '#3b82f6',
  检查: '#3b82f6', 临床所见: '#f59e0b', 临床路径: '#8b5cf6', 药物治疗方案: '#06b6d4',
  操作治疗方案: '#06b6d4', 就诊类型: '#14b8a6', 事件: '#64748b', 人群: '#ec4899', 组织机构: '#84cc16',
} as Record<string, string>)[t] ?? '#94a3b8';

const typeCounts = computed(() => {
  const m = new Map<string, number>();
  for (const n of allNodes.value) m.set(n.type, (m.get(n.type) ?? 0) + 1);
  return [...m.entries()].sort((a, b) => b[1] - a[1]);
});

// 展示子图 = 可见节点 ∪ 当前路径上的节点（路径可能穿过当前视图之外的节点）
const displayNodes = computed<KnowledgeGraphNodeWire[]>(() => {
  const map = new Map<string, KnowledgeGraphNodeWire>();
  for (const n of viewNodes.value) if (!hiddenTypes.value.has(n.type)) map.set(n.id, n);
  if (activePath.value) for (const n of activePath.value.nodes) if (!map.has(n.id)) map.set(n.id, n);
  return [...map.values()];
});
const displayEdges = computed<KnowledgeGraphEdgeWire[]>(() => {
  const ids = new Set(displayNodes.value.map((n) => n.id));
  const map = new Map<string, KnowledgeGraphEdgeWire>();
  for (const e of viewEdges.value) if (ids.has(e.source) && ids.has(e.target)) map.set(edgeKey(e), e);
  if (activePath.value) for (const e of activePath.value.edges) {
    const k = edgeKey(e);
    if (!map.has(k)) map.set(k, e);
  }
  return [...map.values()];
});

// 关系列表视图
const relationRows = computed(() => {
  const idx = nodeIndex.value;
  return displayEdges.value.map((e) => ({
    key: edgeKey(e),
    from: idx.get(e.source)?.label ?? e.source,
    fromType: idx.get(e.source)?.type ?? '',
    predicate: e.predicate,
    to: idx.get(e.target)?.label ?? e.target,
    toType: idx.get(e.target)?.type ?? '',
  }));
});
const filteredRows = computed(() => {
  const q = listQuery.value.trim().toLowerCase();
  if (!q) return relationRows.value;
  return relationRows.value.filter((r) => `${r.from}${r.predicate}${r.to}`.toLowerCase().includes(q));
});

const incomingFiltered = computed(() => {
  const list = neighbors.value?.incoming ?? [];
  return showPathwaysOnly.value ? list.filter((n) => n.node.type === '临床路径') : list;
});
const outgoingFiltered = computed(() => {
  const list = neighbors.value?.outgoing ?? [];
  return showPathwaysOnly.value ? list.filter((n) => n.node.type === '临床路径') : list;
});

let simulation: d3.Simulation<SimNode, d3.SimulationLinkDatum<SimNode>> | null = null;
let nodeSel: any = null;
let linkSel: any = null;
let labelSel: any = null;
let edgeLabelSel: any = null;
let rootG: any = null;
let svgEl: any = null;
let degreeMap = new Map<string, number>();

onMounted(async () => {
  const l = await ensureLease();
  loading.value = true;
  try {
    const g = await getKnowledgeGraph(l, 250);
    stats.value = { nodes: g.nodes.length, edges: g.edges.length };
    allNodes.value = g.nodes;
    allEdges.value = g.edges;
    viewNodes.value = g.nodes;
    viewEdges.value = g.edges;
    renderAll();
  } catch (e) {
    notice.value = e instanceof Error ? e.message : '图谱加载失败';
  } finally {
    loading.value = false;
  }
});

const usableImportSources = computed(() => importSources.value.filter((source) => ['REGISTERED', 'ACTIVE'].includes(source.status)));

async function openImport() {
  importOpen.value = !importOpen.value;
  if (!importOpen.value || importSources.value.length) return;
  try {
    importSources.value = await listKnowledgeSources(await issueKnowledgeLease());
    if (!graphImport.value.sourceId && usableImportSources.value.length) graphImport.value.sourceId = usableImportSources.value[0].source_id;
  } catch (error) {
    notice.value = error instanceof Error ? error.message : '无法读取可用知识来源';
  }
}

async function submitGraphImport() {
  if (importBusy.value || !graphImport.value.sourceId) return;
  importBusy.value = true;
  notice.value = '';
  try {
    const concepts = JSON.parse(graphImport.value.concepts);
    const relations = JSON.parse(graphImport.value.relations);
    if (!Array.isArray(concepts) || !Array.isArray(relations)) throw new Error('概念和关系必须分别是 JSON 数组');
    if (concepts.some((concept) => concept?.source_type && concept.source_type !== 'DICTIONARY')) {
      throw new Error('仅允许已审核的 DICTIONARY 词典概念导入；EXTRACTED 内容须先走证据复核');
    }
    const batch = await importKnowledgeGraph(await issueKnowledgeLease(), {
      source_id: graphImport.value.sourceId,
      source_document_version_id: graphImport.value.sourceDocumentVersionId.trim() || null,
      concepts: concepts.map((concept) => ({ ...concept, source_type: 'DICTIONARY' })),
      relations,
    });
    notice.value = `图谱导入已完成：新增 ${batch.imported_concept_count} 个词典概念、${batch.imported_relation_count} 条关系；批次 …${batch.graph_import_batch_id.slice(-8)}。`;
    importOpen.value = false;
    const l = await ensureLease();
    const graph = await getKnowledgeGraph(l, 250);
    stats.value = { nodes: graph.nodes.length, edges: graph.edges.length };
    allNodes.value = graph.nodes; allEdges.value = graph.edges; viewNodes.value = graph.nodes; viewEdges.value = graph.edges;
    renderAll();
  } catch (error) {
    notice.value = error instanceof Error ? error.message : '图谱导入失败';
  } finally { importBusy.value = false; }
}

onBeforeUnmount(() => { simulation?.stop(); });

function ensureLease(): Promise<ContextLeaseWire> {
  return issuePathwayKnowledgeLease();
}

function renderAll() {
  const nodes = displayNodes.value as SimNode[];
  const edges = displayEdges.value as SimEdge[];
  degreeMap = new Map<string, number>();
  for (const e of edges) {
    degreeMap.set(e.source, (degreeMap.get(e.source) ?? 0) + 1);
    degreeMap.set(e.target, (degreeMap.get(e.target) ?? 0) + 1);
  }
  draw(nodes, edges);
}

function draw(nodes: SimNode[], edges: SimEdge[]) {
  if (!container.value) return;
  container.value.innerHTML = '';
  const width = container.value.clientWidth || 900;
  const height = container.value.clientHeight || 520;

  svgEl = d3.select(container.value).append('svg')
    .attr('width', width).attr('height', height).attr('viewBox', [0, 0, width, height]);
  rootG = svgEl.append('g');
  svgEl.call(d3.zoom<SVGSVGElement, unknown>().scaleExtent([0.2, 6]).on('zoom', (ev) => rootG!.attr('transform', ev.transform)));

  const labelById = new Map(nodes.map((n) => [n.id, n.label]));
  const simEdges = edges.map((e) => ({ ...e, _key: edgeKey(e) }));

  linkSel = rootG.append('g').selectAll('line').data(simEdges).join('line')
    .attr('stroke', '#cbd5e1').attr('stroke-opacity', 0.45).attr('stroke-width', 1);
  linkSel.append('title')
    .text((d) => `${labelById.get(d.source) ?? ''} —[${d.predicate}]→ ${labelById.get(d.target) ?? ''}`);

  edgeLabelSel = rootG.append('g').selectAll('text').data(simEdges).join('text')
    .text((d) => d.predicate.length > 6 ? d.predicate.slice(0, 6) + '…' : d.predicate)
    .attr('font-size', 7.5).attr('fill', '#94a3b8').attr('text-anchor', 'middle')
    .style('pointer-events', 'none')
    .style('display', showEdgeLabels.value ? null : 'none');

  nodeSel = rootG.append('g').selectAll('circle').data(nodes).join('circle')
    .attr('r', (d) => 3 + Math.min(degreeMap.get(d.id) ?? 0, 8))
    .attr('fill', (d) => typeColor(d.type))
    .attr('stroke', '#fff').attr('stroke-width', 1)
    .call(d3.drag<SVGCircleElement, SimNode>().on('start', dragStart).on('drag', dragged).on('end', dragEnd) as never);

  labelSel = rootG.append('g').selectAll('text').data(nodes).join('text')
    .text((d) => d.label.length > 10 ? d.label.slice(0, 10) + '…' : d.label)
    .attr('font-size', 9).attr('dx', 6).attr('dy', 3)
    .attr('fill', '#64748b').style('pointer-events', 'none');

  nodeSel.append('title').text((d) => `${d.label}（${d.type}）· 度数 ${degreeMap.get(d.id) ?? 0}`);
  nodeSel.on('click', (_, d) => selectNode(d.id));

  simulation = d3.forceSimulation<SimNode>(nodes)
    .force('link', d3.forceLink<SimNode, d3.SimulationLinkDatum<SimNode>>(simEdges).id((d) => d.id).distance(50))
    .force('charge', d3.forceManyBody().strength(-90))
    .force('center', d3.forceCenter(width / 2, height / 2))
    .force('collide', d3.forceCollide<SimNode>().radius(14));

  simulation.on('tick', () => {
    linkSel.attr('x1', (d: any) => (d.source as SimNode).x ?? 0).attr('y1', (d: any) => (d.source as SimNode).y ?? 0)
      .attr('x2', (d: any) => (d.target as SimNode).x ?? 0).attr('y2', (d: any) => (d.target as SimNode).y ?? 0);
    edgeLabelSel
      .attr('x', (d: any) => (((d.source as SimNode).x ?? 0) + ((d.target as SimNode).x ?? 0)) / 2)
      .attr('y', (d: any) => (((d.source as SimNode).y ?? 0) + ((d.target as SimNode).y ?? 0)) / 2 - 2);
    nodeSel!.attr('cx', (d: any) => d.x ?? 0).attr('cy', (d: any) => d.y ?? 0);
    labelSel.attr('x', (d: any) => d.x ?? 0).attr('y', (d: any) => d.y ?? 0);
  });

  applyHighlights();

  function dragStart(ev: d3.D3DragEvent<SVGCircleElement, SimNode, SimNode>, d: SimNode) {
    if (!ev.active) simulation?.alphaTarget(0.3).restart();
    d.fx = d.x; d.fy = d.y;
  }
  function dragged(ev: d3.D3DragEvent<SVGCircleElement, SimNode, SimNode>, d: SimNode) {
    d.fx = ev.x; d.fy = ev.y;
  }
  function dragEnd(ev: d3.D3DragEvent<SVGCircleElement, SimNode, SimNode>, d: SimNode) {
    if (!ev.active) simulation?.alphaTarget(0);
    d.fx = null; d.fy = null;
  }
}

function applyHighlights() {
  if (!nodeSel || !linkSel || !labelSel) return;
  const sel = selectedId.value;
  const pathNodeIds = new Set(activePath.value?.nodes.map((n) => n.id) ?? []);
  const pathEdgeKeys = new Set(activePath.value?.edges.map((e) => edgeKey(e)) ?? []);
  const hasPath = !!activePath.value;
  nodeSel
    .attr('stroke', (d) => (d.id === sel ? '#0f172a' : pathNodeIds.has(d.id) ? '#f59e0b' : '#fff'))
    .attr('stroke-width', (d) => (d.id === sel ? 3 : pathNodeIds.has(d.id) ? 2.5 : 1))
    .attr('opacity', (d) => (hasPath && !pathNodeIds.has(d.id) ? 0.3 : 1));
  linkSel
    .attr('stroke', (d) => (pathEdgeKeys.has(d._key) ? '#f59e0b' : '#cbd5e1'))
    .attr('stroke-opacity', (d) => (pathEdgeKeys.has(d._key) ? 0.95 : 0.45))
    .attr('stroke-width', (d) => (pathEdgeKeys.has(d._key) ? 2.5 : 1));
  labelSel.attr('opacity', (d) => (hasPath && !pathNodeIds.has(d.id) ? 0.25 : 1));
}

async function selectNode(id: string) {
  selectedId.value = id;
  applyHighlights();
  neighborLoading.value = true;
  try {
    const l = await ensureLease();
    neighbors.value = await getKnowledgeGraphNeighbors(l, id);
  } catch (e) {
    notice.value = e instanceof Error ? e.message : '邻居查询失败';
  } finally {
    neighborLoading.value = false;
  }
}

function focusNode(id: string) {
  const node = (displayNodes.value as SimNode[]).find((n) => n.id === id);
  if (!node || !simulation || !nodeSel || !rootG || !container.value) return;
  const w = container.value.clientWidth;
  const h = container.value.clientHeight;
  node.fx = w / 2; node.fy = h / 2;
  simulation.alpha(1).restart();
  selectNode(id);
}

function toggleType(type: string) {
  const next = new Set(hiddenTypes.value);
  if (next.has(type)) next.delete(type); else next.add(type);
  hiddenTypes.value = next;
  renderAll();
}

function toggleEdgeLabels() {
  showEdgeLabels.value = !showEdgeLabels.value;
  edgeLabelSel?.style('display', showEdgeLabels.value ? null : 'none');
}

async function expandEgo() {
  if (!selectedId.value) return;
  egoLoading.value = true;
  try {
    const l = await ensureLease();
    const g = await getKnowledgeGraphEgo(l, selectedId.value, egoDepth.value);
    viewNodes.value = g.nodes;
    viewEdges.value = g.edges;
    egoCenterId.value = selectedId.value;
    stats.value = { nodes: g.nodes.length, edges: g.edges.length };
    notice.value = `已展开「${neighbors.value?.node.label ?? ''}」${egoDepth.value} 跳子图：${g.nodes.length} 节点 / ${g.edges.length} 关系`;
    renderAll();
  } catch (e) {
    notice.value = e instanceof Error ? e.message : '子图展开失败';
  } finally {
    egoLoading.value = false;
  }
}

function resetEgo() {
  viewNodes.value = allNodes.value;
  viewEdges.value = allEdges.value;
  egoCenterId.value = null;
  stats.value = { nodes: allNodes.value.length, edges: allEdges.value.length };
  notice.value = '';
  renderAll();
}

async function runPathQuery() {
  if (!pathFromId.value || !pathToId.value) {
    notice.value = '请先选择起点和终点节点';
    return;
  }
  pathLoading.value = true;
  pathResult.value = null;
  activePathIndex.value = 0;
  try {
    const l = await ensureLease();
    pathResult.value = await getKnowledgeGraphPaths(l, pathFromId.value, pathToId.value, pathDepth.value);
    activePathIndex.value = 0;
    notice.value = pathResult.value.paths.length
      ? `找到 ${pathResult.value.paths.length} 条关系路径`
      : '两点之间在限定跳数内没有可达路径';
    renderAll();
  } catch (e) {
    notice.value = e instanceof Error ? e.message : '路径查询失败';
  } finally {
    pathLoading.value = false;
  }
}

function clearPath() {
  pathResult.value = null;
  activePathIndex.value = 0;
  pathFromId.value = null;
  pathToId.value = null;
  pathFromText.value = '';
  pathToText.value = '';
  notice.value = '';
  renderAll();
}

function pathLabel(path: KnowledgeGraphPathWire) {
  return path.nodes.map((n) => n.label).join(' → ');
}

function exportCsv() {
  const header = ['起点', '起点类型', '关系', '终点', '终点类型'];
  const rows = filteredRows.value.map((r) => [r.from, r.fromType, r.predicate, r.to, r.toType]);
  const lines = [header, ...rows].map((cells) => cells.map((c) => `"${(c ?? '').replace(/"/g, '""')}"`).join(','));
  const blob = new Blob(['\ufeff' + lines.join('\n')], { type: 'text/csv;charset=utf-8;' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = 'knowledge-graph-relations.csv';
  a.click();
  URL.revokeObjectURL(url);
}
</script>

<template>
  <section data-page-root class="content vue-native-page">
    <div class="page-head">
      <div class="page-title">
        <h1>知识图谱</h1>
        <p>临床路径 ↔ 疾病 / 药品 / 检查 / 临床所见 的关系网络。点节点看邻居，选两点查路径，展开子图看局部。</p>
      </div>
      <div class="head-actions">
        <button class="btn ghost" @click="openImport">{{ importOpen ? '收起受控导入' : '受控导入' }}</button>
        <button class="btn ghost" @click="toggleEdgeLabels">
          {{ showEdgeLabels ? '隐藏关系标签' : '显示关系标签' }}
        </button>
        <button v-if="pathResult" class="btn ghost" @click="clearPath">清除路径</button>
        <span class="stat">节点 {{ stats.nodes }} · 关系 {{ stats.edges }}</span>
      </div>
    </div>
    <p v-if="notice" class="notice-banner">{{ notice }}</p>

    <section v-if="importOpen" class="graph-import-panel" aria-label="受控图谱导入">
      <header><div><h2>受控图谱导入</h2><p>仅可选择已登记且可用的来源；只接受已审核的词典概念。导入会生成固定清单哈希、批次、审计和 Outbox，不会写入患者或临床事实。</p></div></header>
      <div v-if="!usableImportSources.length" class="path-empty">暂无可用知识来源。请先在知识来源台账登记并启用来源。</div>
      <form v-else class="graph-import-form" @submit.prevent="submitGraphImport">
        <label>来源<select v-model="graphImport.sourceId" required aria-label="图谱导入来源"><option v-for="source in usableImportSources" :key="source.source_id" :value="source.source_id">{{ source.source_name }}（{{ source.source_code }} · {{ source.status }}）</option></select></label>
        <label>来源文档版本（可选）<input v-model="graphImport.sourceDocumentVersionId" placeholder="知识文档版本 UUID；留空表示来源级导入" /></label>
        <label>词典概念 JSON<textarea v-model="graphImport.concepts" rows="7" aria-label="词典概念 JSON" required /></label>
        <label>关系 JSON<textarea v-model="graphImport.relations" rows="6" aria-label="关系 JSON" required /></label>
        <div class="graph-import-actions"><button type="button" class="btn ghost" @click="importOpen = false">取消</button><button class="btn primary" :disabled="importBusy">{{ importBusy ? '正在导入…' : '导入审核词典概念' }}</button></div>
      </form>
    </section>

    <div class="graph-layout">
      <aside class="graph-side">
        <div class="side-block">
          <h3>搜索定位</h3>
          <div class="search-bar">
            <input v-model="searchQuery" placeholder="搜索节点，如：糖尿病 / 发热 / 环磷酰胺…" />
            <div v-if="searchResults.length" class="search-drop">
              <button v-for="r in searchResults" :key="r.id" class="search-item" @click="focusNode(r.id); searchQuery = ''">
                <span class="dot" :style="{ background: typeColor(r.type) }"></span>
                {{ r.label }} <small>{{ r.type }}</small>
              </button>
            </div>
          </div>
        </div>

        <div class="side-block">
          <h3>节点类型</h3>
          <label v-for="[type, count] in typeCounts" :key="type" class="type-row">
            <input type="checkbox" :checked="!hiddenTypes.has(type)" @change="toggleType(type)" />
            <span class="dot" :style="{ background: typeColor(type) }"></span>
            <span class="type-name">{{ type }}</span>
            <span class="type-count">{{ count }}</span>
          </label>
        </div>

        <div class="side-block" v-if="selectedId">
          <h3>子图展开</h3>
          <label class="field-label">跳数</label>
          <select v-model.number="egoDepth" class="depth-select" aria-label="邻域跳数">
            <option :value="1">1 跳</option>
            <option :value="2">2 跳</option>
            <option :value="3">3 跳</option>
          </select>
          <button class="btn primary" :disabled="egoLoading" @click="expandEgo">
            {{ egoLoading ? '展开中…' : '展开 N 跳子图' }}
          </button>
          <button v-if="egoCenterId" class="btn ghost full" @click="resetEgo">恢复全图</button>
        </div>

        <div class="side-block">
          <h3>关系路径查询</h3>
          <label class="field-label">起点</label>
          <div class="pick">
            <input v-model="pathFromText" placeholder="选择起点节点…" />
            <div v-if="pathFromResults.length" class="search-drop">
              <button v-for="r in pathFromResults" :key="r.id" class="search-item" @click="pathFromId = r.id; pathFromText = r.label">
                <span class="dot" :style="{ background: typeColor(r.type) }"></span>
                {{ r.label }} <small>{{ r.type }}</small>
              </button>
            </div>
          </div>
          <label class="field-label">终点</label>
          <div class="pick">
            <input v-model="pathToText" placeholder="选择终点节点…" />
            <div v-if="pathToResults.length" class="search-drop">
              <button v-for="r in pathToResults" :key="r.id" class="search-item" @click="pathToId = r.id; pathToText = r.label">
                <span class="dot" :style="{ background: typeColor(r.type) }"></span>
                {{ r.label }} <small>{{ r.type }}</small>
              </button>
            </div>
          </div>
          <label class="field-label">最大跳数</label>
          <select v-model.number="pathDepth" class="depth-select" aria-label="路径最大跳数">
            <option :value="1">1 跳</option>
            <option :value="2">2 跳</option>
            <option :value="3">3 跳</option>
            <option :value="4">4 跳</option>
          </select>
          <button class="btn primary" :disabled="pathLoading" @click="runPathQuery">
            {{ pathLoading ? '查询中…' : '查询关系路径' }}
          </button>

          <div v-if="pathResult" class="path-results">
            <div v-if="pathResult.paths.length" class="path-count">共 {{ pathResult.paths.length }} 条路径</div>
            <div v-else class="path-empty">两点间无 ≤{{ pathDepth }} 跳可达路径</div>
            <button
              v-for="(p, i) in pathResult.paths"
              :key="i"
              class="path-row"
              :class="{ active: i === activePathIndex }"
              @click="activePathIndex = i; renderAll()"
            >
              {{ pathLabel(p) }}
            </button>
          </div>
        </div>
      </aside>

      <div class="graph-main">
        <div class="view-tabs">
          <button class="tab" :class="{ active: viewMode === 'graph' }" @click="viewMode = 'graph'">图谱视图</button>
          <button class="tab" :class="{ active: viewMode === 'list' }" @click="viewMode = 'list'">关系列表</button>
          <span class="stat muted">当前 {{ relationRows.length }} 条关系</span>
        </div>

        <div v-show="viewMode === 'graph'">
          <div v-if="loading" class="loading">图谱加载中…</div>
          <div ref="container" class="graph-container"></div>
        </div>

        <div v-show="viewMode === 'list'" class="list-panel">
          <div class="list-toolbar">
            <input v-model="listQuery" placeholder="搜索关系：起点 / 终点 / 关系词…" />
            <button class="btn ghost" @click="exportCsv">导出 CSV</button>
          </div>
          <div class="relation-table-wrap">
            <table class="relation-table">
              <thead>
                <tr><th>起点</th><th>关系</th><th>终点</th><th>起点类型</th><th>终点类型</th></tr>
              </thead>
              <tbody>
                <tr v-for="r in filteredRows" :key="r.key">
                  <td>{{ r.from }}</td>
                  <td><span class="pred">{{ r.predicate }}</span></td>
                  <td>{{ r.to }}</td>
                  <td><span class="dot" :style="{ background: typeColor(r.fromType) }"></span> {{ r.fromType }}</td>
                  <td><span class="dot" :style="{ background: typeColor(r.toType) }"></span> {{ r.toType }}</td>
                </tr>
                <tr v-if="!filteredRows.length"><td colspan="5" class="muted">无匹配关系</td></tr>
              </tbody>
            </table>
          </div>
        </div>
      </div>

      <aside class="neighbor-panel" v-if="selectedId">
        <div class="neighbor-head">
          <h3>节点关系</h3>
          <button class="btn ghost small" @click="selectedId = null; neighbors = null; applyHighlights()">×</button>
        </div>
        <div v-if="neighborLoading" class="loading">邻居加载中…</div>
        <template v-else-if="neighbors">
          <div class="neighbor-node">
            <span class="dot" :style="{ background: typeColor(neighbors.node.type) }"></span>
            <strong>{{ neighbors.node.label }}</strong>
            <small>{{ neighbors.node.type }}</small>
          </div>
          <label class="pathway-toggle">
            <input type="checkbox" v-model="showPathwaysOnly" /> 只看临床路径（反向溯源）
          </label>
          <div class="neighbor-section">
            <div class="neighbor-title">指向它（入边）· {{ incomingFiltered.length }}</div>
            <div v-if="!incomingFiltered.length" class="muted">无</div>
            <button v-for="n in incomingFiltered" :key="'i' + n.node.id + n.predicate" class="neighbor-row" @click="focusNode(n.node.id)">
              <span class="dot" :style="{ background: typeColor(n.node.type) }"></span>
              {{ n.node.label }} <em>{{ n.predicate }}</em>
            </button>
          </div>
          <div class="neighbor-section">
            <div class="neighbor-title">它指向（出边）· {{ outgoingFiltered.length }}</div>
            <div v-if="!outgoingFiltered.length" class="muted">无</div>
            <button v-for="n in outgoingFiltered" :key="'o' + n.node.id + n.predicate" class="neighbor-row" @click="focusNode(n.node.id)">
              <span class="dot" :style="{ background: typeColor(n.node.type) }"></span>
              {{ n.node.label }} <em>{{ n.predicate }}</em>
            </button>
          </div>
        </template>
      </aside>
    </div>
  </section>
</template>

<style scoped>
.graph-layout { display: flex; gap: 14px; align-items: flex-start; }
.graph-side { width: 280px; flex-shrink: 0; display: flex; flex-direction: column; gap: 12px; }
.graph-main { flex: 1; min-width: 0; }
.neighbor-panel { width: 300px; flex-shrink: 0; max-height: calc(100vh - 210px); overflow-y: auto; background: #fff; border: 1px solid #e2e8f0; border-radius: 12px; padding: 14px; }

.side-block { background: #fff; border: 1px solid #e2e8f0; border-radius: 12px; padding: 12px; }
.side-block h3 { font-size: 12px; text-transform: uppercase; letter-spacing: .04em; color: #64748b; margin: 0 0 10px; }

.graph-container { width: 100%; height: calc(100vh - 260px); min-height: 420px; background: #fff; border: 1px solid #e2e8f0; border-radius: 12px; overflow: hidden; }
.graph-container :deep(svg) { cursor: grab; }

.view-tabs { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.view-tabs .tab { padding: 6px 14px; border-radius: 8px; font-size: 13px; cursor: pointer; border: 1px solid #cbd7e5; background: #fff; color: #475569; }
.view-tabs .tab.active { background: #2563eb; color: #fff; border-color: #2563eb; }
.muted { color: #94a3b8; }

.list-panel { background: #fff; border: 1px solid #e2e8f0; border-radius: 12px; padding: 12px; }
.list-toolbar { display: flex; gap: 10px; margin-bottom: 10px; }
.list-toolbar input { flex: 1; padding: 8px 10px; border: 1px solid #cbd7e5; border-radius: 8px; font-size: 13px; }
.relation-table-wrap { max-height: calc(100vh - 330px); overflow: auto; }
.relation-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.relation-table th, .relation-table td { text-align: left; padding: 7px 10px; border-bottom: 1px solid #eef2f6; vertical-align: middle; }
.relation-table th { position: sticky; top: 0; background: #f8fafc; color: #64748b; font-weight: 600; z-index: 1; }
.relation-table td .dot { display: inline-block; vertical-align: middle; margin-right: 4px; }
.pred { background: #f1f5f9; color: #334155; padding: 1px 8px; border-radius: 10px; font-size: 12px; }

.head-actions { display: flex; align-items: center; gap: 10px; }
.btn { padding: 7px 12px; border-radius: 8px; font-size: 12px; cursor: pointer; border: 1px solid #cbd7e5; }
.btn.ghost { background: #fff; color: #334155; }
.btn.ghost:hover { background: #f1f5f9; }
.btn.primary { background: #2563eb; color: #fff; border: none; width: 100%; margin-top: 6px; }
.btn.primary:disabled { background: #93c5fd; cursor: not-allowed; }
.btn.small { padding: 2px 8px; }
.btn.full { width: 100%; margin-top: 6px; }

.stat { font-size: 12px; color: #64748b; }
.notice-banner { margin: 12px 0; padding: 10px 14px; border-radius: 8px; background: #eff6ff; color: #2563eb; }
.loading { color: #94a3b8; padding: 20px; text-align: center; }
.graph-import-panel { margin: 12px 0; padding: 16px; border: 1px solid #bfdbfe; border-radius: 12px; background: #f8fbff; }
.graph-import-panel header h2 { margin: 0; font-size: 16px; color: #1e3a5f; }
.graph-import-panel header p { margin: 6px 0 14px; color: #475569; font-size: 13px; line-height: 1.5; }
.graph-import-form { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.graph-import-form label { display: grid; gap: 5px; color: #334155; font-size: 12px; font-weight: 600; }
.graph-import-form label:nth-child(3), .graph-import-form label:nth-child(4) { grid-column: 1 / -1; }
.graph-import-form input, .graph-import-form select, .graph-import-form textarea { box-sizing: border-box; width: 100%; border: 1px solid #cbd7e5; border-radius: 8px; padding: 8px 10px; background: #fff; color: #334155; font: 12px ui-monospace, SFMono-Regular, Menlo, monospace; }
.graph-import-actions { grid-column: 1 / -1; display: flex; justify-content: flex-end; gap: 8px; }
.graph-import-actions .btn.primary { width: auto; margin: 0; }

.search-bar, .pick { position: relative; margin-bottom: 8px; }
.search-bar input, .pick input { width: 100%; padding: 8px 10px; border: 1px solid #cbd7e5; border-radius: 8px; font-size: 13px; box-sizing: border-box; }
.search-drop { position: absolute; top: 38px; left: 0; right: 0; background: #fff; border: 1px solid #e2e8f0; border-radius: 8px; box-shadow: 0 8px 24px rgba(0,0,0,.08); z-index: 20; max-height: 220px; overflow-y: auto; }
.search-item { display: flex; align-items: center; gap: 8px; width: 100%; padding: 7px 10px; border: none; background: transparent; cursor: pointer; font-size: 13px; text-align: left; }
.search-item:hover { background: #f1f5f9; }
.search-item small { color: #94a3b8; }

.type-row { display: flex; align-items: center; gap: 8px; padding: 3px 0; font-size: 13px; cursor: pointer; }
.type-row input { margin: 0; }
.type-name { flex: 1; color: #334155; }
.type-count { color: #94a3b8; font-size: 12px; }
.dot { width: 9px; height: 9px; border-radius: 50%; flex-shrink: 0; }

.field-label { display: block; font-size: 12px; color: #64748b; margin: 6px 0 4px; }
.depth-select { width: 100%; padding: 7px 10px; border: 1px solid #cbd7e5; border-radius: 8px; font-size: 13px; margin-bottom: 6px; }

.path-results { margin-top: 10px; display: flex; flex-direction: column; gap: 6px; }
.path-count, .path-empty { font-size: 12px; color: #64748b; }
.path-row { text-align: left; font-size: 12px; padding: 8px 10px; border: 1px solid #e2e8f0; border-radius: 8px; background: #fff; cursor: pointer; color: #334155; word-break: break-all; }
.path-row:hover { background: #f1f5f9; }
.path-row.active { border-color: #f59e0b; background: #fffbeb; }

.neighbor-head { display: flex; align-items: center; justify-content: space-between; }
.neighbor-head h3 { font-size: 12px; text-transform: uppercase; letter-spacing: .04em; color: #64748b; margin: 0; }
.neighbor-node { display: flex; align-items: center; gap: 8px; padding: 10px 0; border-bottom: 1px solid #eef2f6; }
.neighbor-node small { color: #94a3b8; }
.pathway-toggle { display: flex; align-items: center; gap: 6px; font-size: 12px; color: #475569; padding: 8px 0; cursor: pointer; }
.pathway-toggle input { margin: 0; }
.neighbor-section { margin-top: 10px; }
.neighbor-title { font-size: 12px; color: #64748b; margin-bottom: 4px; }
.neighbor-row { display: flex; align-items: center; gap: 8px; width: 100%; text-align: left; padding: 5px 6px; border: none; background: transparent; cursor: pointer; font-size: 13px; border-radius: 6px; }
.neighbor-row:hover { background: #f1f5f9; }
.neighbor-row em { margin-left: auto; color: #94a3b8; font-style: normal; font-size: 12px; background: #f1f5f9; padding: 1px 6px; border-radius: 10px; }
@media (max-width: 600px) { .graph-layout { display: grid; grid-template-columns: minmax(0, 1fr); } .graph-side, .neighbor-panel { width: auto; max-height: none; } .graph-container { min-height: 340px; height: 55vh; } .graph-import-form { grid-template-columns: minmax(0, 1fr); } .graph-import-form label:nth-child(3), .graph-import-form label:nth-child(4) { grid-column: auto; } }
</style>
