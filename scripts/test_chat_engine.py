"""端到端对话测试：模拟真实用户从开场走到各种分支。

测的不只是「能不能答」，更重要的是：
  - 开场选项是否清晰
  - 用户答不出来时是否如实说（不编）
  - 低置信度时是否澄清而非硬答
  - 转人工是否可用
"""

import io
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import chat_engine as CE                                # noqa: E402

W = 78


def line(c="─"):
    print(c * W)


def step(session, user_text, expect_intent=None):
    answer, rec = CE.reply(user_text, session)
    print(f"\n【用户】{user_text}")
    print(f"  意图: {rec['intent']:11s} 置信={rec['confidence']:.2f} "
          f"来源={rec['source']:12s} 命中={rec['matched'] or '-'}")
    if expect_intent and rec["intent"] != expect_intent:
        print(f"  [FAIL] 期望意图 {expect_intent}，实得 {rec['intent']}")
    for l in answer.split("\n"):
        if l.strip():
            print(f"  | {l}")
    return answer


def main():
    print("=" * W)
    print("智能客服端到端对话测试")
    print("=" * W)

    # ---------- 场景1：完整流程（选选项 -> 问商品） ----------
    print("\n" + "=" * W)
    print("场景 1：用户从开场开始，用选项导航")
    print("=" * W)
    session = {}

    print("\n【客服 · 开场】")
    for l in CE.start().split("\n"):
        if l.strip():
            print(f"  | {l}")

    step(session, "2", "stock")
    step(session, "曜石手机有货吗", "stock")
    step(session, "3", "spec")
    step(session, "续航怎么样", "spec")

    # ---------- 场景2：店铺类问题 ----------
    print("\n" + "=" * W)
    print("场景 2：店铺类问题（物流售后）")
    print("=" * W)
    s2 = {}
    step(s2, "5", "after_sale")
    step(s2, "支持七天无理由退货吗", "after_sale")
    step(s2, "怎么申请退款", "after_sale")

    # ---------- 场景3：无法回答时是否如实 ----------
    print("\n" + "=" * W)
    print("场景 3：知识盲区 —— 关键看是否如实说")
    print("=" * W)
    s3 = {}
    step(s3, "你们老板叫什么名字")
    step(s3, "能给我优惠到 5 折吗")

    # ---------- 场景4：转人工与告别 ----------
    print("\n" + "=" * W)
    print("场景 4：转人工 / 结束")
    print("=" * W)
    s4 = {}
    step(s4, "转人工", "human")
    step(s4, "再见", "human")

    # ---------- 场景5：混乱输入的兜底 ----------
    print("\n" + "=" * W)
    print("场景 5：混乱 / 无意义输入")
    print("=" * W)
    s5 = {}
    step(s5, "嗯嗯")
    step(s5, "……")
    step(s5, "asdfgh")

    # ---------- 场景6：编号变体 ----------
    print("\n" + "=" * W)
    print("场景 6：选项编号的各种写法")
    print("=" * W)
    for variant in ["1", "A", "①", "1、", " 3 ", "6. 发票", "我按错了，2"]:
        s = {}
        _, rec = CE.reply(variant, s)
        print(f"  {variant:14s} → {rec['intent']:11s} (来源 {rec['source']})")

    print("\n" + "=" * W)
    print("对话测试完成")
    print("=" * W)


if __name__ == "__main__":
    main()