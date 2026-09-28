#!/usr/bin/env python3
"""把 Obsidian 临床路径数据（结构化版本 jsonl + 知识图谱 txt）导入 haoemr。
只读外部文件，不修改源数据。用法：python3 scripts/import-clinical-pathway.py
"""
import hashlib
import json
import uuid
import subprocess
import sys
import csv
import io

OBS = "/Users/liuhaoxian/Downloads/我的/Obsidian/医学知识库/知识详情/05_知识图谱与临床路径"
F2 = f"{OBS}/205_临床路径_结构化版本_20250529/临床路径_结构化版本_20250529.jsonl"
F1 = f"{OBS}/204_临床路径_知识图谱_20250529/临床路径_知识图谱_20250529.txt"
TENANT = "018f0000-0000-7000-8000-00000000aa01"
ACTOR = "018f0000-0000-7000-8000-00000000aa04"
REVIEWER = "018f0000-0000-7000-8000-00000000aa06"
APPROVER = "018f0000-0000-7000-8000-00000000aa14"
SOURCE_ID = "018f0000-0000-7000-8000-00000000d201"
IMPORT_BATCH_ID = "018f0000-0000-7000-8000-00000000d205"
DOCUMENT_ID = "018f0000-0000-7000-8000-00000000d202"
DOCUMENT_VERSION_ID = "018f0000-0000-7000-8000-00000000d203"
GRAPH_BATCH_ID = "018f0000-0000-7000-8000-00000000d204"
PSQL = ["/usr/local/opt/postgresql@18/bin/psql", "-X", "-h", "127.0.0.1", "-p", "55432", "-d", "haoemr_dev"]

def u5(s):
    return str(uuid.uuid5(uuid.NAMESPACE_OID, s))

def esc(s):
    return (s or "").replace("\\", "\\\\").replace("\t", " ").replace("\n", " ").replace("\r", " ")[:500]

def q(s):
    return '"' + str(s).replace('"', '""') + '"'

def sha256_file(path):
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()

def copy_into(table, cols, rows):
    """通过 COPY FROM STDIN 导入。"""
    if not rows:
        return 0
    buf = io.StringIO()
    w = csv.writer(buf, delimiter="\t", lineterminator="\n")
    for r in rows:
        w.writerow(r)
    data = buf.getvalue()
    sql = f"COPY {table} ({','.join(cols)}) FROM STDIN WITH (FORMAT text, NULL '\\N')"
    p = subprocess.run(PSQL + ["-c", sql], input=data, text=True, capture_output=True)
    if p.returncode != 0:
        print(f"[ERROR] copy {table}: {p.stderr[:300]}", file=sys.stderr)
        return 0
    return len(rows)

