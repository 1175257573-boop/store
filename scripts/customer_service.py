"""正则意图识别 + 选项式客服引擎。

为什么用正则而不是模型
----------------------
1. 零延迟、零成本 —— 客服对话是高频场景，模型推理每轮 2.3s 撑不住
2. 结果可审计 —— 每个意图命中哪些关键词一目了然，出错能查
3. 不依赖标注数据 —— 冷启动阶段没有训练集
4. 意图集合稳定 —— 电商客服的高频问题就那么几类，长期不会大改

代价与应对
----------
正则的短板是「同义表达」和「上下文依赖」：
- 「续航」「电池」「能用多久」→ 都要映射到同一个意图，所以用同义词表
- 「那它呢」这种依赖上下文的短句，正则处理不了，靠会话状态兜底

设计要点
--------
**开场给选项**不是 UI 糖，是正则方案的必要条件：
自由输入的表述千变万化，而选项是受控的 —— 1/2/3 这三个输入
正则能 100% 稳定命中。用选项把用户「训练」到可控表达上。

所以：支持选项编号（A/B/C、1/2/3）+ 关键词 + 自然语言三层匹配。
"""

import io
import json
import re
from collections import defaultdict

# ============================================================
# 意图定义
#
# 每个意图三要素：
#   id       内部标识
#   label    给用户看的名字（选项里显示）
#   patterns 同义词表，正则用
#   scope    product / shop —— 决定答案从商品知识还是通用知识取
# ============================================================
INTENTS = [
    # ⚠️ 顺序即优先级（命中次数相同时按数组下标决定）。
    # compare 必须排在 price 前面：「哪个便宜点」问的是"买哪个划算"，
    # 答成"这个多少钱"是答错了方向，不是答得不够细。
    {
        "id": "compare",
        "label": "对比与推荐",
        "scope": "product",
        "patterns": [
            r"哪个好", r"哪个比较好", r"哪个好点", r"比较(?!较好)",
            r"推荐", r"求推荐", r"建议(?!价)", r"选哪", r"怎么选",
            r"划算", r"值得买", r"买哪个", r"选哪个",
            r"区别(?!退)", r"对比", r"比一比",
            r"哪个(更|划算|合适|便宜|好)",
            # 「哪个便宜」必须归 compare，但单独的「便宜」归 price。
            # 用负向断言把两者分开。
            r"哪个便宜",
        ],
    },
    {
        "id": "price",
        "label": "查价格",
        "scope": "product",
        "patterns": [
            r"多少钱", r"价格", r"价钱", r"报价", r"贵不贵",
            # 「便宜」只在不构成对比时才算价格问题
            r"(?<!哪个)便宜", r"少点", r"优惠点", r"打个折",
            r"折扣", r"最低价", r"几块", r"多少米", r"售价", r"price",
            r"卖(几|多)个?钱", r"几个钱", r"卖多少", r"几个米", r"值多少",
        ],
    },
    {
        "id": "stock",
        "label": "查库存与发货",
        "scope": "product",
        "patterns": [
            r"有货", r"现货", r"库存", r"还有货", r"断货", r"缺货",
            r"发货", r"什么时候发", r"多久发", r"能发", r"发什么快递",
            r"物流", r"快递", r"包邮", r"运费", r"自提", r"配送",
            # 「几天能到」这类省略了动词的说法，要能覆盖
            r"几天(能|可以)?到", r"几天(能|可以)?发", r"什么时候到货",
            r"到货", r"啥时候到", r"啥时候发", r"何时到",
            r"寄出", r"寄出?来", r"什么时候寄", r"啥时候寄", r"几天寄",
        ],
    },
    {
        "id": "spec",
        "label": "查参数与规格",
        "scope": "product",
        "patterns": [
            r"参数", r"配置", r"规格", r"型号", r"尺寸", r"多大",
            r"多少毫安", r"电池", r"续航", r"能用多久", r"能用多长",
            r"耐用吗", r"耐用性", r"耗电", r"费电", r"掉电",
            r"撑(多)?长", r"用多久", r"能用(多)?久", r"顶得住",
            # 「XX不」「XX吗」式提问：漏不漏、沉不沉、烫不烫、响不响
            r"漏不漏", r"沉不沉", r"轻不轻", r"烫不烫", r"硬不硬",
            r"粘不粘", r"掉不掉", r"裂不裂", r"划不划", r"伤不伤",
            r"功率", r"能耗", r"防水", r"材质", r"材料", r"重量",
            r"屏幕", r"颜色", r"什么色", r"黑色", r"白色", r"银色",
            r"灰色", r"蓝色", r"绿色", r"红色", r"粉色", r"米色",
            r"适合", r"人群", r"送人", r"送礼",
            # 噪音的多种说法
            r"噪音", r"噪声", r"声音(大|小)?", r"吵", r"安静", r"静音",
            r"响不响", r"吵不吵",
            r"接口", r"什么接口", r"配什么",
            r"罩杯", r"尺码", r"多大码", r"几码",
        ],
    },
    {
        "id": "after_sale",
        "label": "退换货与保修",
        "scope": "shop",
        "patterns": [
            r"退货", r"退款", r"退钱", r"换货", r"换新", r"售后",
            r"保修", r"维修", r"坏了", r"质量(?!量足)", r"故障",
            r"七天", r"无理由", r"运费谁出", r"怎么退", r"申请退",
            r"能退", r"可以退", r"包退",
        ],
    },
    {
        "id": "payment",
        "label": "支付与发票",
        "scope": "shop",
        "patterns": [
            r"支付", r"付款", r"怎么付", r"发票", r"开票", r"增值税",
            r"取消订单", r"订单取消", r"能不能取消",
        ],
    },
    {
        "id": "promotion",
        "label": "优惠与活动",
        "scope": "shop",
        "patterns": [
            r"优惠", r"活动", r"打折", r"满减", r"优惠券", r"领券",
            r"券", r"促销", r"特价", r"秒杀",
            r"赠品", r"送(?!货)", r"附赠", r"加送",
        ],
    },
    {
        "id": "howto",
        "label": "怎么购买",
        "scope": "shop",
        "patterns": [
            r"怎么买", r"怎么购买", r"如何购买", r"下单", r"加入购物车",
            r"怎么下单", r"购买流程",
        ],
    },
]

