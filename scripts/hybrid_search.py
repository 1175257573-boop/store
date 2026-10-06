"""混合检索：BM25 ⊕ 向量，用 RRF 融合。

为什么必须用 RRF 而不是加权求和
--------------------------------
BM25 分数是无界的（同一查询可能 5.2 也可能 12.8），
余弦相似度是 -1~1。两者量纲完全不同，
直接加权求和会被 BM25 完全主导，融合退化成纯 BM25。

归一化后加权也不行：分数分布随查询变化，
同一组权重对不同查询的实际效果不一致。

RRF 只看「名次」，天然规避量纲问题，且无需调参。

k=60 的来历
----------
Cormack 等人的原论文默认值。别随手改成 10 或 100：
k 越小越强调头部排名，大幅偏离 60 会让融合结果失真。

架构：三路而非两路
------------------
本项目除了 BM25 和向量，还有一路是结构化属性查询：
用户问「5000mAh 以上」时，走 RRF 会把 4000mAh 的也召回
（语义上"差不多"但数值上不满足），融合救不了，必须精确查。

    意图识别
       ├─ 价格/库存/规格类 → 结构化查库（不参与 RRF）
       └─ 其他 → RRF（BM25 ⊕ 向量）→ rerank → top 3
"""

import io
import json
import os
import subprocess
import sys
import tempfile
import uuid
from collections import defaultdict

MYSQL = r"C:\Program Files\MySQL\MySQL Server 5.7\bin\mysql.exe"
DB = "ecommerce"
RRF_K = 60
CANDIDATE_SIZE = 50      # 两路各取多少候选再融合


# ============================================================
# MySQL 侧：BM25 字面检索（ngram 全文索引）
# ============================================================
def mysql_query(sql_stmt, **params):
    """执行 MySQL 查询，返回 dict 列表。

    用 --batch 输出便于解析，注意三个坑：
      1. 走 stdin 不会自动选库，必须显式 USE
      2. 密码警告混进 stdout，会被当表头
      3. --batch 用 CRLF，不 rstrip 会让表头变成 c.id

    参数用 {name} 占位符 + str.replace 传参，**不要用 % 格式化**：
    BM25 的 AGAINST('{q}' IN BOOLEAN MODE) 与 LIKE CONCAT('%', ...) 里都有 %，
    走 % 格式化会把它们当占位符，SQL 直接语法错。
    """
    safe = sql_stmt
    for k, v in params.items():
        val = str(v)
        if k == "q":
            # BM25 关键词：用白名单而不是转义。
            # 转义（把 \' 还原成 '）治标不治本 —— 只要模板里少一个引号，
            # 或者有人改成 NATURAL LANGUAGE MODE，就又出问题了。
            # 白名单从入口杜绝注入：ngram 只需要汉字、字母、数字、空格。
            val = "".join(ch for ch in val
                          if ch.isalnum() or ch.isspace() or "\u4e00" <= ch <= "\u9fff")
            val = val.strip() or "商品"
        else:
            val = val.replace("'", "''")
        safe = safe.replace("{" + k + "}", val)
    f = os.path.join(tempfile.gettempdir(), f"rrf_{uuid.uuid4().hex[:8]}.tsv")
    with io.open(f, "w", encoding="utf-8") as fh:
        fh.write(f"USE {DB};\n{safe}\n")
    p = subprocess.run(
        [MYSQL, "-uroot", "-p123456", "--default-character-set=utf8mb4",
         "--batch", "--raw"],
        stdin=io.open(f, "rb"), capture_output=True)
    os.remove(f)

    text = p.stdout.decode("utf-8", errors="replace")
    lines = [l.rstrip("\r") for l in text.split("\n")
             if l.strip() and not l.startswith("mysql: [Warning]")]
    if len(lines) < 2:
        err = p.stderr.decode("utf-8", errors="replace")
        if "ERROR" in err:
            print("  [MySQL 错误]", err[:200])
        return []

    header = [h.split(".")[-1].lower() for h in lines[0].split("\t")]
    out = []
    for line in lines[1:]:
        parts = line.split("\t")
        if len(parts) < len(header):
            continue
        out.append(dict(zip(header, parts)))
    return out


