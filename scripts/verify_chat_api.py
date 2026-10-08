"""智能客服接口端到端验证。

走真实 HTTP 接口，模拟前端 ChatWidget 的完整交互：
  打开窗口 → 拿开场白 → 点选项 → 自由输入 → 商品锁定 → 转人工

关键检查项（都是开发中出现过的真实缺陷）：
  1. sessionId 必须有值 —— 否则后续请求拿不到上下文
  2. 只回选项时应反问，不是硬答「确认不了」
  3. 商品锁定：详情页问过的商品，后续追问必须答同一款
  4. 标题与内容一致 —— 不能「关于曜石」却讲极光
  5. 知识盲区要如实说，不能编
"""

import json
import sys
import urllib.parse
import urllib.request

BASE = "http://127.0.0.1:8080/api"
W = 76

PASS, FAIL = 0, 0


def line(c="─"):
    print(c * W)


def check(name, cond, detail=""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  [PASS] {name}")
    else:
        FAIL += 1
        print(f"  [FAIL] {name}  {detail}")


def get(path, params):
    url = BASE + path + "?" + urllib.parse.urlencode(params)
    with urllib.request.urlopen(url, timeout=15) as r:
        return json.loads(r.read().decode())


def post(path, payload):
    req = urllib.request.Request(
        BASE + path,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST")
    with urllib.request.urlopen(req, timeout=15) as r:
        return json.loads(r.read().decode())


def send(sid, msg):
    d = post("/chat/send", {"sessionId": sid, "message": msg})["data"]
    return d


def main():
    print("=" * W)
    print("智能客服接口端到端验证")
    print("=" * W)

    product = "曜石 5G 智能手机 Pro 12GB+256GB"

    # ---------- 1. 开场 ----------
    print("\n【1】开场白与选项")
    g = get("/chat/greeting", {"sessionId": "", "productName": product})["data"]
    sid = g.get("sessionId")
    print(f"  开场回复:\n{g['reply']}")
    print()
    check("sessionId 已生成（否则后续请求无上下文）",
          bool(sid) and sid != "None", f"实际={sid!r}")
    check("开场含 6 个选项",
          all(f"{i}." in g["reply"] for i in range(1, 7)))
    check("开场含当前商品名（详情页场景）", product in g["reply"])

    # ---------- 2. 只回选项：应反问 ----------
    print("\n【2】只回选项编号（用户还没说哪款商品）")
    r = send(sid, "2")
    print(f"  回复: {r['reply'][:80]}")
    check("意图识别为 stock", r["intent"] == "stock", f"实际={r['intent']}")
    check("来源标记为 menu", r["source"].startswith("menu"),
          f"实际={r['source']}")
    check("应反问具体商品，而不是答「确认不了」",
          ("请告诉我" in r["reply"] or "具体的商品" in r["reply"])
          and "确认不了" not in r["reply"],
          f"实际={r['reply'][:40]}")

    # ---------- 3. 商品锁定 ----------
    print("\n【3】商品上下文锁定（连续追问的核心）")
    r1 = send(sid, f"{product} 有货吗")
    print(f"  问：{product} 有货吗")
    print(f"  答: {r1['reply'][:110]}")
    check("第一问锁定了商品", "曜石" in r1["reply"])
    check("库存数字来自该商品（117 件）", "117" in r1["reply"],
          f"实际回复={r1['reply'][:120]}")

    r2 = send(sid, "续航怎么样")
    print(f"\n  问：续航怎么样（这句没提商品名）")
    print(f"  答: {r2['reply'][:110]}")
    check("第二问仍锁定同一商品（标题含曜石）",
          "曜石" in r2["reply"], f"实际={r2['reply'][:80]}")
    # 关键：不能出现别的商品。极光/星野是同品类其他商品
    others = [b for b in ("极光", "星野", "云栖", "澜图") if b in r2["reply"]]
    check("回答不含其他商品（标题与内容必须一致）",
          not others, f"混入了 {others}")

    # ---------- 4. 意图分类准确性 ----------
    print("\n【4】意图分类")
    cases = [
        ("多少钱", "price"),
        ("支持七天无理由退货吗", "after_sale"),
        ("怎么申请退款", "after_sale"),
        ("可以开发票吗", "payment"),
        ("有什么优惠活动吗", "promotion"),
        ("转人工", "human"),
    ]
    for msg, expect in cases:
        rr = send(sid, msg)
        ok = rr["intent"] == expect
        mark = "OK  " if ok else "FAIL"
        print(f"  [{mark}] {msg:22s} 期望={expect:12s} 实得={rr['intent']:12s} "
              f"命中={rr['matched'] or '-'}")
        if not ok:
            check(f"「{msg}」意图正确", False, f"期望 {expect}，实得 {rr['intent']}")
        else:
            PASS += 1

    # ---------- 5. 数字量词不得误判 ----------
    print("\n【5】数字+量词不得被误判为选项编号")
    rr = send(sid, "能给我优惠到 5 折吗")
    print(f"  问：能给我优惠到 5 折吗")
    print(f"  意图={rr['intent']}（应为 promotion，5 是折扣不是选项5）")
    check("「5 折」识别为 promotion", rr["intent"] == "promotion",
          f"实际={rr['intent']}")

    # ---------- 6. 知识盲区如实说 ----------
    print("\n【6】知识盲区 —— 不能编造")
    sid2 = get("/chat/greeting", {"sessionId": "", "productName": ""})["data"]["sessionId"]
    rr = send(sid2, "你们老板叫什么名字")
    print(f"  回复: {rr['reply'][:80]}")
    check("未识别时给选项引导", rr["intent"] == "unknown", f"实际={rr['intent']}")
    check("未识别时不编造内容",
          "没太理解" in rr["reply"] or "确认不了" in rr["reply"])

    # ---------- 7. 店铺类走通用 FAQ ----------
    print("\n【7】店铺类问题命中通用 FAQ")
    rr = send(sid2, "支持七天无理由退货吗")
    print(f"  回复: {rr['reply'][:100]}")
    check("店铺政策答得具体", "7 天" in rr["reply"] or "七天" in rr["reply"])
    check("引用了 FAQ（citations 非空）", len(rr.get("citations") or []) > 0,
          f"实际={rr.get('citations')}")

    # ---------- 8. 性能 ----------
    print("\n【8】响应时间")
    times = [send(sid2, m)["replyTimeMs"] for m in
             ["多少钱", "有货吗", "续航怎么样", "转人工"]]
    avg = sum(times) / len(times)
    print(f"  各次耗时: {times} ms，平均 {avg:.0f} ms")
    check("平均响应 <500ms（实时对话要求）", avg < 500, f"实际 {avg:.0f}ms")

    print("\n" + "=" * W)
    print(f"结果：通过 {PASS} 项，失败 {FAIL} 项")
    print("=" * W)
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())