# 预编译：每个意图一个合并正则
_COMPILED = {
    it["id"]: re.compile("|".join(it["patterns"]))
    for it in INTENTS
}

INTENT_BY_ID = {it["id"]: it for it in INTENTS}

# ============================================================
# 开场白与选项
#
# 选项设计的原则：
#   1. 6 个以内 —— 超过用户会跳过
#   2. 每个都是用户真的会问的，不要塞「其他」（那是兜底，不该出现在选项里）
#   3. 编号用数字而非字母 —— 手机输入法打字母要切输入法，数字直接输
#   4. 顺序按咨询频次排，最常见的在前
# ============================================================
GREETING_TEMPLATE = (
    "您好，我是智能客服。请问您想了解哪方面？\n"
    "直接回复序号即可，也可以用自己的话描述。\n"
)

MENU = [
    ("1", "price",    "商品价格与优惠"),
    ("2", "stock",    "库存与发货"),
    ("3", "spec",     "商品参数与规格"),
    ("4", "compare",  "对比与选购建议"),
    ("5", "after_sale", "退换货与保修"),
    ("6", "shop",     "支付发票与物流"),
]


def build_greeting(product_name=None) -> str:
    lines = [GREETING_TEMPLATE.rstrip("\n")]
    if product_name:
        lines.append(f"当前商品：{product_name}")
        lines.append("")
    lines.append("您可以回复：")
    for num, _, label in MENU:
        lines.append(f"  {num}. {label}")
    lines.append("")
    lines.append("例如回复「2」查看库存与发货，或直接说「这个多少钱」。")
    return "\n".join(lines)


def build_followup_menu() -> str:
    """追问后的简化菜单 —— 只留高频项，避免刷屏。"""
    lines = ["您还想了解："]
    for num, _, label in MENU[:4]:
        lines.append(f"  {num}. {label}")
    lines.append("  0. 转人工")
    return "\n".join(lines)


