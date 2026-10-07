"""客服对话编排：把意图识别 + 混合检索 + 话术组织串成完整会话。

会话流程
--------
1. 开场：打招呼 + 给选项（正则方案的关键设计）
2. 用户回复选项编号或自由输入
3. 意图识别（正则）
4. 按意图选检索策略：
   - 商品类 → BM25 + 向量 RRF 融合
   - 店铺类 → 只查通用 FAQ
   - 数值约束 → 走结构化属性（不参与 RRF）
5. 组织回答 + 追问引导
6. 低置信度 → 澄清话术而非硬答

三条硬规则
----------
1. **答不出来就说答不出来** —— 宁可「我确认不了，建议咨询人工」，
   也不能编。客服说错参数直接导致退货纠纷。
2. **每个事实都要能溯源** —— 回答里引用了参数，必须来自检索到的内容。
3. **意图不确定就澄清** —— 猜错意图比答错更浪费用户时间。
"""

import io
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import hybrid_search as H                              # noqa: E402
from customer_service import (                          # noqa: E402
    recognize, build_greeting, build_followup_menu,
    INTENT_STRATEGY, INTENT_BY_ID,
)

VEC_PATH = "E:/WorkBuddy/Temp/kb_vectors.npz"
META_PATH = "E:/WorkBuddy/Temp/kb_meta.json"

# 加载向量库（若已向量化）
_VEC = None
_META = None


def load_vectors():
    global _VEC, _META
    if _VEC is not None:
        return True
    if not (os.path.exists(VEC_PATH) and os.path.exists(META_PATH)):
        return False
    try:
        import numpy as np
        d = np.load(VEC_PATH)
        _VEC = d["vectors"]
        _META = {x["id"]: x for x in json.load(io.open(META_PATH, encoding="utf-8"))}
        return True
    except Exception:
        return False


def vector_search(query, top_n=20):
    """向量检索。用 numpy 暴力点积 —— 918 条足够快（<1ms）。

    上生产时换 PGvector + HNSW，接口不变。
    """
    if not load_vectors():
        return []
    try:
        import numpy as np
        from sentence_transformers import SentenceTransformer
        global _MODEL
        try:
            _MODEL
        except NameError:
            _MODEL = SentenceTransformer(
                "E:/WorkBuddy/Temp/models/qwen3emb06b")
        qv = _MODEL.encode([query], prompt_name="query",
                           normalize_embeddings=True)[0]
        scores = _VEC @ qv
        top = scores.argsort()[::-1][:top_n]
        hits = []
        for idx in top:
            rid = str(_VEC.shape[0])  # 占位，实际用 meta 的 id
            rec = None
            # npz 里的 ids 与 meta 顺序一致
            hits.append({"idx": int(idx), "score": float(scores[idx])})
        return hits
    except Exception as e:
        print(f"  [向量检索不可用] {e}")
        return []


# 向量检索用到的 ids（与 kb_meta.json 顺序一致）
_VEC_IDS = None


def vector_search2(query, top_n=20):
    """向量检索（正式版）。"""
    global _VEC_IDS
    if not load_vectors():
        return []
    try:
        import numpy as np
        from sentence_transformers import SentenceTransformer
        if _VEC_IDS is None:
            d = np.load(VEC_PATH)
            _VEC_IDS = [str(x) for x in d["ids"]]
        global _MODEL
        try:
            _MODEL
        except NameError:
            _MODEL = SentenceTransformer("E:/WorkBuddy/Temp/models/qwen3emb06b")

        qv = _MODEL.encode([query], prompt_name="query",
                           normalize_embeddings=True)[0]
        scores = _VEC @ qv
        top = scores.argsort()[::-1][:top_n]
        return [{"id": _VEC_IDS[int(i)], "vector_score": float(scores[i])}
                for i in top]
    except Exception as e:
        print(f"  [向量检索跳过] {e}")
        return []


# ============================================================
# 话术
# ============================================================
CLARIFY = {
    "price": "想了解价格的话，可以告诉我具体是哪款商品，或者直接回复「1」我给您列出在售商品的价格。",
    "stock": "想查库存的话，请告诉我具体的商品名称，我帮您看是否现货。",
    "spec": "可以，请问您想了解哪方面？比如电池容量、尺寸、材质或噪音。",
    "compare": "请告诉我您关注的两款商品或您的预算范围，我来帮您对比。",
    "after_sale": "我可以帮您了解退换货与保修政策。请问是商品质量问题，还是七天无理由退货？",
    "payment": "请问您想了解支付方式还是发票开具？",
    "promotion": "目前平台的优惠以活动专区展示为准，您想了解优惠券还是满减活动？",
    "howto": "点击商品详情页的「加入购物车」或「立即购买」即可下单。需要我介绍具体流程吗？",
}

