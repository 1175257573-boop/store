"""正则意图识别的准确率验证。

意图识别错了，后面检索再准也没用 —— 会去答不对的问题。
所以先用一批真实用户问法（不是我自己编的正则样例）验证准确率。
"""

import io
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from customer_service import recognize, INTENTS  # noqa: E402

# ============================================================
# 测试集：按真实用户的说话习惯写，包含口语、错别字、省略、歧义
# 每条是 (输入, 期望意图, 备注)
# 备注里标 tricky 的几条是刻意设计的边界情况
# ============================================================
CASES = [
    # ---- 明确关键词 ----
    ("这个多少钱", "price", ""),
    ("价格是多少", "price", ""),
    ("太贵了，能便宜点吗", "price", ""),
    ("有货吗", "stock", ""),
    ("什么时候发货", "stock", ""),
    ("发什么快递", "stock", ""),
    ("包邮吗", "stock", ""),
    ("支持自提吗", "stock", ""),
    ("续航怎么样", "spec", ""),
    ("电池多少毫安", "spec", ""),
    ("是防水的吗", "spec", ""),
    ("什么材质", "spec", ""),
    ("支持七天无理由退货吗", "after_sale", ""),
    ("怎么申请退款", "after_sale", ""),
    ("坏了怎么办，能保修吗", "after_sale", ""),
    ("退货运费谁出", "after_sale", ""),
    ("可以开发票吗", "payment", ""),
    ("支持什么支付方式", "payment", ""),
    ("有什么优惠活动吗", "promotion", ""),
    ("优惠券在哪领", "promotion", ""),

    # ---- 口语化表达（正则的真正考验）----
    ("能用多久", "spec", "tricky：没出现'续航'，靠同义"),
    ("电耐用吗", "spec", "tricky：口语化"),
    ("声音大不大", "spec", "tricky：噪音的另一种说法"),
    ("吵不吵", "spec", "tricky：极口语"),
    ("防水不", "spec", "tricky：极简"),
    ("啥时候到货", "stock", "tricky：'啥时候'"),
    ("几天能到", "stock", "tricky"),
    ("我想要黑色的有吗", "spec", "tricky：颜色需求"),
    ("有没有赠品", "promotion", "tricky：赠品算优惠吗"),
    ("能便宜多少", "price", "tricky"),

    # ---- 选项编号（方案的核心）----
    ("1", "price", "tricky：纯编号"),
    ("2", "stock", "tricky"),
    ("3", "spec", "tricky"),
    ("4", "compare", "tricky"),
    ("5", "after_sale", "tricky"),
    ("6", "payment", "tricky"),
    ("A", "price", "tricky：字母"),
    ("c", "spec", "tricky"),
    ("②", "stock", "tricky：带圈数字"),
    ("2、我想看什么时候发货", "stock", "tricky：编号+自由输入"),
    ("1多少钱", "price", "tricky：编号紧跟关键词"),
    ("3. 参数", "spec", "tricky：编号+点"),
    ("⑤", "after_sale", "tricky"),

    # ---- 歧义与冲突（检验优先级顺序）----
    ("怎么购买", "howto", "tricky：'购买'不该被'优惠'抢走"),
    ("怎么下单", "howto", "tricky"),
    ("哪个比较好", "compare", ""),
    ("推荐一个", "compare", ""),
    ("哪个便宜点", "compare", "tricky：compare 与 price 冲突"),
    ("转人工", "human", ""),
    ("我要找客服", "human", "tricky：'客服'一词"),
    ("再见", "human", "tricky：结束语"),
    ("在吗", "greeting", ""),
    ("你好", "greeting", ""),

    # ---- 兜底 ----
    ("嗯嗯", "unknown", ""),
    ("那个", "unknown", ""),
    ("哈哈哈哈", "unknown", "tricky：纯语气词"),
]