# ============================================================
# 意图识别
# ============================================================
# 选项编号 -> 意图。菜单第6项是「支付发票与物流」聚合项。
MENU_MAP = {
    "1": "price", "2": "stock", "3": "spec",
    "4": "compare", "5": "after_sale", "6": "payment",
    # 字母也支持（用户可能直接敲 A/B/C）
    "a": "price", "b": "stock", "c": "spec",
    "d": "compare", "e": "after_sale", "f": "payment",
    "①": "price", "②": "stock", "③": "spec",
    "④": "compare", "⑤": "after_sale", "⑥": "payment",
}

# 「数字+意图词」的组合，如「2我想看物流」—— 编号后面跟了别的字也认
MENU_PREFIX = re.compile(
    r"^\s*([1-6a-fＡＢＣＤＥＦ①-⑥])"
    # 排除「5折」「7天」「2件」—— 后面跟量词的是数量，不是选项
    # 同上：允许数字与量词之间有空格
    r"(?!\s*[折天件个台支只张个人月年时分秒米厘克斤套盒瓶包份次])(?:\s*[、.．,，:：]?\s*)(.*)$",
    re.IGNORECASE)

# 编号不在开头的情况：「我按错了，2」「那个2」「选择2」
# 要求编号是独立的 token（前后是空白/标点/结束），否则「2」会从数字里乱认。
MENU_LOOSE = re.compile(
    r"(?:^|[\s，,。.、：:；;！!？?])([1-6a-fＡＢＣＤＥＦ①-⑥])"
    # 编号后面若跟量词，说明这是数量不是选项。
    # 断言里必须带 \s* ——「 5 折」中数字与「折」之间有空格，
    # 只写 (?![折...]) 的话断言吃到的是空格，会直接放行。
    r"(?!\s*[折天件个台支只张个人月年时分秒米厘克斤套盒瓶包份次])(?=$|[\s，,。.、：:；;！!？?])",
    re.IGNORECASE)


_FULLWIDTH = {
    "ａ": "a", "ｂ": "b", "ｃ": "c", "ｄ": "d", "ｅ": "e", "ｆ": "f",
    "Ａ": "a", "Ｂ": "b", "Ｃ": "c", "Ｄ": "d", "Ｅ": "e", "Ｆ": "f",
}


def _normalize_num(ch: str) -> str:
    """把选项编号归一化成 MENU_MAP 的 key。

    要处理的形态比想象中多：数字、字母、带圈数字、全角字母。
    带圈数字（①②③）在 UTF-8 里是单码点，index() 直接可用。
    """
    if ch in "①②③④⑤⑥":
        return str("①②③④⑤⑥".index(ch) + 1)
    return ch.lower().replace("ａ", "a").replace("ｂ", "b").replace("ｃ", "c") \
                    .replace("ｄ", "d").replace("ｅ", "e").replace("ｆ", "f") \
                    .replace("Ａ", "a").replace("Ｂ", "b").replace("Ｃ", "c") \
                    .replace("Ｄ", "d").replace("Ｅ", "e").replace("Ｆ", "f")