# 停用词与语气词：ngram 检索前必须剥离。
#
# 为什么必须剥离：MySQL ngram 全文索引在 BOOLEAN MODE 下默认是 AND，
# 查询里的每个 2-gram 都必须命中。实测「支持七天无理由退货吗」中，
# 「货吗」这个 gram 在全部 500 个知识块里出现 0 次 —— 整条查询直接被否掉。
#
# 「吗」「呢」「的」「了」这类词不携带检索意图，却会生成致命的不存在 gram。
STOPWORDS = ["吗", "呢", "吧", "啊", "呀", "哦", "的", "了", "是", "我", "你",
             "他", "她", "它", "这", "那", "有", "在", "会", "能", "要", "想",
             "请问", "请", "一下", "怎么样", "如何", "什么", "为什么", "多少"]


def extract_keywords(query):
    """从自然语言查询里提取 BM25 检索用的关键词。

    BM25 擅长「明确的关键词」，不擅长「自然的问句」。
    这里做两件事：
      1. 剥离停用词与语气词（否则产生不存在的高频 gram）
      2. 提取 2-4 字的核心词组（ngram 索引的最小匹配单位就是 2-gram）
    """
    # 短查询（≤4 字）本身就是完整关键词，不做剥离。
    # 「多少钱」「有货吗」「退货」剥掉停用词后就空了 ——
    # 这里的「多少」「有」不是噪音，是检索意图本身。
    if len(query.strip()) <= 4:
        return [query.strip()]

    q = query
    for sw in STOPWORDS:
        q = q.replace(sw, " ")

    # 切成词组：连续汉字按 2-4 字滑窗切，数字与字母整体保留
    import re
    tokens = []
    for m in re.finditer(r"[\u4e00-\u9fff]{2,}|[A-Za-z0-9]+", q):
        seg = m.group()
        if seg.isascii():
            tokens.append(seg)
        else:
            # 中文按 2-4 字滑窗，2 字起步（与 ngram 索引单位一致）
            for size in (4, 3, 2):
                if len(seg) >= size:
                    for i in range(len(seg) - size + 1):
                        tokens.append(seg[i:i + size])
    return tokens[:12]   # 最多 12 个词，太多会稀释相关性


def bm25_search(query, limit=CANDIDATE_SIZE):
    """BM25 字面检索。

    ngram 是 2-gram 切分，能命中中文，但有两个硬限制：
      1. BOOLEAN MODE 默认 AND，查询里有任一 gram 不存在就整条不匹配
         -> 必须先用 extract_keywords 剥离停用词
      2. 不做同义扩展，「续航」查不到「电池容量」
         -> 这正是必须再有向量那一路的原因
    """
    kws = extract_keywords(query)
    if not kws:
        return []
    # 多词用 OR 连接：任一词命中即可，避免「必须全部命中」的严苛条件
    expr = " OR ".join(kws)
    rows = mysql_query(
        "SELECT c.id AS chunk_id, c.product_id, c.chunk_type, c.title, "
        "       c.content, c.weight, "
        "       MATCH(c.title, c.content, c.keywords) AGAINST('{q}' IN BOOLEAN MODE) AS score "
        "FROM t_kb_product_chunk c "
        "WHERE MATCH(c.title, c.content, c.keywords) AGAINST('{q}' IN BOOLEAN MODE) "
        "ORDER BY score DESC LIMIT " + str(int(limit)) + ";",
        q=expr)
    # ID 必须带 chunk_ 前缀，与 export_kb_for_vector.py 的产出保持一致。
    # 不一致的后果：融合时与向量库/meta 对不上，
    # 单路对比测试会把相关项全判为不相关（曾导致误判「融合比纯向量差」）。
    return [{"id": "chunk_" + r["chunk_id"], "source": "product_chunk",
             "product_id": r["product_id"], "chunk_type": r["chunk_type"],
             "title": r["title"], "content": r["content"],
             "weight": int(r["weight"] or 1),
             "bm25_score": float(r["score"])} for r in rows]


