"""把知识块导出为向量库可导入的格式。

背景
----
本项目的知识库在 MySQL，但要接向量检索（PGvector / Milvus / Qdrant 等）
时需要一份干净的导出文件：每行一条独立、可直接批量 embedding 的文本。

导出内容刻意做三件事，让灌库后能直接用：
1. **带 product_id** —— 命中后能定位是哪个商品、拼回详情页链接
2. **带 chunk_type 与 weight** —— 检索时可按类型路由、按权重排序
3. **metadata 单独成列** —— 多数向量库支持 metadata 过滤，
   灌库时按列映射即可，不必再解析文本

用法
----
    python scripts/export_kb_for_vector.py                # 导出 JSONL
    python scripts/export_kb_for_vector.py --format json   # 导出 JSON 数组
    python scripts/export_kb_for_vector.py --out E:/kb.jsonl

分块说明
--------
chunk 长度控制在 80~200 字。这个区间是权衡的结果：
- 太短（如 30 字）→ embedding 语义不完整，检索召回不准
- 太长（如 800 字）→ 一个向量承载多个主题，被平均掉，precision 下降
现在的分块是一块一个语义自足的事实（价格/规格/场景/售后/库存），
天然落在这个区间。
"""

import argparse
import io
import json
import os
import subprocess
import sys
import tempfile
import uuid

MYSQL = r"C:\Program Files\MySQL\MySQL Server 5.7\bin\mysql.exe"
DB = "ecommerce"


def run_query(stmt):
    """执行查询并解析成 dict 列表。

    用 --batch 模式输出，避免 mysql 的表格边框干扰解析。
    """
    f = os.path.join(tempfile.gettempdir(), f"kbexp_{uuid.uuid4().hex[:8]}.tsv")
    with io.open(f, "w", encoding="utf-8") as fh:
        # 必须先 USE 库：走 stdin 导入时没有 -e 参数的默认库上下文
        fh.write(f"USE {DB};\n")
        fh.write(stmt)
    p = subprocess.run(
        [MYSQL, "-uroot", "-p123456", "--default-character-set=utf8mb4",
         "--batch", "--raw"],
        stdin=io.open(f, "rb"), capture_output=True)
    os.remove(f)
    text = p.stdout.decode("utf-8", errors="replace")

    # 密码警告会混进 stdout（"mysql: [Warning] Using a password..."），
    # 必须按行过滤掉，否则第一行被当成表头，整个解析全错。
    # mysql --batch 用 CRLF 换行，必须先去掉 \r，
    # 否则表头会变成 "c.id"，split 后拿到错的键名
    lines = [l.rstrip("\r") for l in text.split("\n")
             if l.strip() and not l.startswith("mysql: [Warning]")]
    if not lines:
        err = p.stderr.decode("utf-8", errors="replace")
        if "ERROR" in err:
            print("  [SQL 错误]", err[:200])
        return []
    header = [h.strip().lower() for h in lines[0].split("\t")]
    out = []
    for line in lines[1:]:
        parts = line.split("\t")
        if len(parts) < len(header):
            continue
        rec = {}
        for k, v in zip(header, parts):
            # 去掉列名里的表前缀：c.id -> id
            rec[k.split(".")[-1]] = v
        out.append(rec)
    return out


def to_int(v):
    """把 batch 模式的字符串转成 int。

    mysql --batch --raw 会把 SQL NULL 输出成字面 "NULL" 字符串，
    直接 int("NULL") 会抛 ValueError，必须先判断。
    """
    if v is None:
        return None
    t = str(v).strip()
    if t == "" or t.upper() == "NULL":
        return None
    try:
        return int(t)
    except ValueError:
        return None


def build():
    print("读取知识块...")
    rows = run_query(
        "SELECT c.id, c.product_id, c.category_id, c.chunk_type, c.title, "
        "       c.content, c.keywords, c.weight, p.name AS product_name "
        "FROM t_kb_product_chunk c "
        "LEFT JOIN t_product p ON p.id = c.product_id "
        "WHERE c.status = 1 ORDER BY c.product_id, c.id;"
    )
    print(f"  知识块 {len(rows)} 条")

    print("读取问答对...")
    faqs = run_query(
        "SELECT id, product_id, category_id, question, answer, intent, priority "
        "FROM t_kb_faq WHERE status = 1 ORDER BY priority, id;"
    )
    print(f"  问答对 {len(faqs)} 条")

    records = []
    for r in rows:
        # 供 embedding 的纯文本：标题 + 正文 + 关键词
        # 关键词重复一次是刻意的 —— 它是客服问法的高频词，
        # 出现在 embedding 输入里能提升这类问法的召回率
        text = f"{r['title']}\n{r['content']}\n关键词：{r['keywords']}"
        records.append({
            "id": f"chunk_{r['id']}",
            "source": "product_chunk",
            "text": text,
            "metadata": {
                "product_id": to_int(r["product_id"]),
                "product_name": r["product_name"],
                "category_id": to_int(r["category_id"]),
                "chunk_type": r["chunk_type"],
                "title": r["title"],
                "keywords": r["keywords"],
                "weight": to_int(r["weight"]) or 1,
            },
        })

    for r in faqs:
        text = f"{r['question']}\n{r['answer']}"
        records.append({
            "id": f"faq_{r['id']}",
            "source": "faq",
            "text": text,
            "metadata": {
                "product_id": to_int(r["product_id"]),
                "category_id": to_int(r["category_id"]),
                "intent": r["intent"],
                "priority": to_int(r["priority"]) or 3,
                "question": r["question"],
            },
        })

    return records


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="E:/WorkBuddy/Temp/kb_vector_export.jsonl")
    ap.add_argument("--format", choices=["jsonl", "json"], default="jsonl")
    args = ap.parse_args()

    print("=" * 60)
    print("导出知识库（供向量检索使用）")
    print("=" * 60)

    records = build()
    if not records:
        print("没有数据可导出")
        return 1

    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with io.open(args.out, "w", encoding="utf-8") as fh:
        if args.format == "jsonl":
            for r in records:
                fh.write(json.dumps(r, ensure_ascii=False) + "\n")
        else:
            fh.write(json.dumps(records, ensure_ascii=False, indent=2))

    size = os.path.getsize(args.out)
    print(f"\n导出完成: {args.out}")
    print(f"  记录数: {len(records)}")
    print(f"  文件大小: {size / 1024:.1f} KB")

    # 统计：确认每条都能独立 embedding
    no_text = [r for r in records if not r["text"].strip()]
    lens = [len(r["text"]) for r in records]
    print(f"\n  空文本记录: {len(no_text)}")
    print(f"  文本长度: 最短 {min(lens)} / 平均 {sum(lens)//len(lens)} / 最长 {max(lens)}")
    print(f"  带 product_id 的记录: {sum(1 for r in records if r['metadata'].get('product_id'))}")

    print("\n样例记录（可直接用于灌库）:")
    print(json.dumps(records[0], ensure_ascii=False, indent=2)[:600])

    print("\n" + "=" * 60)
    print("提示：每条记录可直接 embedding，无需再预处理。")
    print("metadata 建议映射为向量库的 metadata 字段，")
    print("检索时可按 category_id / chunk_type / weight 过滤与排序。")
    print("=" * 60)
    return 0


if __name__ == "__main__":
    sys.exit(main())
