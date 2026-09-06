-- 受控知识图谱导入的批次与来源链。图谱节点、关系不得脱离已登记知识来源写入。

create table knowledge_graph_import_batch (
  tenant_id uuid not null,
  graph_import_batch_id uuid not null,
  source_id uuid not null,
  source_document_version_id uuid,
  manifest_hash varchar(64) not null check (manifest_hash ~ '^[0-9a-f]{64}$'),
  imported_concept_count integer not null default 0 check (imported_concept_count >= 0),
  imported_relation_count integer not null default 0 check (imported_relation_count >= 0),
  status varchar(16) not null check (status in ('COMPLETED', 'REJECTED')),
  imported_by uuid not null,
  imported_at timestamptz not null default now(),
  primary key (tenant_id, graph_import_batch_id),
  foreign key (tenant_id, source_id) references knowledge_source_registry(tenant_id, source_id),
  foreign key (tenant_id, source_document_version_id)
    references knowledge_document_version(tenant_id, doc_version_id),
  foreign key (tenant_id, imported_by) references app_user(tenant_id, user_id)
);

alter table knowledge_concept
  add column source_graph_import_batch_id uuid,
  add constraint knowledge_concept_source_graph_import_fk
    foreign key (tenant_id, source_graph_import_batch_id)
    references knowledge_graph_import_batch(tenant_id, graph_import_batch_id);

alter table knowledge_relation
  add column source_graph_import_batch_id uuid,
  add constraint knowledge_relation_source_graph_import_fk
    foreign key (tenant_id, source_graph_import_batch_id)
    references knowledge_graph_import_batch(tenant_id, graph_import_batch_id);

create index knowledge_concept_source_graph_import_idx
  on knowledge_concept(tenant_id, source_graph_import_batch_id)
  where source_graph_import_batch_id is not null;

create index knowledge_relation_source_graph_import_idx
  on knowledge_relation(tenant_id, source_graph_import_batch_id)
  where source_graph_import_batch_id is not null;

create unique index knowledge_relation_semantic_uidx
  on knowledge_relation(tenant_id, from_concept, to_concept, rel_type, coalesce(version, ''));

create or replace function prevent_knowledge_graph_import_provenance_mutation() returns trigger language plpgsql as $$
begin
  if new.source_graph_import_batch_id is distinct from old.source_graph_import_batch_id then
    raise exception 'knowledge graph import provenance is immutable' using errcode = '23514';
  end if;
  return new;
end $$;

create trigger knowledge_concept_import_provenance_immutable
  before update of source_graph_import_batch_id on knowledge_concept
  for each row execute function prevent_knowledge_graph_import_provenance_mutation();

create trigger knowledge_relation_import_provenance_immutable
  before update of source_graph_import_batch_id on knowledge_relation
  for each row execute function prevent_knowledge_graph_import_provenance_mutation();