def faq_search(query, limit=CANDIDATE_SIZE):
    """问答对的 BM25 检索。

    意图识别已经跑过 FAQ 精确匹配，这一路是补充：
    用户问法与标准问法不完全一致时，靠 BM25 模糊命中。
    """
    kws = extract_keywords(query)
    if not kws:
        return []
    expr = " OR ".join(kws)
    rows = mysql_query(
        "SELECT id AS faq_id, product_id, intent, question, answer, priority, "
        "       MATCH(question, question_kw, answer) AGAINST('{q}' IN BOOLEAN MODE) AS score "
        "FROM t_kb_faq "
        "WHERE MATCH(question, question_kw, answer) AGAINST('{q}' IN BOOLEAN MODE) "
        "ORDER BY score DESC LIMIT " + str(int(limit)) + ";",
        q=expr)
    return [{"id": "faq_" + r["faq_id"], "source": "faq",
             "product_id": r["product_id"], "intent": r["intent"],
             "title": r["question"], "content": r["answer"],
             "weight": 6 - int(r["priority"] or 3),   # priority 1 最高 → weight 5
             "bm25_score": float(r["score"])} for r in rows]


# ============================================================
# RRF 融合
# ============================================================
def rrf_fusion(rank_lists, k=RRF_K, top_n=10, weight_map=None):
    """Reciprocal Rank Fusion。

    Args:
        rank_lists: [[id, ...], ...] 多个有序 ID 列表（按相关度降序）
        k: 平滑常数，60 是原论文默认值，不要随意改
        top_n: 返回条数
        weight_map: {列表下标: 权重倍数}，用于给不同来源不同话语权

    Returns:
        [(id, fused_score, 各路排名信息), ...] 按融合分降序
    """
    scores = defaultdict(float)
    appearances = defaultdict(list)

    for i, rlist in enumerate(rank_lists):
        mult = (weight_map or {}).get(i, 1.0)
        for rank, doc_id in enumerate(rlist, start=1):
            scores[doc_id] += mult * 1.0 / (k + rank)
            appearances[doc_id].append({
                "source_idx": i, "rank": rank, "weighted": mult})

    fused = sorted(scores.items(), key=lambda x: -x[1])[:top_n]
    return [(doc_id, round(score, 6), appearances[doc_id]) for doc_id, score in fused]


def merge_results(bm25_hits, vector_hits, k=RRF_K, top_n=10):
    """两路结果融合。

    权重设计：FAQ 略高于知识块。
    FAQ 是人工确认过的标准答案，命中它比命中一个知识块更可靠。
    """
    # 统一去重后的候选池：同一 id 在两路都出现是好事，说明双路都认可
    bm25_ids = [h["id"] for h in bm25_hits]
    vector_ids = [h["id"] for h in vector_hits]

    fused = rrf_fusion([bm25_ids, vector_ids], k=k, top_n=top_n,
                       weight_map={0: 1.0, 1: 1.0})

    # 回填完整信息：两路可能有不同字段
    info = {}
    for h in bm25_hits:
        info[h["id"]] = dict(h)
    for h in vector_hits:
        if h["id"] in info:
            info[h["id"]]["vector_score"] = h.get("vector_score")
        else:
            info[h["id"]] = dict(h)

    results = []
    for doc_id, score, where in fused:
        r = info.get(doc_id, {"id": doc_id})
        r["fused_score"] = score
        r["matched_by"] = "both" if len(where) > 1 else \
                          ("bm25" if where[0]["source_idx"] == 0 else "vector")
        results.append(r)
    return results