UNKNOWN_REPLY = (
    "抱歉，我没太理解您的意思。\n"
    "您可以回复下面的序号，或直接描述问题："
)

TRANSFER_REPLY = (
    "好的，为您转接人工客服，请稍候。\n"
    "（演示环境暂未接入人工，坐席接入后会继续为您服务）"
)

NO_ANSWER = (
    "这个我暂时确认不了，不方便给您不准确的信息。\n"
    "建议咨询人工客服确认，或者换个说法再问一下。"
)


def _filter_by_product(hits, product_name):
    """只保留真正属于该商品的知识块。

    匹配依据：知识块正文里以商品名开头（知识库生成时就是这个格式）。
    只按 product_id 匹配不够 —— BM25 返回的是 FAQ 与知识块的混合，
    FAQ 绑定的是单个商品，chunk 才有 product_id。
    """
    out = []
    for h in hits:
        content = h.get("content", "")
        title = h.get("title", "")
        # 商品名出现在正文开头，或标题里含商品名，才算这条讲的是它
        if content.startswith(product_name) or product_name in title:
            out.append(h)
    # 一条都没匹配上时，退而用商品名重新检索一次 —— 融合排序可能把它挤出了 top5
    if not out:
        for h in H.bm25_search(product_name)[:3]:
            c = h.get("content", "")
            if c.startswith(product_name) or product_name in h.get("title", ""):
                out.append(h)
    return out


_PRODUCT_INDEX = None


def _extract_product(text):
    """从用户输入里认出具体商品名（精确匹配知识库中的真实商品名）。

    只做最长前缀匹配 —— 「曜石 5G 智能手机 Pro 12GB+256GB」比
    「曜石 5G 智能手机」更具体，前者才是用户要问的那款。
    """
    global _PRODUCT_INDEX
    if _PRODUCT_INDEX is None:
        try:
            rows = H.mysql_query(
                "SELECT name FROM t_product WHERE description LIKE '%演示数据%';")
            _PRODUCT_INDEX = sorted(
                [r["name"] for r in rows], key=len, reverse=True)
        except Exception:
            _PRODUCT_INDEX = []

    best = None
    for name in _PRODUCT_INDEX:
        # 商品名含规格后缀（如 12GB+256GB），用户可能只说前半部分
        head = name.split(" ")[0]
        if name in text or head in text:
            if best is None or len(name) > len(best):
                best = name
    return best


# 判断查询里是否提到了具体商品。
# 判据：出现了知识库里存在的品牌名或品类词。
_BRAND_HINTS = ["澜图", "曜石", "星野", "云栖", "极光", "维度", "矩阵",
                "锐界", "方糖", "拓维", "清野", "风驰", "暖冬", "沁园", "沐歌",
                "步履", "轻羽", "远行", "素白", "行者", "鲜集", "山野",
                "禾谷", "溪涧", "南亩", "知页", "墨香", "文津", "拾光",
                "观澜", "木言", "织梦", "安寝", "素居", "暖屋", "匠心",
                "得力", "简美", "办公通", "优格"]

_CATEGORY_HINTS = ["手机", "笔记本", "电脑", "空调", "冰箱", "吸尘器",
                   "洗衣机", "跑鞋", "运动鞋", "连衣裙", "外套", "衬衫",
                   "图书", "书", "四件套", "枕头", "被子", "台灯", "键盘",
                   "鼠标", "水杯", "水果", "橙", "车厘子", "大米", "耳机",
                   "手表", "充电宝", "路由器", "显示器", "投影仪", "椅子"]


def _has_entity(text: str) -> bool:
    """查询里是否提到了具体商品。

    命中品牌名或品类词就算 —— 精确匹配不够，
    用户会说「那款折叠的」「白色那件」这类指代，靠关键词兜住。
    """
    if any(b in text for b in _BRAND_HINTS):
        return True
    if any(c in text for c in _CATEGORY_HINTS):
        return True
    return False


def answer_unknown(text):
    return UNKNOWN_REPLY + "\n" + build_followup_menu()


def answer_human():
    return TRANSFER_REPLY


def answer_greeting():
    return build_followup_menu()