def recognize(user_input: str, session_state=None) -> dict:
    """识别用户意图。

    返回 {intent, confidence, matched, source}
      intent      识别出的意图 id，unknown 表示没把握
      confidence  0~1，用于决定是否追问澄清
      matched     命中的关键词，用于「为什么这么答」的追溯
      source      'menu' 表示来自选项编号，'keyword' 来自正则，'context' 来自上下文
    """
    if not user_input:
        return {"intent": "unknown", "confidence": 0.0,
                "matched": "", "source": "none"}

    text = user_input.strip()
    low = text.lower()

    # ① 转人工 / 退出 —— 必须最高优先级
    if re.search(r"人工|转人工|客服|真人|人工服务|退出|结束|拜拜|再见", text):
        return {"intent": "human", "confidence": 1.0,
                "matched": "人工", "source": "keyword"}

    # ② 问候语
    if re.search(r"^(在吗|您好|你好|hi|hello|哈喽|在不在|有人吗)[!！。~？?]*$",
                 low.strip()):
        return {"intent": "greeting", "confidence": 1.0,
                "matched": "问候", "source": "keyword"}

    # ③ 选项编号（可带后缀，如「2物流」「2我想看发货」）
    m = MENU_PREFIX.match(text)
    if m:
        num = m.group(1)
        rest = m.group(2)
        key = _normalize_num(num)
        if key in MENU_MAP:
            intent = MENU_MAP[key]
            # 编号后面还有词，若能再识别出更具体的意图，用它（更精确）
            if rest:
                sub = _match_keywords(rest)
                if sub and sub["intent"] != "unknown":
                    # 只有当细分意图与选项不冲突时才覆盖
                    # （如选「6 支付发票与物流」但后缀写「退货」，应按退货走）
                    if sub["intent"] not in intent:
                        return {**sub, "source": "menu+keyword",
                                "matched": f"{num} + {sub['matched']}"}
            return {"intent": intent, "confidence": 1.0,
                    "matched": num, "source": "menu"}

    # ③b 编号不在开头：「我按错了，2」「那个2」
    loose = MENU_LOOSE.search(text)
    if loose:
        key = _normalize_num(loose.group(1))
        if key in MENU_MAP:
            return {"intent": MENU_MAP[key], "confidence": 0.9,
                    "matched": loose.group(1), "source": "menu_loose"}

    # ④ 关键词正则
    r = _match_keywords(text)
    if r["intent"] != "unknown":
        return r

    # ⑤ 上下文兜底：极短的输入（如「嗯」「那个」）沿用上一轮意图
    if session_state and len(text) <= 4:
        prev = session_state.get("last_intent")
        if prev and prev != "unknown":
            return {"intent": prev, "confidence": 0.5,
                    "matched": "上下文延续", "source": "context"}

    return {"intent": "unknown", "confidence": 0.0,
            "matched": "", "source": "none"}


def _match_keywords(text):
    """按意图顺序做正则匹配。

    顺序很重要：INTENTS 的排列即优先级。
    「怎么购买」含「购买」，若 promotion 排在前面会误判成「优惠」，
    所以更具体的意图必须排在更宽泛的之前。
    """
    # 每个意图记录：命中关键词、命中次数、在 INTENTS 中的次序
    hits = []
    for idx, it in enumerate(INTENTS):
        found = _COMPILED[it["id"]].findall(text)
        if found:
            hits.append({
                "intent": it["id"],
                "keyword": found[0],
                "count": len(found),
                "order": idx,          # 数组下标即优先级，越小越优先
            })
    if not hits:
        return {"intent": "unknown", "confidence": 0.0,
                "matched": "", "source": "none"}

    # 排序规则：命中次数多的优先；次数相同时按 INTENTS 顺序（具体的排前面）
    best = sorted(hits, key=lambda h: (-h["count"], h["order"]))[0]
    best_id = best["intent"]
    matched_kw = best["keyword"]
    count = best["count"]
    # 命中多个关键词 → 置信度更高（上限 0.95，选项才是 1.0）
    conf = min(0.6 + 0.15 * count, 0.95)
    return {"intent": best_id, "confidence": conf,
            "matched": matched_kw, "source": "keyword"}


def _order(intent_id):
    for i, it in enumerate(INTENTS):
        if it["id"] == intent_id:
            return i
    return 999


# ============================================================
# 意图 → 检索策略
# ============================================================
INTENT_STRATEGY = {
    "price":    {"scope": "product", "faq_intent": "price"},
    "stock":    {"scope": "product", "faq_intent": "stock"},
    "spec":     {"scope": "product", "faq_intent": "spec"},
    "compare":  {"scope": "product", "faq_intent": "spec"},
    "after_sale": {"scope": "shop", "faq_intent": "after_sale"},
    "payment":  {"scope": "shop", "faq_intent": "payment"},
    "promotion": {"scope": "shop", "faq_intent": "promotion"},
    "howto":    {"scope": "shop", "faq_intent": "unknown"},
    # 非业务意图
    "greeting": {"scope": "none", "faq_intent": None},
    "human":    {"scope": "none", "faq_intent": None},
    "unknown":  {"scope": "both", "faq_intent": None},
}