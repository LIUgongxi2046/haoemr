insert into knowledge_source_registry(
  tenant_id, source_id, source_code, source_name, source_kind, source_path,
  license, allowed_use, sensitivity, update_frequency, checksum, status)
select t.tenant_id, '018f0000-0000-7000-8000-00000000d201'::uuid,
  'SYNTHETIC_CLINICAL_KNOWLEDGE', '合成临床知识中心', 'MANUAL',
  'dev-synthetic://knowledge-center', '内部验收数据', '仅用于本地临床知识中心功能验收',
  'INTERNAL', 'ON_DEMAND', repeat('a', 64), 'ACTIVE'
from tenant t
where t.tenant_id = '018f0000-0000-7000-8000-00000000aa01'::uuid
on conflict (tenant_id, source_code) do update set
  source_name = excluded.source_name,
  source_path = excluded.source_path,
  status = 'ACTIVE',
  updated_at = now();

insert into knowledge_import_batch(
  tenant_id, batch_id, source_id, source_root, selection_matrix_version,
  source_manifest_hash, mode, imported_row_count, skipped_row_count, status, operator)
select t.tenant_id, '018f0000-0000-7000-8000-00000000d205'::uuid,
  '018f0000-0000-7000-8000-00000000d201'::uuid, 'dev-synthetic://knowledge-center',
  'synthetic-knowledge-v1', repeat('b', 64), 'READ_ONLY', 1, 0, 'COMPLETED',
  '018f0000-0000-7000-8000-00000000aa04'::uuid
from tenant t
where t.tenant_id = '018f0000-0000-7000-8000-00000000aa01'::uuid
on conflict (tenant_id, batch_id) do nothing;

insert into knowledge_source_file(
  tenant_id, file_id, batch_id, source_path, source_content_hash,
  entity_category, system, table_name, included)
select t.tenant_id, '018f0000-0000-7000-8000-00000000d206'::uuid,
  '018f0000-0000-7000-8000-00000000d205'::uuid,
  '医学结构化数据/合成临床路径知识图谱.md', repeat('c', 64),
  '临床路径', 'PATHWAY_GRAPH', 'knowledge_concept', true
from tenant t
where t.tenant_id = '018f0000-0000-7000-8000-00000000aa01'::uuid
on conflict (tenant_id, file_id) do nothing;

insert into knowledge_document(
  tenant_id, document_id, document_code, content_type, title,
  source_authority, license, classification)
select t.tenant_id, '018f0000-0000-7000-8000-00000000d202'::uuid,
  'SYNTHETIC_PATHWAY_GRAPH', 'GRAPH_ENTITY', '合成临床路径知识图谱',
  'HaoEMR 合成验收数据', '内部验收数据', 'INTERNAL'
from tenant t
where t.tenant_id = '018f0000-0000-7000-8000-00000000aa01'::uuid
on conflict (tenant_id, document_code) do nothing;

insert into knowledge_document_version(
  tenant_id, doc_version_id, document_id, version, content_hash, markdown,
  metadata, status, effective_from, published_by)
select t.tenant_id, '018f0000-0000-7000-8000-00000000d203'::uuid,
  '018f0000-0000-7000-8000-00000000d202'::uuid, '1.0', repeat('d', 64),
  $$# 合成临床路径知识图谱

该数据集用于验证临床路径知识库、来源追踪、图谱关系查询和临床路径检索。

包含急性心肌梗死、社区获得性肺炎、检查、治疗方案和典型临床表现之间的关系。$$,
  jsonb_build_object('fixture_source', 'dev-synthetic', 'review_status', 'ACTIVE'),
  'ACTIVE', now(), '018f0000-0000-7000-8000-00000000aa04'::uuid
from tenant t
where t.tenant_id = '018f0000-0000-7000-8000-00000000aa01'::uuid
on conflict (tenant_id, document_id, version) do nothing;

insert into knowledge_chunk(
  tenant_id, chunk_id, doc_version_id, section_path, section_title, text,
  token_count, language, clinical_tags, content_hash, source_locator)
select t.tenant_id, '018f0000-0000-7000-8000-00000000d207'::uuid,
  '018f0000-0000-7000-8000-00000000d203'::uuid, 'pathway-graph', '临床路径图谱',
  '急性心肌梗死路径关联心电图、抗血小板治疗和胸痛伴出汗；社区获得性肺炎路径关联胸部 CT、抗感染治疗和发热伴咳嗽。',
  32, 'zh-CN', '["临床路径", "知识图谱"]'::jsonb, repeat('e', 64),
  jsonb_build_object('source_path', '医学结构化数据/合成临床路径知识图谱.md')
from tenant t
where t.tenant_id = '018f0000-0000-7000-8000-00000000aa01'::uuid
on conflict (tenant_id, chunk_id) do nothing;

insert into knowledge_graph_import_batch(
  tenant_id, graph_import_batch_id, source_id, source_document_version_id,
  manifest_hash, imported_concept_count, imported_relation_count, status, imported_by)
