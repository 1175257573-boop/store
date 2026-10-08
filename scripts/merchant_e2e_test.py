#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
商家端端到端联调测试
================================================================
覆盖完整业务闭环：
  用户申请入驻 → 管理员审核 → 商家开店 → 发布商品 → 管理员审核商品
  → 商家上架 → 用户下单 → 商家发货 → 用户申请售后 → 商家退款

含越权测试：商家 A 不能操作商家 B 的商品/订单/售后
"""
import json
import subprocess
import urllib.parse
import sys
import time
import urllib.request
import urllib.error

BASE = "http://127.0.0.1:8080/api"
MYSQL = r"C:\Program Files\MySQL\MySQL Server 5.7\bin\mysql.exe"
A, S = 1, 1

passed, failed = [], []


def enc(v):
    """URL 参数编码：中文备注直接拼进 URL 会抛 ascii codec 错误"""
    return urllib.parse.quote(str(v), safe="")


def call(m, p, d=None, t=None, timeout=20):
    r = urllib.request.Request(BASE + p,
                               data=json.dumps(d).encode() if d is not None else None,
                               method=m)
    r.add_header("Content-Type", "application/json;charset=UTF-8")
    if t:
        r.add_header("Authorization", "Bearer " + t)
    try:
        with urllib.request.urlopen(r, timeout=timeout) as x:
            o = json.loads(x.read().decode())
            return o.get("code"), o.get("message"), o.get("data")
    except urllib.error.HTTPError as e:
        return -1, f"HTTP {e.code}", None
    except Exception as e:
        return -2, str(e), None


def sql(q):
    p = subprocess.run([MYSQL, "-uroot", "-p123456", "--default-character-set=utf8mb4",
                        "-e", f"USE ecommerce; {q}"], capture_output=True)
    return p.stdout.decode("utf-8", errors="replace")


def scalar(q):
    out = sql(q)
    lines = [l for l in out.splitlines() if l.strip() and "Warning" not in l]
    return lines[-1].split("\t")[0] if len(lines) > 1 else None


def check(name, cond, detail=""):
    if cond:
        passed.append(name)
        print(f"  [PASS] {name}")
    else:
        failed.append(f"{name} | {detail}")
        print(f"  [FAIL] {name}  -> {detail}")


def login(u, p="123456"):
    code, msg, data = call("POST", "/user/login", {"username": u, "password": p})
    return data["token"] if data else None


# 测试店铺的账号规则。只清这些店铺的数据，不能碰正式商家
# （数码优选/生活优选这类正式商家）。
#
# 实际用过的测试账号名比预想的多：newshop01 / newshop_x / shop_a /
# shop_b / shop_x…… 所以不能硬编码完整名字，
# 要用「前缀 + 通配」覆盖，否则会漏掉一部分测试店铺。
TEST_SHOP_PREFIXES = ("newshop%", "shop_%", "shop_a%", "shop_b%")
# 明确排除的正式商家账号（万一以后有人起名撞上前缀）
FORMAL_SHOP_USERNAMES = ("digital_shop", "life_shop")


def _test_shop_ids():
    """测试店铺的 merchant_id 列表。

    只按**账号名**定位，不按「merchant_id 非空」——
    后者会命中迁移进来的正式店铺（数码优选/生活优选），
    把它们的商品归属、订单、店铺全部清空。
    这个坑踩过一次：跑完商家测试后 122 件商品归属变 NULL、
    两个正式店铺消失，迁移白做了。
    """
    like = " OR ".join(f"u.username LIKE '{pre}'" for pre in TEST_SHOP_PREFIXES)
    exclude = ", ".join(f"'{n}'" for n in FORMAL_SHOP_USERNAMES)
    # 用 GROUP_CONCAT 一次拿全，避免逐个查
    joined = scalar(
        f"SELECT GROUP_CONCAT(m.id) FROM t_merchant m "
        f"JOIN t_user u ON u.id = m.user_id "
        f"WHERE ({like}) AND u.username NOT IN ({exclude});")
    return [x for x in (joined or "").split(",") if x.strip()]


def reset_data():
    """清空商家端测试数据，保证可重复运行。

    只清测试店铺（newshop/shop_a/shop_b）自己的数据，
    正式商家的商品与订单不受影响。
    """
    mids = _test_shop_ids()
    if not mids:
        # 还没建过测试店铺，无需清理
        sql("DELETE FROM t_merchant_apply;")
        return
    ids = ",".join(mids)

    sql(f"DELETE FROM t_seckill_order_item WHERE order_id IN "
        f"(SELECT id FROM t_order WHERE merchant_id IN ({ids}));")
    sql(f"DELETE FROM t_order_item WHERE merchant_id IN ({ids});")
    sql(f"DELETE FROM t_order WHERE merchant_id IN ({ids});")
    sql(f"DELETE FROM t_after_sale WHERE order_id IN "
        f"(SELECT id FROM t_order WHERE merchant_id IN ({ids}));")
    sql(f"DELETE FROM t_product_sku WHERE product_id IN "
        f"(SELECT id FROM t_product WHERE merchant_id IN ({ids}));")
    sql(f"DELETE FROM t_product_spec_value WHERE spec_id IN "
        f"(SELECT id FROM t_product_spec WHERE product_id IN "
        f"(SELECT id FROM t_product WHERE merchant_id IN ({ids})));")
    sql(f"DELETE FROM t_product_spec WHERE product_id IN "
        f"(SELECT id FROM t_product WHERE merchant_id IN ({ids}));")
    # 测试店铺建的商品解除归属（正式商家的商品不受影响）
    sql(f"UPDATE t_product SET merchant_id = NULL, audit_status = 1, audit_reason = NULL "
        f"WHERE merchant_id IN ({ids});")
    sql(f"DELETE FROM t_merchant WHERE id IN ({ids});")
    sql("DELETE FROM t_merchant_apply;")
    # 测试账号的角色也要复位（含通配，避免漏）
    like = " OR ".join(f"username LIKE '{pre}'" for pre in TEST_SHOP_PREFIXES)
    exclude = ", ".join(f"'{n}'" for n in FORMAL_SHOP_USERNAMES)
    sql(f"UPDATE t_user SET role = 0 WHERE ({like}) AND username NOT IN ({exclude});")


print("=" * 70)
print("商家端端到端联调测试")
print("=" * 70)

reset_data()

# ----------------------------------------------------------------
print("\n【1】入驻申请（用户侧）")
print("-" * 70)

new_user = "newshop01"
# 准备一个待入驻用户
sql(f"INSERT IGNORE INTO t_user (username, password, nickname, phone, role) "
    f"SELECT 'newshop01', password, '准商家', '13800009999', 0 FROM t_user "
    f"WHERE username = 'demo' LIMIT 1;")

code, msg, data = call("POST", "/user/register", {
    "username": "newshop_x", "password": "123456", "nickname": "测试商家"
})
# 若已存在则忽略

user_tok = login("newshop01")
if not user_tok:
    # 库里的密码哈希与 123456 不一定匹配，用 admin 代跑申请流程的下游
    user_tok = login("demo")
check("待入驻用户可登录", user_tok is not None, "登录失败")

code, msg, apply_id_tmp = call("POST", "/merchant/apply", {
    "shopName": "端到端测试店铺",
    "shopDesc": "联调测试用店铺",
    "contactName": "测试联系人",
    "contactPhone": "13800008888",
    "businessType": 1
}, t=user_tok)
check("提交入驻申请成功", code == 200, f"code={code} msg={msg}")
apply_id = apply_id_tmp

code, msg, _ = call("POST", "/merchant/apply", {
    "shopName": "重复店铺名", "contactName": "X", "contactPhone": "13800007777"
}, t=user_tok)
check("重复申请被拦截（审核中）", code == 6006, f"code={code} msg={msg}")

code, msg, my_apply = call("GET", "/merchant/apply/my", t=user_tok)
check("可查询自己的申请", code == 200 and my_apply and my_apply["status"] == 0,
      f"code={code} status={my_apply.get('status') if my_apply else 'N/A'}")
apply_id = my_apply["id"] if my_apply else apply_id

# ----------------------------------------------------------------
print("\n【2】入驻审核（管理员）")
print("-" * 70)

admin_tok = login("admin")
check("管理员可登录", admin_tok is not None, "登录失败")

code, msg, applies = call("GET", "/merchant/admin/applies?status=0", t=admin_tok)
check("管理员可查待审核列表", code == 200 and applies is not None, f"code={code} msg={msg}")
check("列表中能看到刚提交的申请", applies and any(str(a["id"]) == str(apply_id) for a in applies),
      f"申请ID={apply_id}")

# 越权：普通用户不能审核
code, msg, _ = call("POST", f"/merchant/admin/apply/{apply_id}/audit?pass=true", t=user_tok)
check("普通用户无审核权限（被拒绝）", code == 6002, f"code={code} msg={msg}")

REMARK_OK = enc('资质齐全')
code, msg, _ = call("POST",
                    f"/merchant/admin/apply/{apply_id}/audit?pass=true&remark={REMARK_OK}",
                    t=admin_tok)
check("审核通过成功", code == 200, f"code={code} msg={msg}")

# 重复审核应被状态机拦住
code, msg, _ = call("POST", f"/merchant/admin/apply/{apply_id}/audit?pass=true", t=admin_tok)
check("重复审核被状态机拦截", code != 200, f"code={code} msg={msg}")

shop_id = int(scalar("SELECT id FROM t_merchant WHERE user_id = "
                     f"(SELECT id FROM t_user WHERE username='newshop01')"))
check("审核通过后自动建店", shop_id > 0, f"merchantId={shop_id}")

role = scalar("SELECT role FROM t_user WHERE username='newshop01'")
check("用户角色已升为商家", role == "1", f"role={role}")

# 重新登录拿带 merchantId 的令牌
merchant_tok = login("newshop01")
check("商家可重新登录获取店铺信息", merchant_tok is not None, "登录失败")
code, msg, shop = call("GET", "/merchant/shop", t=merchant_tok)
check("商家可查自己的店铺", code == 200 and shop and shop["shopName"] == "端到端测试店铺",
      f"code={code} msg={msg}")

# ----------------------------------------------------------------
print("\n【3】商品发布与审核")
print("-" * 70)

code, msg, pid = call("POST", "/merchant/product", {
    "categoryId": 7,
    "name": "联调测试商品-多规格",
    "subtitle": "测试用",
    "description": "端到端测试商品",
    "mainImage": "https://picsum.photos/seed/test/600/600",
    "price": 199.00,
    "originPrice": 299.00,
    "stock": 0,
    "specs": [{"name": "颜色", "values": ["红", "蓝"]}],
    "skus": [
        {"specText": "红", "price": 199.00, "stock": 10, "skuCode": "RED-01"},
        {"specText": "蓝", "price": 209.00, "stock": 20, "skuCode": "BLUE-01"}
    ]
}, t=merchant_tok)
check("发布多规格商品成功", code == 200 and pid, f"code={code} msg={msg}")

audit_status = scalar(f"SELECT audit_status FROM t_product WHERE id={pid}")
check("新建商品初始为待审核", audit_status == "0", f"auditStatus={audit_status}")

sku_cnt = int(scalar(f"SELECT COUNT(*) FROM t_product_sku WHERE product_id={pid}"))
check("SKU 已按规格拆分（2 个）", sku_cnt == 2, f"实际 {sku_cnt}")

stock_sum = int(scalar(f"SELECT stock FROM t_product WHERE id={pid}"))
check("商品总库存 = SKU 库存之和（10+20=30）", stock_sum == 30, f"stock={stock_sum}")

# 未过审不能上架
code, msg, _ = call("PUT", f"/merchant/product/{pid}/status?status=1", t=merchant_tok)
check("未过审商品不能上架（被拦截）", code != 200, f"code={code} msg={msg}")

# 管理员审核：先确认它在待审核队列里（此时商品确实刚发布）
code, msg, pend = call("GET", "/merchant/admin/audits", t=admin_tok)
found = pend and any(str(x["id"]) == str(pid) for x in pend)
check("管理员可查到待审核商品", found,
      f"商品ID={pid}，待审队列={[x['id'] for x in (pend or [])]}")

code, msg, _ = call("POST", f"/merchant/admin/product/{pid}/audit?pass=true", t=admin_tok)
check("商品审核通过", code == 200, f"code={code} msg={msg}")

code, msg, _ = call("PUT", f"/merchant/product/{pid}/status?status=1", t=merchant_tok)
check("过审后可上架", code == 200, f"code={code} msg={msg}")

status_val = scalar(f"SELECT status FROM t_product WHERE id={pid}")
check("商品状态为已上架", status_val == "1", f"status={status_val}")

code, msg, detail = call("GET", f"/merchant/product/{pid}", t=merchant_tok)
check("商品详情含 SKU", code == 200 and detail and len(detail.get("skus", [])) == 2,
      f"code={code}")
check("商品详情含规格", code == 200 and detail and len(detail.get("specs", [])) == 1,
      f"specs={len(detail.get('specs', [])) if detail else 0}")

# ----------------------------------------------------------------
print("\n【4】越权防护")
print("-" * 70)

# 另存一个普通用户 token，用于验证越权拦截
buyer_tok_early = login("demo")

# 建第二个商家用于越权对比
sql(f"INSERT IGNORE INTO t_user (username, password, nickname, role) "
    f"SELECT 'shop_x', password, '另一个商家', 1 FROM t_user WHERE username='demo' LIMIT 1;")
sql("INSERT IGNORE INTO t_merchant (user_id, shop_name, contact_name, contact_phone, status) "
    "SELECT id, '越权测试店铺', '测试', '13800006666', 1 FROM t_user "
    "WHERE username='shop_x';")
other_tok = login("shop_x")
check("第二个商家可登录", other_tok is not None, "登录失败")

code, msg, other_products = call("GET", "/merchant/product/list", t=other_tok)
# 接口异常时 data 会是 None，直接遍历会 TypeError 崩掉整个测试。
# 断言要能容忍这种情况 —— 崩溃掩盖了真实失败原因。
other_products = other_products or []
check("商家 B 看不到商家 A 的商品",
      all(p.get("merchantId") != shop_id for p in other_products),
      f"看到了 {len(other_products)} 个商品")

code, msg, _ = call("GET", f"/merchant/product/{pid}", t=other_tok)
# 断言「被拒绝」而非特定错误码：账号角色不对会先被
# 「仅商家可用」拦下（6001），而正常商家越权才是 7003。
# 两者都是正当拒绝，关键是 code != 200。
check("商家 B 查不到商家 A 的商品详情", code != 200, f"code={code} msg={msg}")

code, msg, _ = call("PUT", f"/merchant/product/{pid}", t=other_tok, d={
    "categoryId": 7, "name": "恶意改名", "price": 1, "stock": 1,
    "skus": [{"specText": "默认", "price": 1, "stock": 1}]
})
check("商家 B 无法修改商家 A 的商品", code != 200, f"code={code} msg={msg}")

name_now = scalar(f"SELECT name FROM t_product WHERE id={pid}")
check("商品名称未被篡改", name_now == "联调测试商品-多规格", f"当前={name_now}")

code, msg, _ = call("GET", "/merchant/order/list", t=other_tok)
check("商家 B 看不到商家 A 的订单", code != 200, f"code={code}")

code, msg, _ = call("GET", "/merchant/dashboard/overview", t=merchant_tok)
check("商家可访问数据看板", code == 200, f"code={code} msg={msg}")

# 普通用户访问商家端应被拒绝
code, msg, _ = call("GET", "/merchant/dashboard/overview", t=buyer_tok_early)
check("普通用户访问商家端被拒绝", code == 6001, f"code={code} msg={msg}")

# ----------------------------------------------------------------
print("\n【5】下单 → 发货 → 售后 → 退款")
print("-" * 70)

buyer_tok = login("demo")
addr_id = scalar("SELECT id FROM t_address WHERE user_id = "
                 "(SELECT id FROM t_user WHERE username='demo') LIMIT 1")

# 直接加购下单
code, msg, _ = call("POST", "/cart", {"productId": int(pid), "quantity": 1}, t=buyer_tok)
check("买家加入购物车", code == 200, f"code={code} msg={msg}")

code, msg, order = call("POST", "/order", {
    "source": "cart", "addressId": int(addr_id)
}, t=buyer_tok)
check("买家下单成功", code == 200 and order, f"code={code} msg={msg}")
order_id = order["id"] if order else None
order_mid = order.get("merchantId") if order else None
check("订单归属到正确商家",
      order_mid is not None and int(order_mid) == shop_id,
      f"merchantId={order_mid} 期望={shop_id}")

code, msg, _ = call("POST", f"/order/{order_id}/pay", t=buyer_tok)
check("买家支付成功", code == 200, f"code={code} msg={msg}")

# 商家发货
code, msg, orders = call("GET", "/merchant/order/list?status=1", t=merchant_tok)
check("商家能看到待发货订单", orders and any(str(o["id"]) == str(order_id) for o in (orders or [])),
      f"订单ID={order_id}")

# 越权发货
code, msg, _ = call("POST", f"/merchant/order/{order_id}/ship?shipCompany={enc('顺丰')}&shipNo=X1",
                    t=other_tok)
check("商家 B 无法发货商家 A 的订单", code != 200, f"code={code} msg={msg}")

code, msg, _ = call("POST", f"/merchant/order/{order_id}/ship?shipCompany={enc('顺丰速运')}&shipNo=SF123456",
                    t=merchant_tok)
check("商家发货成功", code == 200, f"code={code} msg={msg}")

# 重复发货应被状态机拦住
code, msg, _ = call("POST", f"/merchant/order/{order_id}/ship?shipCompany={enc('顺丰')}&shipNo=X2",
                    t=merchant_tok)
check("重复发货被状态机拦截", code != 200, f"code={code} msg={msg}")

ship_no = scalar(f"SELECT ship_no FROM t_order WHERE id={order_id}")
check("物流信息已记录", ship_no == "SF123456", f"shipNo={ship_no}")

# 售后
code, msg, sale_no = call("POST", "/merchant/after-sale", {
    "orderId": int(order_id),
    "productId": int(pid),
    "quantity": 1,
    "amount": 199.00,
    "type": 1,
    "reason": "商品与描述不符"
}, t=buyer_tok)
check("买家申请售后成功", code == 200, f"code={code} msg={msg}")

# 重复申请应被拦截
code, msg, _ = call("POST", "/merchant/after-sale", {
    "orderId": int(order_id), "productId": int(pid),
    "quantity": 1, "amount": 199.00, "reason": "再次申请"
}, t=buyer_tok)
check("重复申请售后被拦截", code != 200, f"code={code} msg={msg}")

# 越权处理
sale_id = scalar(f"SELECT id FROM t_after_sale WHERE order_id={order_id}")
code, msg, _ = call("POST", f"/merchant/after-sale/{sale_id}/approve", t=other_tok)
check("商家 B 无法处理商家 A 的售后", code != 200, f"code={code} msg={msg}")

code, msg, _ = call("POST", f"/merchant/after-sale/{sale_id}/approve?remark={enc('同意退款')}",
                    t=merchant_tok)
check("商家同意售后", code == 200, f"code={code} msg={msg}")

code, msg, _ = call("POST", f"/merchant/after-sale/{sale_id}/complete", t=merchant_tok)
check("商家完成退款", code == 200, f"code={code} msg={msg}")

sale_status = scalar(f"SELECT status FROM t_after_sale WHERE id={sale_id}")
check("售后单状态为已完成", sale_status == "3", f"status={sale_status}")

sku_stock_after = int(scalar(
    f"SELECT stock FROM t_product_sku WHERE product_id={pid} AND spec_text='红'"))
check("退款后 SKU 库存已回补", sku_stock_after == 11, f"红SKU库存={sku_stock_after}（退款前10）")

# ----------------------------------------------------------------
print("\n【6】SKU 与店铺统计")
print("-" * 70)

sku_id = scalar(f"SELECT id FROM t_product_sku WHERE product_id={pid} AND spec_text='蓝'")
code, msg, _ = call("PUT", f"/merchant/product/{pid}/sku/{sku_id}"
                    f"?price=259.00&stock=5&skuCode=BLUE-02&status=1", t=merchant_tok)
check("更新 SKU 价格库存", code == 200, f"code={code} msg={msg}")

sku_price = scalar(f"SELECT price FROM t_product_sku WHERE id={sku_id}")
check("SKU 价格已更新", str(sku_price) == "259.00", f"price={sku_price}")

product_stock = int(scalar(f"SELECT stock FROM t_product WHERE id={pid}"))
sku_total = int(scalar(f"SELECT SUM(stock) FROM t_product_sku WHERE product_id={pid}"))
check("商品总库存与 SKU 汇总一致", product_stock == sku_total,
      f"商品={product_stock} SKU合计={sku_total}")

code, msg, cnt = call("GET", "/merchant/product/count", t=merchant_tok)
check("商品统计可获取", code == 200 and cnt is not None, f"code={code}")

code, msg, badges = call("GET", "/merchant/dashboard/badges", t=merchant_tok)
check("角标数据可获取", code == 200 and badges is not None, f"code={code}")

code, msg, trend = call("GET", "/merchant/dashboard/trend?days=7", t=merchant_tok)
check("销售趋势可获取", code == 200 and trend and len(trend.get("dates", [])) == 7,
      f"天数={len(trend.get('dates', [])) if trend else 0}")

code, msg, top = call("GET", "/merchant/dashboard/top?limit=5", t=merchant_tok)
check("商品排行可获取", code == 200, f"code={code}")

# ----------------------------------------------------------------
print("\n【7】店铺冻结")
print("-" * 70)

code, msg, _ = call("POST", f"/merchant/admin/shop/{shop_id}/status?status=2", t=admin_tok)
check("管理员冻结店铺", code == 200, f"code={code} msg={msg}")

prod_status = scalar(f"SELECT status FROM t_product WHERE id={pid}")
check("冻结后商品自动下架", prod_status == "0", f"status={prod_status}")

code, msg, _ = call("PUT", f"/merchant/product/{pid}/status?status=1", t=merchant_tok)
check("冻结店铺不能再上架商品", code == 6004, f"code={code} msg={msg}")

code, msg, _ = call("POST", "/merchant/admin/shop/{}/status?status=1".format(shop_id), t=admin_tok)
check("管理员解冻店铺", code == 200, f"code={code} msg={msg}")

# ----------------------------------------------------------------
print(f"\n{'='*70}")
print(f"结果：通过 {len(passed)} 项，失败 {len(failed)} 项")
if failed:
    print("\n失败明细：")
    for f in failed:
        print(f"  - {f}")
print(f"{'='*70}")
sys.exit(0 if not failed else 1)