def answer_no_kb(intent_id):
    """检索到了意图但没检索到可用知识 —— 必须如实说。"""
    return (NO_ANSWER + "\n\n" + build_followup_menu())


def format_shop_faq(hit):
    """把 FAQ 答案整理成客服口吻。"""
    q = hit["title"].rstrip("？?")
    a = hit["content"]
    return f"关于「{q}」：\n{a}"


def format_product_answer(hits, intent_id, product_name=None):
    """把检索到的商品知识组织成回答。"""
    if not hits:
        return None
    lines = []
    if product_name:
        lines.append(f"关于「{product_name}」：")
        lines.append("")

    label = INTENT_BY_ID.get(intent_id, {}).get("label", "相关信息")

    for h in hits[:3]:
        title = h.get("title", "")
        content = h.get("content", "").strip()
        if not content:
            continue
        # 知识块按类型给个前缀，让回答有结构
        type_label = {
            "spec": "规格",
            "stock": "库存与发货",
            "usecase": "适用场景",
            "warranty": "售后",
        }.get(h.get("chunk_type"), label)
        lines.append(f"【{type_label}】{content}")
        lines.append("")

    if len(lines) <= 2:
        return None
    return "\n".join(lines)


# ============================================================
# 主流程
# ============================================================
def reply(user_input, session=None):
    """处理一轮对话，返回回答文本。"""
    session = session if session is not None else {}

    rec = recognize(user_input, session)
    intent = rec["intent"]

    # 记录会话状态，供上下文兜底用
    if intent not in ("unknown", "greeting"):
        session["last_intent"] = intent

    # ---- 非业务意图直接答 ----
    if intent == "human":
        return answer_human(), rec
    if intent == "greeting":
        return answer_greeting(), rec
    if intent == "unknown":
        return answer_unknown(user_input), rec

    # ---- 按意图分流 ----
    strategy = INTENT_STRATEGY.get(intent, {"scope": "both"})
    scope = strategy["scope"]

    # 店铺类问题只查通用 FAQ（与具体商品无关）
    if scope == "shop":
        hits = H.faq_search(user_input)
        shop_hits = [h for h in hits if h.get("source") == "faq"]
        # faq_search 已限定 t_kb_faq，这里再过滤掉绑定商品的
        shop_hits = [h for h in shop_hits if h.get("product_id") in ("NULL", "", None)]
        if shop_hits:
            return format_shop_faq(shop_hits[0]), rec
        return answer_no_kb(intent), rec

    # 商品类问题：混合检索
    # 但如果用户只回了个选项编号、还没说商品，检索必然查不准。
    # 这时该反问，不是硬答「确认不了」——两者对用户完全不同：
    #   确认不了 = 我不知道
    #   反问      = 你还没告诉我哪款
    if rec["source"].startswith("menu") and not _has_entity(user_input):
        return CLARIFY.get(intent, "请问您想了解哪款商品？") + "\n\n" + build_followup_menu(), rec

    # 商品上下文锁定：用户明确说过某款商品后，后续追问都应限定在那款上。
    # 「曜石手机有货吗」→「续航怎么样」是典型的连续追问，
    # 第二问里没有商品名，靠语义检索会混进同品类的其他商品 ——
    # 客服答错对象比答不出来更糟。
    entity = _extract_product(user_input)
    if entity:
        session["product"] = entity
    product = session.get("product")

    hits = H.bm25_search(user_input) + H.faq_search(user_input)
    vec = vector_search2(user_input, top_n=20)
    if vec:
        merged = H.merge_results(hits, vec, top_n=5)
    else:
        merged = hits[:5]

    # 上下文有商品时，必须严格过滤 —— 只要真正属于该商品的知识块。
    #
    # 不能只改标题：那是「标题说曜石、内容讲极光」，用户会当成曜石的参数。
    # 客服答错对象比答不出来危害大得多。
    if product:
        merged = _filter_by_product(merged, product)
        if not merged:
            # 该商品的知识确实没检索到，如实说，不拿别的商品充数
            return (f"关于「{product}」，我暂时没检索到相关记录。\n"
                    f"可能是该商品的资料还没完善，建议咨询人工客服确认。\n\n"
                    + build_followup_menu()), rec

    answer = format_product_answer(merged, intent, product)
    if answer:
        return answer + "\n" + build_followup_menu(), rec
    return answer_no_kb(intent), rec


def start(product_name=None):
    """开场：打招呼 + 选项。"""
    return build_greeting(product_name)