def load_pathways():
    """F2 → pathway_knowledge 系列。"""
    pk, ver, stage, task, var, qua = [], [], [], [], [], []
    seen_kids = set()
    with open(F2, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            d = json.loads(line)
            title = d.get("clinical_pathway_title", "").strip()
            if not title:
                continue
            kid = u5("cp:" + title)
            if kid in seen_kids:
                continue
            seen_kids.add(kid)
            code = d.get("clinical_pathway_id") or d.get("group_id")
            keywords = d.get("keywords") or []
            diagnosis = next((k["keyword"] for k in keywords if k.get("keyword_type") == "疾病症状"), "TBD")
            specialty = next((k["keyword"] for k in keywords if k.get("keyword_type") == "科室"), "GENERAL")
            pk.append([TENANT, kid, code, title, specialty, diagnosis, "ACTIVE", ACTOR])
            vid = u5("cp-version:" + title)
            ver.append([TENANT, vid, kid, 1, "imported", "DRAFT", ACTOR])
            seq_stage = 1
            for section in d.get("sections", []):
                for sub in section.get("subsections", []):
                    sub_title = sub.get("subsection_title", "").strip()
                    if not sub_title:
                        continue
                    sid = u5(f"cp-stage:{title}:{seq_stage}")
                    stage.append([TENANT, sid, vid, f"S{seq_stage}", sub_title, seq_stage, 1, 1, "", ""])
                    seq_stage += 1
                    contents = sub.get("content", [])
                    ttype = task_type(sub_title)
                    for j, c in enumerate(contents):
                        c = str(c).strip()
                        if not c:
                            continue
                        if ttype == "VARIANCE":
                            var.append([TENANT, u5(f"cp-var:{title}:{j}"), vid, sub_title, c, c, ""])
                        elif ttype == "QUALITY":
                            qua.append([TENANT, u5(f"cp-qua:{title}:{j}"), vid, sub_title, c, ""])
                        else:
                            task.append([TENANT, u5(f"cp-task:{title}:{seq_stage-1}:{j}"), sid, ttype, esc(c), None, "true", j + 1])
    return pk, ver, stage, task, var, qua

def task_type(sub_title):
    if "变异" in sub_title:
        return "VARIANCE"
    if "出院标准" in sub_title:
        return "QUALITY"
    if "检查" in sub_title:
        return "IMAGING"
    if any(k in sub_title for k in ["治疗", "方案", "用药", "药物", "化疗"]):
        return "MEDICATION"
    if "护理" in sub_title:
        return "NURSING"
    return "ASSESSMENT"

def load_graph():
    """F1 → knowledge_concept + knowledge_relation。"""
    concept, relation = [], []
    seen = {}
    seen_rels = set()
    with open(F1, encoding="utf-8") as f:
        r = csv.reader(f, delimiter="\t")
        next(r, None)
        for row in r:
            if len(row) < 8:
                continue
            entity_id, entity, etype, prop, value_id, value, vtype, group, source = [x.strip().strip('"') for x in row[:9]]
            if not entity or not value:
                continue
            def concept_row(node_id, display, ctype):
                if node_id not in seen:
                    seen[node_id] = u5("cp-concept:" + node_id)
                    concept.append([TENANT, seen[node_id], "EXTRACTED", node_id, ctype or "OTHER", node_id, esc(display)])
                return seen[node_id]
            c_from = concept_row(entity_id, entity, etype)
            c_to = concept_row(value_id, value, vtype)
            rkey = (c_from, c_to, prop)
            if rkey not in seen_rels:
                seen_rels.add(rkey)
                relation.append([TENANT, u5(f"cp-rel:{entity_id}:{value_id}:{prop}"), c_from, c_to, "MENTIONS", prop])
    return concept, relation

def reset_tenant_knowledge():
    sql = f"""
    delete from knowledge_feedback where tenant_id = '{TENANT}'::uuid;
    delete from knowledge_retrieval_log where tenant_id = '{TENANT}'::uuid;
    delete from knowledge_relation where tenant_id = '{TENANT}'::uuid;
    delete from knowledge_concept where tenant_id = '{TENANT}'::uuid;
    delete from knowledge_graph_import_batch where tenant_id = '{TENANT}'::uuid;
    delete from knowledge_chunk where tenant_id = '{TENANT}'::uuid;
    delete from knowledge_document_version where tenant_id = '{TENANT}'::uuid;
    delete from knowledge_document where tenant_id = '{TENANT}'::uuid;
    delete from knowledge_source_file where tenant_id = '{TENANT}'::uuid;
    delete from knowledge_import_batch where tenant_id = '{TENANT}'::uuid;
    delete from knowledge_source_registry where tenant_id = '{TENANT}'::uuid;
    delete from pathway_knowledge_task where tenant_id = '{TENANT}'::uuid;
    delete from pathway_knowledge_variance where tenant_id = '{TENANT}'::uuid;
    delete from pathway_knowledge_quality_point where tenant_id = '{TENANT}'::uuid;
    delete from pathway_knowledge_stage where tenant_id = '{TENANT}'::uuid;
    delete from pathway_knowledge_version where tenant_id = '{TENANT}'::uuid;
    delete from pathway_knowledge where tenant_id = '{TENANT}'::uuid;
    """
    subprocess.run(PSQL + ["-c", sql], check=True)

def register_knowledge_source(concept_count, relation_count):
    pathway_hash = sha256_file(F2)
    graph_hash = sha256_file(F1)
    manifest_hash = hashlib.sha256(f"{F1}|{graph_hash}\n{F2}|{pathway_hash}".encode()).hexdigest()
    sql = f"""
    insert into knowledge_source_registry(
      tenant_id, source_id, source_code, source_name, source_kind, source_path,
      license, allowed_use, sensitivity, update_frequency, checksum, status)
    values ('{TENANT}'::uuid, '{SOURCE_ID}'::uuid, 'OBSIDIAN_CLINICAL_KNOWLEDGE',
      'Obsidian 医学知识库', 'OBSIDIAN_VAULT', '{OBS}', '内部授权资料',
      '仅读导入临床路径与临床知识图谱', 'INTERNAL', 'ON_DEMAND', '{manifest_hash}', 'ACTIVE');
    insert into knowledge_import_batch(
      tenant_id, batch_id, source_id, source_root, selection_matrix_version,
      source_manifest_hash, mode, imported_row_count, skipped_row_count, status, operator)
    values ('{TENANT}'::uuid, '{IMPORT_BATCH_ID}'::uuid, '{SOURCE_ID}'::uuid, '{OBS}',
      'clinical-pathway-v2', '{manifest_hash}', 'READ_ONLY', 2, 0, 'COMPLETED', '{ACTOR}'::uuid);
    insert into knowledge_source_file(
      tenant_id, file_id, batch_id, source_path, source_content_hash,
      entity_category, system, table_name, included)
    values
      ('{TENANT}'::uuid, '{u5("source-file:pathway")}'::uuid, '{IMPORT_BATCH_ID}'::uuid,
        '{F2}', '{pathway_hash}', '临床路径', 'PATHWAY', 'pathway_knowledge', true),
      ('{TENANT}'::uuid, '{u5("source-file:graph")}'::uuid, '{IMPORT_BATCH_ID}'::uuid,
        '{F1}', '{graph_hash}', '临床路径', 'GRAPH', 'knowledge_concept', true);
    insert into knowledge_document(
      tenant_id, document_id, document_code, content_type, title,
      source_authority, license, classification)
    values ('{TENANT}'::uuid, '{DOCUMENT_ID}'::uuid, 'OBSIDIAN_CLINICAL_PATHWAY_GRAPH',
      'GRAPH_ENTITY', 'Obsidian 临床路径与知识图谱', '本地 Obsidian 医学知识库',
      '内部授权资料', 'INTERNAL');
    insert into knowledge_document_version(
      tenant_id, doc_version_id, document_id, version, content_hash, markdown,
      metadata, status, effective_from, published_by)
    values ('{TENANT}'::uuid, '{DOCUMENT_VERSION_ID}'::uuid, '{DOCUMENT_ID}'::uuid,
      '20250529', '{manifest_hash}', '临床路径与知识图谱源数据导入版本。',
      jsonb_build_object('source_manifest_hash', '{manifest_hash}', 'pathway_count', {concept_count},
        'relation_count', {relation_count}), 'ACTIVE', now(), '{ACTOR}'::uuid);
    insert into knowledge_chunk(
      tenant_id, chunk_id, doc_version_id, section_path, section_title, text,
      token_count, language, clinical_tags, content_hash, source_locator)
    values ('{TENANT}'::uuid, '{u5("source-chunk:clinical-pathway")}'::uuid, '{DOCUMENT_VERSION_ID}'::uuid,
      'clinical-pathway-graph', '临床路径与知识图谱源数据',
      '该版本来自 Obsidian 医学知识库的临床路径结构化数据和知识图谱数据。',
      64, 'zh-CN', '["临床路径", "知识图谱"]'::jsonb, '{manifest_hash}',
      jsonb_build_object('source_path', '{OBS}'));
    insert into knowledge_graph_import_batch(
      tenant_id, graph_import_batch_id, source_id, source_document_version_id,
      manifest_hash, imported_concept_count, imported_relation_count, status, imported_by)
    values ('{TENANT}'::uuid, '{GRAPH_BATCH_ID}'::uuid, '{SOURCE_ID}'::uuid,
      '{DOCUMENT_VERSION_ID}'::uuid, '{manifest_hash}', 0, 0, 'COMPLETED', '{ACTOR}'::uuid);
    """
    subprocess.run(PSQL + ["-c", sql], check=True)

def publish_imported_pathways():
    sql = f"""
    update pathway_knowledge_version
    set status = 'ACTIVE', reviewed_by = '{REVIEWER}'::uuid, reviewed_at = now(),
      approved_by = '{APPROVER}'::uuid, approved_at = now(), published_at = now()
    where tenant_id = '{TENANT}'::uuid and content_hash = 'imported';
    """
    subprocess.run(PSQL + ["-c", sql], check=True)

def update_graph_batch_counts(concept_count, relation_count):
    sql = f"""
    update knowledge_graph_import_batch
    set imported_concept_count = {concept_count}, imported_relation_count = {relation_count}
    where tenant_id = '{TENANT}'::uuid and graph_import_batch_id = '{GRAPH_BATCH_ID}'::uuid;
    """
    subprocess.run(PSQL + ["-c", sql], check=True)

def main():
    print("解析 F2 结构化路径…")
    pk, ver, stage, task, var, qua = load_pathways()
    print(f"  pathway={len(pk)} version={len(ver)} stage={len(stage)} task={len(task)} variance={len(var)} quality={len(qua)}")
    print("解析 F1 知识图谱…")
    concept, relation = load_graph()
    print(f"  concept={len(concept)} relation={len(relation)}")

    reset_tenant_knowledge()
    register_knowledge_source(len(concept), len(relation))

    n = 0
    n += copy_into("pathway_knowledge", ["tenant_id","pathway_knowledge_id","pathway_code","display_name","specialty_code","diagnosis_code","status","created_by"], pk)
    n += copy_into("pathway_knowledge_version", ["tenant_id","pathway_version_id","pathway_knowledge_id","version_no","content_hash","status","submitted_by"], ver)
    n += copy_into("pathway_knowledge_stage", ["tenant_id","stage_id","pathway_version_id","stage_code","stage_name","sequence_no","expected_day_start","expected_day_end","stage_goal","assessment_points"], stage)
    n += copy_into("pathway_knowledge_task", ["tenant_id","task_id","stage_id","task_type","content","code_ref","required","sequence_no"], task)
    n += copy_into("pathway_knowledge_variance", ["tenant_id","variance_id","pathway_version_id","variance_type","trigger_condition","disposition","record_requirement"], var)
    n += copy_into("pathway_knowledge_quality_point", ["tenant_id","quality_point_id","pathway_version_id","indicator","standard","frequency"], qua)
    concept = [row + [GRAPH_BATCH_ID] for row in concept]
    relation = [row + [GRAPH_BATCH_ID] for row in relation]
    n += copy_into("knowledge_concept", ["tenant_id","concept_id","source_type","source_id","system","code","display","source_graph_import_batch_id"], concept)
    n += copy_into("knowledge_relation", ["tenant_id","relation_id","from_concept","to_concept","rel_type","version","source_graph_import_batch_id"], relation)
    publish_imported_pathways()
    update_graph_batch_counts(len(concept), len(relation))
    print(f"导入完成，共 {n} 行")

if __name__ == "__main__":
    main()
