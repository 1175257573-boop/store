"""知识库检索验证：模拟客服的三条检索路径。

客服实际会用到的不是单一检索，而是按问题类型分流：
  价格/库存/规格  → 精确查库（结构化），不能靠语义猜
  售后/物流/适配  → BM25 全文（关键词明确）
  模糊问法        → 向量语义（这里是数据准备，检索侧另测）

本脚本验证前两条 —— 它们能落地才算知识库真的可用。
向量侧只检查数据形态（块长度、语义自足性），
因为向量化需要 embedding 模型，不在本脚本能力范围内。
"""

import io
import os
import subprocess
import sys

MYSQL = r"C:\Program Files\MySQL\MySQL Server 5.7\bin\mysql.exe"
DB = "ecommerce"

PASS, FAIL = 0, 0


def sql(stmt):
    p = subprocess.run(
        [MYSQL, "-uroot", "-p123456", "--default-character-set=utf8mb4",
         "-e", f"USE {DB}; {stmt}"],
        capture_output=True)
    return p.stdout.decode("utf-8", errors="replace")


def rows(stmt):
    """取结果行，跳过表头与 mysql 的密码警告行。"""
    out = []
    for line in sql(stmt).split("\n"):
        t = line.strip()
        if not t or "Warning" in line:
            continue
        out.append(t)
    return out[1:] if out else []