def main():
    print("=" * 70)
    print("正则意图识别准确率验证")
    print("=" * 70)
    print(f"测试样本 {len(CASES)} 条（含 {sum(1 for c in CASES if c[2])} 条边界情况）\n")

    passed, failed = 0, []
    by_group = {"正常": [0, 0], "口语": [0, 0], "选项": [0, 0],
                "歧义": [0, 0], "兜底": [0, 0]}

    for text, expect, note in CASES:
        got = recognize(text)
        ok = got["intent"] == expect
        passed += ok
        failed.append((text, expect, got)) if not ok else None

        # 分组统计
        if text in ("1", "2", "3", "4", "5", "6") or text[0] in "ABCDEFabcdef①-⑥" or \
           (len(text) > 1 and text[0].isdigit() and len(text) < 20):
            g = "选项"
        elif expect == "unknown":
            g = "兜底"
        elif expect in ("compare",) or "冲突" in note or "抢走" in note:
            g = "歧义"
        elif note and ("口语" in note or "极" in note or "tricky" in note) and expect in ("spec", "stock"):
            g = "口语"
        else:
            g = "正常"
        by_group[g][1] += 1
        by_group[g][0] += ok

        mark = "OK  " if ok else "FAIL"
        flag = f"  <- {note}" if note else ""
        print(f"  [{mark}] {text:22s} 期望={expect:10s} 实得={got['intent']:10s}"
              f" 置信={got['confidence']:.2f}{flag}")

    print("\n" + "=" * 70)
    print(f"总准确率: {passed}/{len(CASES)} = {passed / len(CASES) * 100:.1f}%")
    print("-" * 70)
    print("分组表现:")
    for g, (p, t) in by_group.items():
        if t:
            bar = "#" * int(p / t * 20)
            print(f"  {g:4s} {p:2d}/{t:2d}  {bar}")

    if failed:
        print("\n失败明细:")
        for text, expect, got in failed:
            print(f"  「{text}」期望 {expect}，实得 {got['intent']}"
                  f"（命中关键词：{got.get('matched') or '无'}）")

    print("=" * 70)
    return 0 if passed == len(CASES) else 1


# ============================================================
# 过拟合检验集
#
# 存在的意义：上面的 CASES 是我照着正则写的，全部通过只能证明
# 「正则覆盖了我自己写的样例」。这个集合用没见过的表达方式，
# 检验泛化能力。实测第一次跑只有 70%（20 条错 6 条），暴露了词库缺口。
#
# 规则：这个集合里的每一条都不能出现在 CASES 里。
# ============================================================
NEW_CASES = [
    ("这玩意儿卖几个钱", "price"), ("太贵了能少点不", "price"),
    ("现货吗现在", "stock"), ("啥时候能寄出", "stock"),
    ("能撑多长时间", "spec"), ("这玩意儿沉不沉", "spec"),
    ("漏不漏水", "spec"), ("大不大声音", "spec"),
    ("我要退货", "after_sale"), ("发票能开吗", "payment"),
    ("有啥子优惠没", "promotion"), ("哪个更划算", "compare"),
    ("人工", "human"), ("你谁", "unknown"), ("额", "unknown"),
    (" 3 ", "spec"), ("4、", "compare"), ("6. 发票", "payment"),
    ("我按错了，2", "stock"), ("3 想问价格", "price"),
    # 数字 + 量词：曾被误判为选项编号（"5 折" -> after_sale）
    ("能给我优惠到 5 折吗", "promotion"),
    ("7 天无理由", "after_sale"),
]


def run_overfit_check():
    print("\n" + "=" * 70)
    print("过拟合检验：全新输入（CASES 里没有过的表达方式）")
    print("=" * 70)
    ok, bad = 0, []
    for text, expect in NEW_CASES:
        got = recognize(text)
        good = got["intent"] == expect
        ok += good
        if not good:
            bad.append((text, expect, got))
        print(f"  [{'OK  ' if good else 'FAIL'}] {text:22s} "
              f"期望={expect:11s} 实得={got['intent']:11s}")

    rate = ok / len(NEW_CASES) * 100
    print("-" * 70)
    print(f"泛化准确率: {ok}/{len(NEW_CASES)} = {rate:.1f}%")
    if bad:
        print("\n失败明细（说明词库或优先级有缺口）:")
        for text, expect, got in bad:
            print(f"  「{text}」期望 {expect}，实得 {got['intent']}")

    # 阈值：低于 90% 说明泛化能力不足，会在真实对话里频繁答错
    threshold_ok = rate >= 90.0
    print(f"\n判定: {'PASS（>=90%）' if threshold_ok else 'FAIL（低于 90%，泛化不足）'}")
    return threshold_ok


if __name__ == "__main__":
    sys.exit(main() or (0 if run_overfit_check() else 1))