select t.tenant_id, '018f0000-0000-7000-8000-00000000d204'::uuid,
  '018f0000-0000-7000-8000-00000000d201'::uuid,
  '018f0000-0000-7000-8000-00000000d203'::uuid,
  repeat('f', 64), 10, 8, 'COMPLETED',
  '018f0000-0000-7000-8000-00000000aa04'::uuid
from tenant t
where t.tenant_id = '018f0000-0000-7000-8000-00000000aa01'::uuid
on conflict (tenant_id, graph_import_batch_id) do nothing;

insert into knowledge_concept(
  tenant_id, concept_id, source_type, source_id, system, code, display,
  source_graph_import_batch_id)
select t.tenant_id, seed.concept_id::uuid, 'DICTIONARY', 'SYNTHETIC_CLINICAL_KNOWLEDGE',
  seed.system, seed.code, seed.display, '018f0000-0000-7000-8000-00000000d204'::uuid
from (values
  ('018f0000-0000-7000-8000-00000000d211', 'PATHWAY', 'PATH-AMI', '急性心肌梗死临床路径'),
  ('018f0000-0000-7000-8000-00000000d212', 'PATHWAY', 'PATH-CAP', '社区获得性肺炎临床路径'),
  ('018f0000-0000-7000-8000-00000000d213', 'DISEASE', 'AMI', '急性心肌梗死'),
  ('018f0000-0000-7000-8000-00000000d214', 'DISEASE', 'CAP', '社区获得性肺炎'),
  ('018f0000-0000-7000-8000-00000000d215', 'EXAM', 'ECG', '心电图'),
  ('018f0000-0000-7000-8000-00000000d216', 'EXAM', 'CHEST-CT', '胸部 CT'),
  ('018f0000-0000-7000-8000-00000000d217', 'TREATMENT', 'ANTIPLATELET', '抗血小板治疗'),
  ('018f0000-0000-7000-8000-00000000d218', 'TREATMENT', 'ANTIBIOTIC', '抗感染治疗'),
  ('018f0000-0000-7000-8000-00000000d219', 'FINDING', 'CHEST-PAIN', '胸痛伴出汗'),
  ('018f0000-0000-7000-8000-00000000d220', 'FINDING', 'FEVER-COUGH', '发热伴咳嗽')
) as seed(concept_id, system, code, display)
cross join tenant t
where t.tenant_id = '018f0000-0000-7000-8000-00000000aa01'::uuid
on conflict (tenant_id, source_type, system, code) do nothing;

insert into knowledge_relation(
  tenant_id, relation_id, from_concept, to_concept, rel_type, version,
  source_graph_import_batch_id)
select t.tenant_id, seed.relation_id::uuid, seed.from_concept::uuid, seed.to_concept::uuid,
  seed.rel_type, seed.version, '018f0000-0000-7000-8000-00000000d204'::uuid
from (values
  ('018f0000-0000-7000-8000-00000000d231', '018f0000-0000-7000-8000-00000000d211', '018f0000-0000-7000-8000-00000000d213', 'MAPS_TO', '对应疾病'),
  ('018f0000-0000-7000-8000-00000000d232', '018f0000-0000-7000-8000-00000000d212', '018f0000-0000-7000-8000-00000000d214', 'MAPS_TO', '对应疾病'),
  ('018f0000-0000-7000-8000-00000000d233', '018f0000-0000-7000-8000-00000000d213', '018f0000-0000-7000-8000-00000000d215', 'SUPPORTED_BY', '首选检查'),
  ('018f0000-0000-7000-8000-00000000d234', '018f0000-0000-7000-8000-00000000d214', '018f0000-0000-7000-8000-00000000d216', 'SUPPORTED_BY', '首选检查'),
  ('018f0000-0000-7000-8000-00000000d235', '018f0000-0000-7000-8000-00000000d213', '018f0000-0000-7000-8000-00000000d217', 'CONSTRAINS', '治疗方案'),
  ('018f0000-0000-7000-8000-00000000d236', '018f0000-0000-7000-8000-00000000d214', '018f0000-0000-7000-8000-00000000d218', 'CONSTRAINS', '治疗方案'),
  ('018f0000-0000-7000-8000-00000000d237', '018f0000-0000-7000-8000-00000000d213', '018f0000-0000-7000-8000-00000000d219', 'MENTIONS', '典型表现'),
  ('018f0000-0000-7000-8000-00000000d238', '018f0000-0000-7000-8000-00000000d214', '018f0000-0000-7000-8000-00000000d220', 'MENTIONS', '典型表现')
) as seed(relation_id, from_concept, to_concept, rel_type, version)
cross join tenant t
where t.tenant_id = '018f0000-0000-7000-8000-00000000aa01'::uuid
on conflict (tenant_id, from_concept, to_concept, rel_type, coalesce(version, '')) do nothing;
