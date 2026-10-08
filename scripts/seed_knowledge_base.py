#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""生成智能客服知识库数据。

数据与 100 条演示商品一一对应（scripts/seed_products.py 生成的那些）。

为什么按「知识块」而不是「一整段详情」存储
----------------------------------------
1. 详情动辄上千字，整段做向量匹配会被平均掉，召回不精确
2. 客服问「续航多久」，期望命中续航那一段，不是整段详情
3. 分块后每块可独立更新（改价格不必重算全段的向量）

四种知识来源与用途
------------------
| 知识类型           | 表                   | 服务的问法           |
|--------------------|----------------------|----------------------|
| 结构化属性         | t_product_attr       | 「有黑色吗」「多少毫安」|
| 商品知识块         | t_kb_product_chunk   | 「防水吗」「适合学生吗」|
| 问答对             | t_kb_faq             | 「能开发票吗」「多久发货」|
| 店铺/平台通用      | t_kb_shop_knowledge  | 「退货怎么退」        |

结构化属性是「不能靠语义相似度猜」的部分：
用户问「5000mAh 以上有哪些」，必须能用 value_num 做数值比较，
不能指望向量检索把 5000 和 4500 认作「差不多」。
"""

import io
import os
import subprocess
import tempfile
import uuid

MYSQL = r"C:\Program Files\MySQL\MySQL Server 5.7\bin\mysql.exe"
DB = "ecommerce"
TAG = "演示数据"


def sql(stmt):
    p = subprocess.run(
        [MYSQL, "-uroot", "-p123456", "--default-character-set=utf8mb4",
         "-e", f"USE {DB}; {stmt}"],
        capture_output=True)
    return p.stdout.decode("utf-8", errors="replace")


def sql_file(stmt):
    """长 SQL 走文件导入：Windows 命令行有 32K 限制。"""
    f = os.path.join(tempfile.gettempdir(), f"kb_{uuid.uuid4().hex[:8]}.sql")
    with io.open(f, "w", encoding="utf-8") as fh:
        fh.write(f"USE {DB};\n")
        fh.write(stmt)
    with io.open(f, "rb") as fh:
        p = subprocess.run(
            [MYSQL, "-uroot", "-p123456", "--default-character-set=utf8mb4"],
            stdin=fh, capture_output=True)
    os.remove(f)
    if p.returncode != 0:
        print("  [导入失败]", p.stderr.decode("utf-8", errors="replace")[:300])


def q(v):
    if v is None:
        return "NULL"
    return "'" + str(v).replace("\\", "\\\\").replace("'", "''") + "'"


def first_int(query):
    for line in sql(query).split("\n"):
        t = line.strip()
        if t.isdigit():
            return int(t)
    return -1


# ============================================================
# 各品类的属性定义
#   key / 显示名 / 单位 / 分组 / 从描述里提取数值的正则
# 每个品类关心的参数完全不同：手机关心电池与屏幕，家电关心能耗
# ============================================================
ATTR_SPECS = {
    1: [  # 手机通讯
        ("brand", "品牌", None, "基本参数"),
        ("model", "型号", None, "基本参数"),
        ("screen_size", "屏幕尺寸", "英寸", "屏幕"),
        ("screen_type", "屏幕类型", None, "屏幕"),
        ("battery", "电池容量", "mAh", "性能"),
        ("charging", "充电功率", "W", "性能"),
        ("chip", "处理器", None, "性能"),
        ("weight", "机身重量", "g", "机身"),
        ("thickness", "机身厚度", "mm", "机身"),
        ("network", "网络制式", None, "通信"),
    ],
    2: [  # 电脑办公
        ("brand", "品牌", None, "基本参数"),
        ("model", "型号", None, "基本参数"),
        ("screen_size", "屏幕尺寸", "英寸", "屏幕"),
        ("resolution", "分辨率", None, "屏幕"),
        ("cpu", "处理器", None, "性能"),
        ("gpu", "显卡", None, "性能"),
        ("ram", "内存容量", "GB", "性能"),
        ("ssd", "固态硬盘", "GB", "性能"),
        ("weight", "整机重量", "kg", "机身"),
        ("battery_life", "续航时长", "小时", "续航"),
    ],
    3: [  # 家用电器
        ("brand", "品牌", None, "基本参数"),
        ("model", "型号", None, "基本参数"),
        ("power", "额定功率", "W", "性能"),
        ("capacity", "容量", "L", "容量"),
        ("energy_level", "能效等级", None, "能效"),
        ("noise", "运行噪音", "dB", "性能"),
        ("warranty", "保修期", "年", "售后"),
    ],
    4: [  # 服饰鞋包
        ("brand", "品牌", None, "基本参数"),
        ("model", "款式", None, "基本参数"),
        ("material", "面料材质", None, "材质"),
        ("size", "可选尺码", None, "规格"),
        ("color", "可选颜色", None, "规格"),
        ("weight", "单件重量", "g", "重量"),
        ("season", "适用季节", None, "适用"),
    ],
    5: [  # 食品生鲜
        ("brand", "品牌", None, "基本参数"),
        ("origin", "产地", None, "产地"),
        ("grade", "等级规格", None, "规格"),
        ("net_weight", "净含量", None, "规格"),
        ("storage", "储存方式", None, "保鲜"),
        ("shelf_life", "保质期", None, "保鲜"),
        ("delivery", "配送方式", None, "配送"),
    ],
    6: [  # 图书文娱
        ("publisher", "出版社", None, "出版信息"),
        ("author", "作者", None, "出版信息"),
        ("edition", "版次", None, "出版信息"),
        ("binding", "装帧", None, "规格"),
        ("pages", "页数", "页", "规格"),
        ("isbn", "ISBN", None, "出版信息"),
    ],
    7: [  # 家居家纺
        ("brand", "品牌", None, "基本参数"),
        ("material", "材质", None, "材质"),
        ("size", "适用尺寸", None, "规格"),
        ("color", "可选颜色", None, "规格"),
        ("craft", "工艺", None, "工艺"),
        ("washable", "是否可洗", None, "护理"),
    ],
    8: [  # 办公文具
        ("brand", "品牌", None, "基本参数"),
        ("model", "型号", None, "基本参数"),
        ("material", "材质", None, "材质"),
        ("size", "尺寸", None, "规格"),
        ("feature", "特色功能", None, "功能"),
        ("warranty", "保修期", None, "售后"),
    ],
}


# ============================================================
# 通用 FAQ（不绑定具体商品，适用于所有商品）
# ============================================================
SHOP_FAQ = [
    # 物流
    ("logistics", "多久发货？", "现货商品在您付款后 48 小时内发出，预售商品以商品详情页标注的时间为准。付款后可在「我的订单」查看物流状态。",
     "发货,什么时候发货,几天发货,多久发货", 1),
    ("logistics", "发什么快递？", "默认按下单时系统自动匹配的快递发出，同一订单不换快递。偏远地区可能需要额外 1-2 天。",
     "快递,发什么快递,什么物流,顺丰,圆通", 2),
    ("logistics", "可以指定快递吗？", "暂不支持指定快递，我们按系统自动匹配。如有特殊要求可以联系客服备注，我们会尽量协调。",
     "指定快递,指定顺丰,换快递", 3),
    ("logistics", "支持自提吗？", "目前部分城市支持自提，下单时页面会显示自提选项。自提点与营业时间以页面标注为准。",
     "自提,自己取,自取,到店取", 3),
    # 售后
    ("after_sale", "七天无理由退货吗？", "支持。签收后 7 天内、商品不影响二次销售的情况下可申请无理由退货，运费买家承担。定制类商品除外。",
     "七天无理由,无理由退货,可以退货吗,退货", 1),
    ("after_sale", "怎么申请退款？", "进入「我的订单」找到对应订单，点击「申请售后」选择退款原因并提交，审核通过后 1-3 个工作日原路退回。",
     "退款,怎么退款,申请退款,退钱", 1),
    ("after_sale", "退货运费谁出？", "质量问题导致的退货运费由我们承担；无理由退货的运费由买家承担。寄回时建议保留物流单号。",
     "运费,退货运费,邮费谁出", 2),
    ("after_sale", "换货怎么申请？", "商品有质量问题可申请换货。在订单页选择「申请售后」→ 提交换货申请，上门取件后我们会在收到商品后 48 小时内发出新货。",
     "换货,如何换货,换新的", 2),
    ("after_sale", "商品坏了怎么保修？", "在保修期内出现质量问题，凭订单号申请保修，我们会安排寄修或上门服务。保修期以商品详情页标注为准。",
     "保修,坏了,维修,质量有问题,坏了怎么办", 1),
    # 支付
    ("payment", "支持哪些支付方式？", "支持微信支付、支付宝、银行卡（储蓄卡/信用卡）。企业用户可在订单备注中说明以便对公转账。",
     "支付方式,怎么付款,支付宝,微信支付", 2),
    ("payment", "可以开发票吗？", "支持。订单完成后在「我的订单」点击「申请开票」，填写发票信息，电子发票 1 个工作日内发送到您的邮箱。",
     "发票,开发票,开票,增值税发票", 2),
    ("payment", "订单可以取消吗？", "未发货的订单可在订单页直接取消。已发货订单请先拒收或联系客服处理。",
     "取消订单,能不能取消,撤销订单", 3),
    # 优惠
    ("promotion", "有哪些优惠活动？", "平台定期会有满减、限时折扣、新人券等优惠，以商品详情页与首页活动专区展示为准。",
     "优惠,活动,打折,满减,优惠券", 2),
    ("promotion", "优惠券在哪里领？", "在首页「活动专区」或结算页可领取。优惠券有使用门槛与有效期，下单时系统会自动计算可用券。",
     "优惠券,领券,怎么用券,券在哪", 2),
    # 售前
    ("unknown", "在吗？", "在的，请问有什么可以帮您？可以直接问我商品的具体参数、库存、价格或售后问题。",
     "在吗,你好,有人吗,在不在", 1),
    ("unknown", "能介绍一下这款商品吗？", "可以的。您想了解哪方面？比如核心参数、适用场景、库存状态或者售后政策，我都可以帮您解答。",
     "介绍,介绍一下,详情,讲讲", 1),
    ("unknown", "怎么购买？", "商品详情页点击「加入购物车」或「立即购买」即可下单。有其他问题也可以先问我。",
     "怎么买,怎么购买,怎么下单", 2),
    ("unknown", "便宜点吗？", "目前已经是活动价，页面显示的已是优惠后价格，暂时没有议价空间。可以在活动专区看看有没有可用的优惠券。",
     "便宜,少点,优惠点,打折吗,议价", 3),
]

# 平台级知识（与具体店铺无关）
PLATFORM_KB = [
    ("logistics", "修改收货地址", "订单在未发货状态下可在订单详情页修改收货地址。已发货订单无法修改，请联系快递员协调。",
     "改地址,修改收货地址,地址写错了", 1),
    ("logistics", "支持配送到港澳台吗？", "部分地区支持港澳台配送，下单时会自动显示可用配送方式。大陆偏远地区可能需要额外时间。",
     "港澳台,海外,台湾,香港", 3),
    ("after_sale", "商品有赠品，赠品能退吗？", "赠品需一并退回，缺失赠品可能会影响退款金额。发货时附带的赠品属于商品组成部分。",
     "赠品,送的东西,赠品能退吗", 3),
    ("payment", "付款后多久发货？", "现货商品通常在 48 小时内发出，大促期间可能延迟至 72 小时。预售商品以详情页标注时间为准。",
     "付款后多久,什么时候发货", 1),
    ("promotion", "满减怎么参与？", "订单实付金额达到活动门槛即自动享受满减，无需手动操作。多件商品可合并计算金额。",
     "满减,满多少减多少,怎么算满减", 2),
    ("member", "有会员权益吗？", "平台有会员体系，会员可享积分、专属价与优先发货权益。具体权益以会员中心页面展示为准。",
     "会员,会员权益,vip,积分", 3),
]


# ============================================================
# 从商品的规格/描述里抽取属性
# ============================================================
import re

NUM_RE = re.compile(r"(\d+(?:\.\d+)?)")


def extract_numeric(text, unit):
    """从文本里找出「数字+单位」的数值，用于 value_num 区间查询。

    例如 battery=5000mAh、screen_size=6.7 英寸。

    正则必须与 guess_value 里的完全一致 —— 两处判断不一致会导致
    guess_value 认为抽到了值、这里却返回 NULL，那条属性最终被丢弃。
    负向断言用于避免单位互相误匹配（如 "W" 命中 "mAh" 里的字符）。

    找不到返回 None —— 宁缺勿滥，错的数值比没有更危险。
    """
    if not unit or not text:
        return None
    m = re.search(rf"(?<![\d.])([\d.]+)\s*{re.escape(unit)}(?![a-zA-Z])", text)
    return m.group(1).rstrip(".") if m else None


# 数值型属性的单位映射。缺了这里的条目，属性抽不出数值、无法区间查询。
UNIT_MAP = {
    "screen_size": "英寸", "battery": "mAh", "charging": "W",
    "weight": "g", "thickness": "mm", "ram": "GB", "ssd": "GB",
    "battery_life": "小时", "power": "W", "capacity": "L",
    "noise": "dB", "pages": "页",
}

# 关键词型属性的候选词。按顺序匹配，命中即用。
KEYWORD_MAP = {
    "screen_type": ["OLED", "AMOLED", "LTPO", "LCD", "IPS", "VA", "TFT"],
    "chip": ["骁龙", "天玑", "麒麟", "锐龙", "酷睿"],
    "cpu": ["骁龙", "天玑", "麒麟", "锐龙", "酷睿", "至强"],
    "gpu": ["RTX", "独显", "集显", "核显"],
    "energy_level": ["新一级能效", "一级能效", "二级能效", "三级能效"],
    "material": ["纯棉", "全棉", "长绒棉", "醋酸", "乳胶", "记忆棉",
                 "铝合金", "碳纤维", "PBT", "硅胶", "牛津布", "涤纶", "塑料", "无纺布"],
    "storage": ["冷链", "冷冻", "冷藏", "常温", "真空"],
    "binding": ["精装", "平装", "双色版", "典藏版"],
    "delivery": ["冷链", "空运", "产地直发", "次日达", "仓发"],
    "network": ["5G", "4G", "WiFi 6E", "WiFi 6"],
    "resolution": ["2K", "4K", "1080P", "1.5K", "2.5K"],
    "feature": ["静音", "防水", "快充", "无线", "折叠", "旋转", "变频", "除菌"],
    "craft": ["活性印染", "高低温釉", "刺绣", "压印", "无纺", "三层"],
    "washable": ["机洗", "可水洗", "不可水洗", "手洗"],
    "season": ["春秋", "夏季", "冬季", "四季"],
}


# 各属性的合理数值区间。超出即认为匹配到了别的单位的数字。
#
# 这张表的价值：宁可少抽一条属性，也不能抽错。
# 「手机屏幕 34 英寸」这种错误一旦进库，客服会当成事实回答用户，
# 比「查不到屏幕尺寸」的代价大得多。
VALUE_RANGE = {
    "screen_size": (3.0, 50.0),      # 智能手表 1.4 ~ 曲面显示器 49
    "battery":     (1000.0, 30000.0),  # 充电宝 10000 也合理
    "charging":    (5.0, 300.0),
    "weight":      (20.0, 30000.0),
    "thickness":   (3.0, 30.0),
    "ram":         (4.0, 256.0),
    "ssd":         (128.0, 8000.0),   # 笔记本 SSD 不会低于 128GB
    "battery_life": (3.0, 40.0),
    "power":       (5.0, 6000.0),
    "capacity":    (0.1, 1000.0),
    "noise":       (10.0, 90.0),
    "pages":       (50.0, 3000.0),
}


def guess_value(attr_key, spec_text, subtitle, name):
    """从商品文本推断属性值。推不出来的返回 None —— 不编造。

    错误的属性比没有属性更危险：客服会拿它当事实回答用户。
    """
    blob = f"{name} {subtitle} {spec_text}"

    # 1) 关键词型：命中候选词即返回
    if attr_key in KEYWORD_MAP:
        for k in KEYWORD_MAP[attr_key]:
            if k in blob:
                return k
        return None

    # 2) 数值型：从文本里抽「数字+单位」
    #
    # 关键：必须校验单位前的数值是否落在该属性的合理区间。
    # 同一段文本里 "6.7 英寸 2K ... 165Hz" 都能匹配 "英寸"，
    # 但手机屏幕不可能是 34 英寸 —— 那是显示器的尺寸。
    # 不做区间校验就会出现「手机屏幕 34 英寸」这种明显错误的事实，
    # 而客服会拿它当依据回答用户，比查不到危害大得多。
    if attr_key in UNIT_MAP:
        unit = UNIT_MAP[attr_key]
        # 负向断言：数字前面不能紧邻另一个「数字+其他单位」，
        # 否则 "5000mAh 电池，100W 快充" 查 "W" 时会命中 100W（对的），
        # 但查 "mAh" 时若先遇到 "6.5 英寸" 就会错位。
        # 逐个候选匹配，取最靠前的一个，保证稳定。
        for m in re.finditer(
                rf"(?<![\d.])([\d.]+)\s*{re.escape(unit)}(?![a-zA-Z])", blob):
            val = m.group(1).rstrip(".")
            lo, hi = VALUE_RANGE.get(attr_key, (None, None))
            if lo is None:
                return val
            try:
                fv = float(val)
            except ValueError:
                continue
            # 超出合理区间说明匹配到的是别的单位的数字，跳过继续找
            if lo <= fv <= hi:
                return val
        return None

    # 3) 文本型：从规格里取最有信息量的一段
    if attr_key in ("spec", "size", "net_weight", "grade", "edition", "color"):
        return spec_text.strip() or None

    return None


def main():
    print("=" * 62)
    print("生成智能客服知识库（结构化属性 + 知识块 + 问答对）")
    print("=" * 62)

    # ---------- 读取目标商品 ----------
    # 覆盖全部在售商品，而不只是演示商品。
    # 原因：客服会问任何商品，若种子商品没有知识块，
    # 用户会看到「没检索到相关记录」—— 客服对在售商品答不出来是不可接受的。
    # 排除联调测试残留数据（那些不是真实商品）。
    out = sql("SELECT id, category_id, name, subtitle, price, stock "
              "FROM t_product WHERE status = 1 "
              "  AND name NOT LIKE '联调测试%' AND name <> '枕头' "
              "ORDER BY id;")
    rows = []
    for line in out.split("\n")[1:]:
        parts = line.split("\t")
        if len(parts) >= 6 and parts[0].strip().isdigit():
            rows.append({
                "id": int(parts[0]),
                "cid": int(parts[1]),
                "name": parts[2].strip(),
                "subtitle": parts[3].strip(),
                "price": parts[4].strip(),
                "stock": parts[5].strip(),
            })
    print(f"\n[1/7] 读取商品 {len(rows)} 条")
    if not rows:
        print("  没找到演示商品，请先跑 seed_products.py")
        return

    # 读 SKU 规格
    sku_map = {}
    out = sql("SELECT product_id, spec_text, price, stock FROM t_product_sku ORDER BY product_id, sort_order;")
    for line in out.split("\n")[1:]:
        p = line.split("\t")
        if len(p) >= 4 and p[0].strip().isdigit():
            sku_map.setdefault(int(p[0]), []).append(
                {"spec": p[1].strip(), "price": p[2].strip(), "stock": p[3].strip()})
    print(f"  读取 SKU {sum(len(v) for v in sku_map.values())} 条")

    # ---------- 清理 ----------
    print("\n[2/7] 清理旧知识数据")
    # 必须与读取条件一致（全部在售商品），否则旧块删不干净，
    # 新插入会撞唯一键 (product_id, attr_key) / (product_id, spec_text) 而静默失败
    sql("DELETE FROM t_kb_product_chunk WHERE product_id IN "
        "(SELECT id FROM t_product WHERE status = 1 "
        " AND name NOT LIKE '联调测试%' AND name <> '枕头');")
    sql("DELETE FROM t_product_attr WHERE product_id IN "
        "(SELECT id FROM t_product WHERE status = 1 "
        " AND name NOT LIKE '联调测试%' AND name <> '枕头');")
    # 商品问答按商品清理；通用问答（product_id IS NULL）整体重建。
    # 注意不能写 "WHERE question_kw IS NULL" —— 通用问答都有 question_kw，
    # 那个条件永远匹配不到，导致每次重跑都重复累加。
    sql("DELETE FROM t_kb_faq WHERE product_id IS NULL;")
    sql("DELETE FROM t_kb_faq WHERE product_id IN "
        "(SELECT id FROM t_product WHERE status = 1 "
        " AND name NOT LIKE '联调测试%' AND name <> '枕头');")
    sql("DELETE FROM t_kb_shop_knowledge;")

    # ---------- 结构化属性 ----------
    print("\n[3/7] 生成结构化属性（供精确匹配）")
    attr_rows = []
    attr_names = {"brand": "品牌", "model": "型号"}
    for p in rows:
        specs = ATTR_SPECS.get(p["cid"], [])
        sku_list = sku_map.get(p["id"], [])
        # 品牌与型号直接商品名拆：第一个词是品牌
        # 图书类商品名形如「观澜 《算法导论》 第 4 版 英文原版」，
        # 书名号内是书名不是品牌，不能按空格切第 3 段当型号。
        # 规则：品牌 = 第一个空格前的词；型号 = 剥掉《》后按空格切第 2 段。
        raw = p["name"]
        name_parts = raw.split(" ")
        brand = name_parts[0].replace("《", "").strip() if name_parts else None
        if "《" in raw:
            # 图书：型号取书名（《》内）或版次
            m = re.search(r"《(.+?)》", raw)
            model = m.group(1) if m else None
        else:
            model = name_parts[2] if len(name_parts) > 2 else None
        spec_texts = " / ".join(s["spec"] for s in sku_list)

        specs = list(specs) + [("highlight", "核心亮点", None, "卖点")]
        for order, (key, disp, unit, group) in enumerate(specs):
            if key == "brand":
                value = brand
            elif key == "model":
                value = model
            elif p["cid"] == 6:
                # 图书：author/edition/binding 等只能从书名与规格抽，抽不到就不入库
                value = guess_value(key, spec_texts, p["subtitle"], p["name"])
            elif key in ("size", "color"):
                # 规格维度直接取 SKU 的枚举值
                if key == "color" and any(c in spec_texts for c in
                                          ("黑", "白", "银", "灰", "蓝", "绿", "米", "咖", "雾")):
                    value = spec_texts
                elif key == "size":
                    value = spec_texts
                else:
                    value = None
            elif key == "highlight":
                value = p["subtitle"] or None
            else:
                value = guess_value(key, spec_texts, p["subtitle"], p["name"])

            if not value:
                continue

            vnum = None
            if unit:
                vnum = extract_numeric(f"{p['subtitle']} {spec_texts}", unit)
                if vnum is None:
                    vnum = extract_numeric(str(value), unit)
            if unit and vnum is None:
                # 数值型属性抽不到数值就没有区间查询价值，不入库
                # 但如果 value 本身就是个数字（规格里写的），保留
                m = NUM_RE.match(str(value))
                if m:
                    vnum = m.group(1)
                else:
                    continue

            attr_rows.append(
                f"({p['id']}, {q(key)}, {q(disp)}, {q(str(value))}, "
                f"{vnum if vnum else 'NULL'}, {q(unit)}, {q(group)}, {order})"
            )

    B = 150
    for i in range(0, len(attr_rows), B):
        sql_file("INSERT INTO t_product_attr "
                 "(product_id, attr_key, attr_name, attr_value, value_num, unit, "
                 " group_name, sort_order) VALUES\n"
                 + ",\n".join(attr_rows[i:i + B]) + ";\n")
    print(f"  已插入属性 {len(attr_rows)} 条")

    # ---------- 知识块 ----------
    print("\n[4/7] 生成商品知识块（供语义检索）")
    CAT_NAME = {1: "手机通讯", 2: "电脑办公", 3: "家用电器", 4: "服饰鞋包",
                5: "食品生鲜", 6: "图书文娱", 7: "家居家纺", 8: "办公文具"}

    chunk_rows = []
    for p in rows:
        pid, cid = p["id"], p["cid"]
        sku_list = sku_map.get(pid, [])
        specs = "、".join(s["spec"] for s in sku_list) if sku_list else "单一规格"
        min_price = min((float(s["price"]) for s in sku_list), default=0)
        max_price = max((float(s["price"]) for s in sku_list), default=0)

        def add(ctype, title, content, keywords, weight):
            chunk_rows.append(
                f"({pid}, {cid}, {q(ctype)}, {q(title)}, {q(content)}, "
                f"{q(keywords)}, {weight}, 1)")

        # 块1：规格与价格（客服最常被问的）
        if sku_list:
            spec_detail = "；".join(
                f"{s['spec']} 售价 {float(s['price']):.2f} 元，库存 {s['stock']} 件"
                for s in sku_list)
            add("spec", "规格与价格",
                f"{p['name']}（{CAT_NAME.get(cid, '商品')}）共有 {len(sku_list)} 个规格可选。"
                f"具体为：{spec_detail}。"
                f"价格区间 {min_price:.2f} 元至 {max_price:.2f} 元。"
                f"您告诉我想要的规格，我帮您确认对应价格与库存。",
                "规格,价格,多少钱,价位,版本,选择,几种", 5)

        # 块2：核心参数
        add("spec", "核心参数",
            f"{p['name']}，主要参数：{p['subtitle']}。"
            f"当前售价 {min_price:.2f} 元起，库存 {p['stock']} 件，"
            f"可选择规格有：{specs}。",
            "参数,配置,规格参数,多少,指标", 4)

        # 块3：适用人群与场景（按品类给不同说法）
        scene = {
            1: "适合日常通勤、拍照记录与影音娱乐用户。续航与快充能满足一天重度使用，适合经常出差的商务人士。",
            2: "适合学生、上班族与居家办公用户。配置可满足日常办公、编程开发与影音剪辑等负载。",
            3: "适合家庭日常使用。参数以耐用、省电为主，日常运行噪音控制良好，夜间使用不影响休息。",
            4: "适合日常穿着与通勤搭配。建议按尺码表选择，版型正常，介意见购前咨询客服。",
            5: "适合家庭日常食用与日常送礼。下单后按承诺时效发货，注意按标示方式保存。",
            6: "适合阅读与学习使用。内容与版本信息见商品详情，印刷质量符合出版标准。",
            7: "适合家庭日常使用。材质与工艺见详情说明，清洁保养方式已标注。",
            8: "适合学生、办公与居家场景。具体规格与功能见下方参数说明。",
        }.get(cid, "适合日常使用。")
        add("usecase", "适用人群与使用场景",
            f"{p['name']}。{scene}"
            f"如果您在犹豫是否适合自己，可以告诉我具体的使用场景"
            f"（比如预算、使用频率、是否需要某项特定功能），我给您更明确的建议。",
            "适合,适用,人群,场景,什么人,怎么用,怎么选,建议", 3)

        # 块4：售后与保修
        add("warranty", "售后与保修",
            f"{p['name']} 支持七天无理由退货，签收后 7 天内、商品不影响二次销售即可申请，"
            f"质量问题导致的退货运费由商家承担，无理由退货运费由买家承担。"
            f"保修期内出现质量问题可申请寄修或上门服务，保修期以商品详情页标注为准。"
            f"操作路径：我的订单 → 找到对应订单 → 申请售后 → 选择原因提交。"
            f"审核通过后 1-3 个工作日原路退回。",
            "售后,保修,退货,维修,质量,换货,怎么退,运费", 3)

        # 块5：库存与发货
        add("stock", "库存与发货",
            f"{p['name']} 当前库存 {p['stock']} 件，"
            f"商品状态在售，下单后库存会实时扣减。"
            f"现货商品在您付款后 48 小时内发出，大促期间可能延迟至 72 小时；"
            f"发货后可在「我的订单」查看物流单号与实时位置。"
            f"如果您想确认某个规格是否还有货，可以直接告诉我规格名称，我帮您看。",
            "库存,发货,有没有货,现货,什么时候到,还有货吗,几天到", 4)

    B = 100
    for i in range(0, len(chunk_rows), B):
        sql_file("INSERT INTO t_kb_product_chunk "
                 "(product_id, category_id, chunk_type, title, content, "
                 " keywords, weight, status) VALUES\n"
                 + ",\n".join(chunk_rows[i:i + B]) + ";\n")
    print(f"  已插入知识块 {len(chunk_rows)} 条（每商品 {len(chunk_rows)//max(len(rows),1)} 块）")

    # ---------- 商品级 FAQ ----------
    print("\n[5/7] 生成商品问答对")
    faq_rows = []
    for p in rows:
        pid = p["id"]
        sku_list = sku_map.get(pid, [])
        specs = "、".join(s["spec"] for s in sku_list) if sku_list else "单一规格"
        min_price = min((float(s["price"]) for s in sku_list), default=0)

        def addf(product_id, cat_id, question, answer, kw, intent, pr):
            faq_rows.append(
                f"({product_id if product_id else 'NULL'}, "
                f"{cat_id if cat_id else 'NULL'}, {q(question)}, {q(answer)}, "
                f"{q(kw)}, {q(intent)}, {pr}, 0, 1)")

        addf(pid, p["cid"], f"{p['name']}多少钱？",
             f"{p['name']}售价 {min_price:.2f} 元起。不同规格价格不同，"
             f"当前可选规格有：{specs}。",
             f"多少钱,价格,报价,价钱,{p['name'][:8]}", "price", 1)

        addf(pid, p["cid"], f"{p['name']}有货吗？",
             f"有货。当前库存 {p['stock']} 件，现货商品付款后 48 小时内发出。",
             f"有货吗,库存,还有货吗,现货,{p['name'][:8]}", "stock", 1)

        addf(pid, p["cid"], f"{p['name']}有哪些规格？",
             f"共有 {len(sku_list)} 个规格：{specs}。可在商品详情页直接选择。",
             f"规格,型号,版本,选择,{p['name'][:8]}", "spec", 1)

        addf(pid, p["cid"], f"{p['name']}的主要参数是什么？",
             f"{p['name']}的主要参数：{p['subtitle']}。完整参数可在商品详情页查看。",
             f"参数,配置,参数是什么,{p['name'][:8]}", "spec", 2)

    B = 100
    for i in range(0, len(faq_rows), B):
        sql_file("INSERT INTO t_kb_faq "
                 "(product_id, category_id, question, answer, question_kw, "
                 " intent, priority, hit_count, status) VALUES\n"
                 + ",\n".join(faq_rows[i:i + B]) + ";\n")
    print(f"  已插入商品问答 {len(faq_rows)} 条")

    # ---------- 通用 FAQ ----------
    print("\n[6/7] 生成通用问答与店铺知识")
    gen_rows = []
    for topic, question, answer, kw, pr in SHOP_FAQ:
        gen_rows.append(
            f"(NULL, NULL, {q(question)}, {q(answer)}, {q(kw)}, "
            f"{q('unknown') if topic == 'unknown' else q(topic)}, {pr}, 0, 1)")
    B = 100
    for i in range(0, len(gen_rows), B):
        sql_file("INSERT INTO t_kb_faq "
                 "(product_id, category_id, question, answer, question_kw, "
                 " intent, priority, hit_count, status) VALUES\n"
                 + ",\n".join(gen_rows[i:i + B]) + ";\n")
    print(f"  已插入通用问答 {len(gen_rows)} 条")

    kb_rows = []
    for topic, question, answer, kw, pr in PLATFORM_KB:
        kb_rows.append(
            f"(NULL, {q(topic)}, {q(question)}, {q(answer)}, {q(kw)}, {pr}, 1)")
    sql_file("INSERT INTO t_kb_shop_knowledge "
             "(merchant_id, topic, question, answer, keywords, weight, status) "
             "VALUES\n" + ",\n".join(kb_rows) + ";\n")
    print(f"  已插入平台知识 {len(kb_rows)} 条")

    # ---------- 校验 ----------
    print("\n[7/7] 校验")
    checks = [
        ("知识块总数", "SELECT COUNT(*) FROM t_kb_product_chunk;"),
        ("覆盖商品数", "SELECT COUNT(DISTINCT product_id) FROM t_kb_product_chunk;"),
        ("结构化属性", "SELECT COUNT(*) FROM t_product_attr;"),
        ("可区间查询属性", "SELECT COUNT(*) FROM t_product_attr WHERE value_num IS NOT NULL;"),
        ("商品问答", "SELECT COUNT(*) FROM t_kb_faq WHERE product_id IS NOT NULL;"),
        ("通用问答", "SELECT COUNT(*) FROM t_kb_faq WHERE product_id IS NULL;"),
        ("平台知识", "SELECT COUNT(*) FROM t_kb_shop_knowledge;"),
    ]
    ok = True
    for label, query in checks:
        n = first_int(query)
        print(f"  {label}: {n}")
        if n <= 0:
            ok = False

    if not ok or first_int("SELECT COUNT(DISTINCT product_id) FROM t_kb_product_chunk;") != len(rows):
        print("\n  [失败] 知识库不完整")
        return 1

    print("\n  按块类型分布:")
    out = sql("SELECT chunk_type, COUNT(*) FROM t_kb_product_chunk GROUP BY chunk_type;")
    for line in out.split("\n")[1:]:
        p = line.split("\t")
        if len(p) >= 2 and p[0].strip():
            print(f"    {p[0].strip()}: {p[1].strip()}")

    print("\n  按意图分布（FAQ）:")
    out = sql("SELECT intent, COUNT(*) FROM t_kb_faq GROUP BY intent ORDER BY COUNT(*) DESC;")
    for line in out.split("\n")[1:]:
        p = line.split("\t")
        if len(p) >= 2 and p[0].strip():
            print(f"    {p[0].strip()}: {p[1].strip()}")

    print("\n" + "=" * 62)
    print("完成。四个知识来源可独立更新：")
    print("  t_product_attr        结构化属性，精确匹配与区间筛选")
    print("  t_kb_product_chunk    知识块，BM25 全文 + 向量语义")
    print("  t_kb_faq              问答对，标准问答")
    print("  t_kb_shop_knowledge   物流售后等通用知识")
    print("=" * 62)


if __name__ == "__main__":
    raise SystemExit(main() or 0)