# ============================================================
# 结构化检索（第三路，不参与 RRF）
# ============================================================
def structured_search(query):
    """结构化属性检索 —— 处理「5000mAh 以上」这类数值约束。

    为什么要单独一路：RRF 救不了数值比较。
    4000mAh 与 5000mAh 在语义上"差不多"，融合后都会被召回，
    但用户要的是「≥5000」，必须靠 value_num 精确过滤。
    """
    hits = []

    # 数值区间：问「5000mAh 以上」「多少英寸」
    import re
    num = re.search(r"(\d+(?:\.\d+)?)\s*(mAh|英寸|W|GB|mm|g|小时|L)", query)
    if num:
        val, unit = float(num.group(1)), num.group(2)
        key_map = {"mAh": "battery", "英寸": "screen_size", "W": "charging",
                   "GB": "ram", "mm": "thickness", "g": "weight",
                   "小时": "battery_life", "L": "capacity"}
        key = key_map.get(unit)
        if key:
            rows = mysql_query(
                "SELECT a.product_id, p.name, a.attr_value, a.value_num, a.unit "
                "FROM t_product_attr a JOIN t_product p ON p.id = a.product_id "
                "WHERE a.attr_key = '" + key + "' AND a.value_num >= " + str(val) + " "
                "ORDER BY a.value_num DESC LIMIT 10;")
            for r in rows:
                hits.append({
                    "type": "structured_range", "product_id": r["product_id"],
                    "product_name": r["name"], "attr": key,
                    "value": r["attr_value"], "unit": r["unit"],
                    "detail": f"{r['name']} 的{key}为 {r['attr_value']}"
                              f"{r['unit']}（≥{val}）",
                })

    return hits


# ============================================================
# 演示：用真实问题验证
# ============================================================
def demo():
    print("=" * 70)
    print("混合检索演示（BM25 ⊕ 向量，RRF 融合）")
    print("=" * 70)

    vector_hits = []
    if os.path.exists("E:/WorkBuddy/Temp/vec_sim_results.json"):
        with io.open("E:/WorkBuddy/Temp/vec_sim_results.json", encoding="utf-8") as f:
            vector_hits = json.load(f)
        print(f"已加载向量侧候选 {len(vector_hits)} 条")

    queries = [
        "这个手机续航怎么样",
        "多少钱",
        "有货吗",
        "支持七天无理由退货吗",
        "适合学生用吗",
        "怎么申请发票",
        "什么时候发货",
    ]

    for q in queries:
        print(f"\n{'─' * 66}")
        print(f"【问】{q}")

        # 意图识别（规则版，L1）
        intent = guess_intent(q)
        print(f"  意图: {intent}")

        bm = bm25_search(q) + faq_search(q)
        print(f"  BM25 命中: {len(bm)} 条")
        for h in bm[:3]:
            print(f"    [{h['source'][:6]}] {h['title'][:34]}  "
                  f"score={h['bm25_score']:.3f}")

        if vector_hits:
            merged = merge_results(bm, vector_hits, top_n=5)
            print(f"  RRF 融合后 Top5:")
            for m in merged:
                print(f"    {m['fused_score']:.5f} [{m['matched_by']:6s}] "
                      f"{m['title'][:32]}")

        if intent in ("price", "stock", "spec"):
            sh = structured_search(q)
            if sh:
                print(f"  结构化命中 {len(sh)} 条")
                for s in sh[:2]:
                    print(f"    {s['detail']}")


INTENT_RULES = [
    ("price", ["多少钱", "价格", "报价", "几块", "贵不贵", "便宜"]),
    ("stock", ["有货", "库存", "现货", "还有货", "什么时候发货", "多久发货"]),
    ("spec", ["参数", "配置", "规格", "尺寸", "多大", "多少毫安", "续航",
              "防水", "什么材质", "适合"]),
    ("after_sale", ["退货", "退款", "换货", "保修", "坏了", "质量", "售后"]),
    ("logistics", ["运费", "快递", "物流", "包邮", "发什么"]),
    ("payment", ["支付", "付款", "发票", "开票"]),
]


def guess_intent(query):
    """规则版意图识别（L1 层）。

    这不是最终方案 —— 没有标注数据时先用规则兜住高频问法。
    覆盖率有限但延迟 <1ms、成本为 0，是 L1 该做的事。
    """
    for intent, kws in INTENT_RULES:
        for kw in kws:
            if kw in query:
                return intent
    return "unknown"


if __name__ == "__main__":
    demo()