def check(name, cond, detail=""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  [PASS] {name}")
    else:
        FAIL += 1
        print(f"  [FAIL] {name}  {detail}")


def main():
    print("=" * 66)
    print("知识库检索验证（智能客服的三条路径）")
    print("=" * 66)

    # ------------------------------------------------------------
    print("\n【1】精确检索：价格与库存（不能靠语义，必须查库）")
    # ------------------------------------------------------------
    r = rows("SELECT MIN(CAST(price AS CHAR)), MAX(CAST(price AS CHAR)) "
             "FROM t_product WHERE description LIKE '%演示数据%';")
    prices = r[0].split("\t") if r else []
    check("价格可作为数值检索（价格区间能查到）",
          len(prices) == 2 and float(prices[0]) > 0,
          f"实际 {prices}")

    # 按价格升序取前 3
    r = rows("SELECT name, price FROM t_product WHERE description LIKE '%演示数据%' "
             "ORDER BY price ASC LIMIT 3;")
    check("价格升序检索可用（客服答『最便宜的』）", len(r) == 3, f"实际返回 {len(r)} 条")
    if len(r) == 3:
        vals = [float(x.split("\t")[1]) for x in r]
        check("升序结果确实递增", vals == sorted(vals), f"实际 {vals}")
        print(f"         最便宜 3 件：{[x.split(chr(9))[0][:20] for x in r]}")

    # 库存区间
    r = rows("SELECT name, stock FROM t_product WHERE stock > 300 "
             "AND description LIKE '%演示数据%' ORDER BY stock DESC LIMIT 5;")
    check("库存区间检索可用（客服答『库存多的』）", len(r) > 0, f"实际 {len(r)} 条")

    # ------------------------------------------------------------
    print("\n【2】结构化属性：数值区间（BM25 做不到，必须用 value_num）")
    # ------------------------------------------------------------
    r = rows("SELECT value_num, COUNT(*) FROM t_product_attr "
             "WHERE attr_key='battery' AND value_num IS NOT NULL "
             "GROUP BY value_num ORDER BY value_num DESC;")
    check("电池容量可做数值区间（客服答『5000mAh 以上』）", len(r) > 0,
          "无 battery 数值属性")
    if r:
        print(f"         电池容量分布：{[x.replace(chr(9), '=') for x in r]}")

    r = rows("SELECT p.name, a.value_num FROM t_product_attr a "
             "JOIN t_product p ON p.id = a.product_id "
             "WHERE a.attr_key='battery' AND a.value_num >= 5000;")
    check("「5000mAh 以上」能精确筛出商品", len(r) > 0, f"实际 {len(r)} 条")
    for x in r:
        print(f"         {x.split(chr(9))[0][:28]} → {x.split(chr(9))[1]} mAh")

    # 交叉验证：属性不能张冠李戴
    r = rows("SELECT p.name, a.value_num FROM t_product_attr a "
             "JOIN t_product p ON p.id = a.product_id "
             "WHERE a.attr_key='screen_size' AND a.value_num > 20;")
    bad = [x for x in r if "显示器" not in x and "笔记本" not in x]
    check("大于 20 英寸的 screen_size 只能是显示器/笔记本（属性未张冠李戴）",
          not bad, f"异常：{bad}")

    # ------------------------------------------------------------
    print("\n【3】BM25 全文检索：ngram 分词是否真的能命中")
    # ------------------------------------------------------------
    tests = [
        ("续航", "SELECT COUNT(*) FROM t_kb_product_chunk "
                 "WHERE MATCH(title, content, keywords) AGAINST('续航' IN BOOLEAN MODE);"),
        ("退货", "SELECT COUNT(*) FROM t_kb_faq "
                 "WHERE MATCH(question, question_kw, answer) AGAINST('退货' IN BOOLEAN MODE);"),
        ("发票", "SELECT COUNT(*) FROM t_kb_faq "
                 "WHERE MATCH(question, question_kw, answer) AGAINST('发票' IN BOOLEAN MODE);"),
        ("发货", "SELECT COUNT(*) FROM t_kb_product_chunk "
                 "WHERE MATCH(title, content, keywords) AGAINST('发货' IN BOOLEAN MODE);"),
    ]
    for kw, query in tests:
        r = rows(query)
        n = int(r[0]) if r and r[0].isdigit() else 0
        check(f"BM25 能召回「{kw}」", n > 0, f"命中 {n} 条")
        if n:
            print(f"         命中 {n} 条")

    # 排序列
    r = rows("SELECT title, ROUND(MATCH(title, content, keywords) AGAINST('续航' IN BOOLEAN MODE),3) "
             "AS score FROM t_kb_product_chunk "
             "WHERE MATCH(title, content, keywords) AGAINST('续航' IN BOOLEAN MODE) "
             "ORDER BY score DESC LIMIT 3;")
    check("BM25 支持按相关度排序", len(r) > 0, "无排序结果")
    if r:
        for x in r:
            parts = x.split("\t")
            print(f"         {parts[0][:20]}  score={parts[1]}")

    # ------------------------------------------------------------
    print("\n【4】问答对：精确命中（命中率最高的路径）")
    # ------------------------------------------------------------
    r = rows("SELECT question FROM t_kb_faq WHERE question LIKE '%多少钱%' LIMIT 3;")
    check("「多少钱」类问题可直接命中标准问答", len(r) > 0, f"实际 {len(r)} 条")
    for x in r[:2]:
        print(f"         {x}")

    r = rows("SELECT question FROM t_kb_faq "
             "WHERE question_kw LIKE '%有货%' AND product_id IS NOT NULL LIMIT 2;")
    check("「有货吗」类问题有关键词兜底", len(r) > 0, f"实际 {len(r)} 条")

    # 意图覆盖
    r = rows("SELECT intent, COUNT(*) FROM t_kb_faq GROUP BY intent ORDER BY 2 DESC;")
    intents = {}
    for x in r:
        p = x.split("\t")
        if len(p) == 2:
            intents[p[0]] = int(p[1])
    need = {"price", "stock", "spec", "after_sale", "logistics"}
    check("意图分类覆盖价格/库存/规格/售后/物流",
          need.issubset(set(intents)), f"现有 {list(intents)}")
    print(f"         意图分布：{intents}")

    # ------------------------------------------------------------
    print("\n【5】向量数据形态（检索侧另测，这里只验数据可用性）")
    # ------------------------------------------------------------
    r = rows("SELECT chunk_type, ROUND(AVG(CHAR_LENGTH(content))) "
             "FROM t_kb_product_chunk GROUP BY chunk_type;")
    lens = {}
    for x in r:
        p = x.split("\t")
        if len(p) == 2:
            lens[p[0]] = int(p[1])
    too_short = {k: v for k, v in lens.items() if v < 80}
    check("知识块平均长度 ≥80 字（太短向量语义不完整）",
          not too_short, f"偏短：{too_short}")
    print(f"         平均字数：{lens}")

    r = rows("SELECT COUNT(*), COUNT(DISTINCT product_id) FROM t_kb_product_chunk;")
    total, cover = (r[0].split("\t") if r else ["0", "0"])
    check("知识块覆盖全部 100 个商品", cover == "100", f"实际覆盖 {cover}")

    r = rows("SELECT COUNT(*) FROM t_kb_product_chunk WHERE content IS NULL OR content = '';")
    check("无空知识块（空块会污染向量库）",
          r and r[0] == "0", f"实际 {r}")

    # 语义自足性：块里必须带商品名，否则向量检索命中后无法定位是哪个商品
    r = rows("SELECT COUNT(*) FROM t_kb_product_chunk c "
             "JOIN t_product p ON p.id = c.product_id "
             "WHERE c.content NOT LIKE CONCAT('%', SUBSTRING_INDEX(p.name, ' ', 1), '%');")
    check("每个知识块都含商品名（向量命中后能定位商品）",
          r and r[0] == "0", f"不含商品名的块 {r[0] if r else '?'} 个")

    # ------------------------------------------------------------
    print("\n" + "=" * 66)
    print(f"结果：通过 {PASS} 项，失败 {FAIL} 项")
    print("=" * 66)
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
