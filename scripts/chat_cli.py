"""可交互的客服对话程序 —— 用来实际体验，不只是跑测试。

用法：
    python scripts/chat_cli.py
    python scripts/chat_cli.py --product "曜石 5G 智能手机 Pro"

会话状态存在内存里（演示用）。生产环境需要落库：
  kb_unanswered 表已建好，检索无命中时应落库，供运营补知识。
"""

import argparse
import io
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import chat_engine as CE                                # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--product", help="当前浏览的商品名，会显示在开场")
    ap.add_argument("--demo", action="store_true",
                    help="演示模式：自动走一遍典型场景")
    args = ap.parse_args()

    if args.demo:
        run_demo(args.product)
        return

    session = {}
    print("\n" + "=" * 66)
    print(CE.start(args.product))
    print("=" * 66)

    while True:
        try:
            text = input("\n您> ").strip()
        except (EOFError, KeyboardInterrupt):
            print("\n\n会话结束，感谢使用。")
            break
        if not text:
            continue
        if text in ("exit", "quit", "退出", "q"):
            print("\n会话结束，感谢使用。")
            break

        answer, rec = CE.reply(text, session)

        # 显示意图识别结果 —— 演示时可见，实际部署要去掉
        print(f"\n[意图: {rec['intent']} | 置信: {rec['confidence']:.2f} | "
              f"来源: {rec['source']} | 命中: {rec['matched'] or '-'}]")
        print("─" * 66)
        print(answer)
        print("─" * 66)


def run_demo(product=None):
    """自动演示一遍，不需要交互输入。"""
    script = [
        ("", "开场"),
        ("2", "选了「库存与发货」，但没说哪款 → 应反问"),
        ("曜石 5G 智能手机 Pro 有货吗", "指定商品后应给出库存"),
        ("3", "切到「参数与规格」→ 应反问具体方面"),
        ("续航怎么样", "问参数 → 应给出电池/快充信息"),
        ("多少钱", "问价格 → 应给出价格区间"),
        ("支持七天无理由退货吗", "店铺类问题 → 应直接答政策"),
        ("怎么申请退款", "店铺类 → 应给出操作路径"),
        ("能给我优惠到 5 折吗", "数字量词 → 不应误判为选项 5"),
        ("你们老板叫什么", "知识盲区 → 应如实说答不了"),
        ("转人工", "转人工"),
    ]

    session = {}
    print("\n" + "=" * 70)
    print(CE.start(product))
    print("=" * 70)

    for text, note in script:
        if not text:
            continue
        print(f"\n【用户】{text}")
        print(f"    （{note}）")
        answer, rec = CE.reply(text, session)
        print(f"    意图={rec['intent']} 置信={rec['confidence']:.2f} "
              f"来源={rec['source']}")
        for line in answer.split("\n"):
            if line.strip():
                print(f"    | {line}")
        print("─" * 70)


if __name__ == "__main__":
    main()