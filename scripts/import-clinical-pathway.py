#!/usr/bin/env python3
"""把 Obsidian 临床路径数据（结构化版本 jsonl + 知识图谱 txt）导入 haoemr。
只读外部文件，不修改源数据。用法：python3 scripts/import-clinical-pathway.py
"""
import json, uuid, subprocess, sys, csv, io

OBS = "/Users/liuhaoxian/Downloads/我的/Obsidian/医学知识库/知识详情/05_知识图谱与临床路径"
F2 = f"{OBS}/205_临床路径_结构化版本_20250529/临床路径_结构化版本_20250529.jsonl"
F1 = f"{OBS}/204_临床路径_知识图谱_20250529/临床路径_知识图谱_20250529.txt"
TENANT = "018f0000-0000-7000-8000-00000000aa01"
ACTOR = "018f0000-0000-7000-8000-00000000aa04"
PSQL = ["/usr/local/opt/postgresql@18/bin/psql", "-X", "-h", "/private/tmp", "-p", "55432", "-d", "haoemr_dev"]

def u5(s):
    return str(uuid.uuid5(uuid.NAMESPACE_OID, s))

def esc(s):
    return (s or "").replace("\\", "\\\\").replace("\t", " ").replace("\n", " ").replace("\r", " ")[:500]

def q(s):
    return '"' + str(s).replace('"', '""') + '"'

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

def main():
    print("解析 F2 结构化路径…")
    pk, ver, stage, task, var, qua = load_pathways()
    print(f"  pathway={len(pk)} version={len(ver)} stage={len(stage)} task={len(task)} variance={len(var)} quality={len(qua)}")
    print("解析 F1 知识图谱…")
    concept, relation = load_graph()
    print(f"  concept={len(concept)} relation={len(relation)}")

    # 清空旧导入（仅本次注入的数据）
    subprocess.run(PSQL, input="delete from pathway_knowledge_task; delete from pathway_knowledge_variance; delete from pathway_knowledge_quality_point; delete from pathway_knowledge_stage; delete from pathway_knowledge_version; delete from pathway_knowledge; delete from knowledge_relation; delete from knowledge_concept;", text=True, capture_output=True)

    n = 0
    n += copy_into("pathway_knowledge", ["tenant_id","pathway_knowledge_id","pathway_code","display_name","specialty_code","diagnosis_code","status","created_by"], pk)
    n += copy_into("pathway_knowledge_version", ["tenant_id","pathway_version_id","pathway_knowledge_id","version_no","content_hash","status","submitted_by"], ver)
    n += copy_into("pathway_knowledge_stage", ["tenant_id","stage_id","pathway_version_id","stage_code","stage_name","sequence_no","expected_day_start","expected_day_end","stage_goal","assessment_points"], stage)
    n += copy_into("pathway_knowledge_task", ["tenant_id","task_id","stage_id","task_type","content","code_ref","required","sequence_no"], task)
    n += copy_into("pathway_knowledge_variance", ["tenant_id","variance_id","pathway_version_id","variance_type","trigger_condition","disposition","record_requirement"], var)
    n += copy_into("pathway_knowledge_quality_point", ["tenant_id","quality_point_id","pathway_version_id","indicator","standard","frequency"], qua)
    n += copy_into("knowledge_concept", ["tenant_id","concept_id","source_type","source_id","system","code","display"], concept)
    n += copy_into("knowledge_relation", ["tenant_id","relation_id","from_concept","to_concept","rel_type","version"], relation)
    print(f"导入完成，共 {n} 行")

if __name__ == "__main__":
    main()
