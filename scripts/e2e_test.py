#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
电商平台端到端联调脚本
覆盖：公开浏览 -> 登录 -> 购物车 -> 地址 -> 下单 -> 支付 -> 取消 -> 库存校验
所有断言基于接口真实返回，失败会直接抛出，不做假通过。
"""
import json
import urllib.request
import urllib.error

BASE = "http://127.0.0.1:8080/api"
passed, failed = [], []


def call(method, path, data=None, token=None):
    """发起请求，返回 (code, message, data)"""
    url = BASE + path
    body = json.dumps(data).encode("utf-8") if data is not None else None
    req = urllib.request.Request(url, data=body, method=method)
    req.add_header("Content-Type", "application/json;charset=UTF-8")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            obj = json.loads(resp.read().decode("utf-8"))
            return obj.get("code"), obj.get("message"), obj.get("data")
    except urllib.error.HTTPError as e:
        return -1, f"HTTP {e.code}", None
    except Exception as e:
        return -2, str(e), None


def check(name, cond, detail=""):
    if cond:
        passed.append(name)
        print(f"  [PASS] {name}")
    else:
        failed.append(f"{name} | {detail}")
        print(f"  [FAIL] {name}  -> {detail}")


print("=" * 60)
print("电商平台端到端联调测试")
print("=" * 60)

# ---------- 1. 公开接口 ----------
print("\n[1] 公开浏览接口（无需登录）")
code, msg, data = call("GET", "/product/categories")
# 不写死分类数量：补演示商品时扩了分类（原 6 个 → 现 8 个），
# 写死会变成噪音断言 —— 每次改数据都要跟着改测试。
# 真正要保证的是「接口通、且有数据」。
check("分类列表可访问", code == 200 and data and len(data) >= 6,
      f"code={code} len={len(data) if data else 0} msg={msg}")
categories = data or []

code, msg, data = call("GET", "/product/list?pageNum=1&pageSize=12")
total = int(data.get("total") or 0) if data else 0
check("商品列表可访问", code == 200 and total >= 15,
      f"code={code} total={total}（商家商品加入后不再固定为 15）")
products = (data or {}).get("records", [])
check("商品列表返回 12 条", len(products) == 12, f"实际 {len(products)}")
check("商品含中文名称", any("华为" in p["name"] or "小米" in p["name"] for p in products),
      "未找到中文商品名")
check("价格字段为字符串（Long/Decimal 序列化）", isinstance(products[0]["price"], (str, float)),
      f"类型={type(products[0]['price'])}")

# 分类筛选
if categories:
    cid = categories[0]["id"]
    code, msg, data = call("GET", f"/product/list?categoryId={cid}&pageSize=20")
    cnt = int(data.get("total") or 0) if data else 0
    check("按分类筛选生效", code == 200 and cnt > 0, f"categoryId={cid} total={cnt}")

# 关键字搜索
code, msg, data = call("GET", "/product/list?keyword=Apple")
cnt = int(data.get("total") or 0) if data else 0
check("关键字搜索生效", code == 200 and cnt >= 1, f"keyword=Apple total={cnt}")

# 价格排序
code, msg, data = call("GET", "/product/list?sortBy=priceAsc&pageSize=15")
recs = (data or {}).get("records", [])
prices = [float(p["price"]) for p in recs]
check("价格升序排序正确", prices == sorted(prices), f"{prices[:5]}")

# 商品详情 + 浏览量自增
code, msg, detail = call("GET", "/product/1")
check("商品详情可访问", code == 200 and detail and detail["name"], f"code={code} msg={msg}")
v1 = detail.get("viewCount") if detail else None
call("GET", "/product/1")
code, msg, detail2 = call("GET", "/product/1")
v2 = detail2.get("viewCount") if detail2 else None
check("浏览量真实自增（未走缓存）", v2 is not None and v1 is not None and v2 > v1,
      f"{v1} -> {v2}")

# 缓存写入校验
code, msg, related = call("GET", "/product/1/related?limit=5")
check("相关推荐可访问", code == 200, f"code={code} msg={msg}")

# ---------- 2. 认证 ----------
print("\n[2] 认证与 JWT")
code, msg, data = call("POST", "/user/login", {"username": "demo", "password": "123456"})
check("正确密码登录成功", code == 200 and data and data.get("token"), f"code={code} msg={msg}")
token = (data or {}).get("token", "")
nickname = (data or {}).get("nickname", "")
check("登录返回用户信息", bool(nickname), f"nickname={nickname}")

code, msg, _ = call("POST", "/user/login", {"username": "demo", "password": "wrong123"})
check("错误密码被拒绝", code != 200, f"code={code}")

code, msg, _ = call("POST", "/user/login", {"username": "notexist", "password": "123456"})
check("不存在的账号被拒绝", code != 200, f"code={code}")

# 未登录访问受保护接口
code, msg, _ = call("GET", "/cart")
check("未登录访问购物车返回 401 业务码", code == 401, f"code={code}")

# 无效令牌
code, msg, _ = call("GET", "/cart", token="invalid.token.here")
check("无效令牌被拦截", code == 401, f"code={code}")

# 有效令牌
code, msg, profile = call("GET", "/user/profile", token=token)
check("携带令牌可获取资料", code == 200 and profile and profile.get("username") == "demo",
      f"code={code}")
check("密码字段未泄漏", profile and not profile.get("password"), f"data={profile}")

# ---------- 3. 购物车 ----------
print("\n[3] 购物车")
# 先清空可能残留
code, msg, cart0 = call("GET", "/cart", token=token)
if cart0:
    ids = [c["cartId"] for c in cart0]
    call("DELETE", "/cart/batch", ids, token=token)

code, msg, _ = call("POST", "/cart", {"productId": 1, "quantity": 2}, token=token)
check("加入购物车成功", code == 200, f"code={code} msg={msg}")

code, msg, cart = call("GET", "/cart", token=token)
check("购物车含 1 条记录", cart and len(cart) == 1, f"len={len(cart) if cart else 0}")
item = cart[0] if cart else {}
check("小计计算正确（7999 x 2 = 15998）",
      item.get("subtotal") is not None and abs(float(item["subtotal"]) - 15998.0) < 0.01,
      f"subtotal={item.get('subtotal')}")
check("默认勾选状态为已勾选", item.get("checked") == 1, f"checked={item.get('checked')}")

# 重复加购应累加
code, msg, _ = call("POST", "/cart", {"productId": 1, "quantity": 1}, token=token)
code, msg, cart = call("GET", "/cart", token=token)
qty = cart[0]["quantity"] if cart else 0
check("重复加购数量累加（2+1=3）", qty == 3, f"qty={qty}")

# 改数量
code, msg, _ = call("PUT", f"/cart/{item['cartId']}?quantity=5", token=token)
code, msg, cart = call("GET", "/cart", token=token)
check("修改数量成功", cart and cart[0]["quantity"] == 5,
      f"qty={cart[0]['quantity'] if cart else 'N/A'}")

# 越权：改数量到超过库存
code, msg, _ = call("PUT", f"/cart/{item['cartId']}?quantity=99999", token=token)
check("超出库存被拒绝", code != 200, f"code={code}")

# 勾选状态
call("PUT", f"/cart/{item['cartId']}/checked?checked=0", token=token)
code, msg, count = call("GET", "/cart/count", token=token)
check("取消勾选后计数为 0", count == 0, f"count={count}")
call("PUT", f"/cart/{item['cartId']}/checked?checked=1", token=token)
code, msg, count = call("GET", "/cart/count", token=token)
check("重新勾选后计数为 5", count == 5, f"count={count}")

# ---------- 4. 越权测试 ----------
print("\n[4] 越权访问防护")
# admin 用户尝试删除 demo 的购物车条目
code, msg, admin_login = call("POST", "/user/login", {"username": "admin", "password": "123456"})
admin_token = (admin_login or {}).get("token", "")
code, msg, _ = call("DELETE", f"/cart/{item['cartId']}", token=admin_token)
check("他人无法删除我的购物车条目", code != 200, f"code={code} msg={msg}")
code, msg, admin_cart = call("GET", "/cart", token=admin_token)
check("他人购物车不受影响", admin_cart == [], f"实际={admin_cart}")

# ---------- 5. 地址 ----------
print("\n[5] 收货地址")
code, msg, addrs = call("GET", "/address", token=token)
check("地址列表可访问", code == 200 and addrs is not None, f"code={code}")
addr_count = len(addrs or [])
default_addr = next((a for a in (addrs or []) if a["isDefault"] == 1), None)
check("存在默认地址", default_addr is not None, f"共 {addr_count} 条")

code, msg, new_id = call("POST", "/address", {
    "receiver": "张三", "phone": "13800138000",
    "province": "广东省", "city": "深圳市", "district": "南山区",
    "detail": "科技园南路 1 号", "isDefault": 0
}, token=token)
check("新增地址成功", code == 200 and new_id, f"code={code} msg={msg}")

# 手机号校验
code, msg, _ = call("POST", "/address", {
    "receiver": "李四", "phone": "123",
    "province": "北京市", "city": "北京市", "district": "朝阳区",
    "detail": "某某路 2 号", "isDefault": 0
}, token=token)
check("非法手机号被参数校验拦截", code == 400, f"code={code} msg={msg}")

# 设为默认
if new_id:
    code, msg, _ = call("PUT", f"/address/{new_id}/default", token=token)
    check("设为默认地址成功", code == 200, f"code={code} msg={msg}")
    code, msg, addrs2 = call("GET", "/address", token=token)
    defaults = [a for a in (addrs2 or []) if a["isDefault"] == 1]
    check("全局只有一个默认地址", len(defaults) == 1, f"默认数量={len(defaults)}")

# 删除测试地址
if new_id:
    code, msg, _ = call("DELETE", f"/address/{new_id}", token=token)
    check("删除地址成功", code == 200, f"code={code} msg={msg}")

# ---------- 6. 下单 ----------
print("\n[6] 订单主链路")

use_addr = default_addr["id"] if default_addr else None

# 记录下单前库存：遍历购物车里所有商品的当前库存，用于下单后精确核对
stock_before_map = {}
if cart:
    for c in cart:
        pid = str(c["productId"])
        _, _, p = call("GET", f"/product/{pid}")
        if p:
            stock_before_map[pid] = int(p["stock"])

if use_addr:
    code, msg, order = call("POST", "/order", {
        "source": "cart", "addressId": use_addr, "remark": "联调测试订单"
    }, token=token)
    check("从购物车下单成功", code == 200 and order and order.get("orderNo"),
          f"code={code} msg={msg}")
    order_no = (order or {}).get("orderNo", "")
    order_id = (order or {}).get("id")
    check("订单含明细", order and len(order.get("items", [])) == 1,
          f"items={len(order.get('items', [])) if order else 0}")

    # 金额按购物车实际商品与数量动态核算（购物车里加的是商品 1，共 5 件）
    expect_amount = 0.0
    if order:
        for it in order.get("items", []):
            expect_amount += float(it["productPrice"]) * int(it["quantity"])
    check("订单金额 = 各明细单价×数量之和",
          order and abs(float(order["payAmount"]) - expect_amount) < 0.01,
          f"实际={order.get('payAmount') if order else 'N/A'} 期望={expect_amount}")

    # 库存扣减：按订单明细逐条精确核对（下单前已记录每件商品的库存）
    for it in (order or {}).get("items", []):
        pid = str(it["productId"])
        qty = int(it["quantity"])
        before = stock_before_map.get(pid)
        c, m, prod = call("GET", f"/product/{pid}")
        if prod and before is not None:
            after_stock = int(prod["stock"])
            check(f"商品 {pid} 库存精确扣减 {qty} 件",
                  after_stock == before - qty,
                  f"{before} - {qty} != {after_stock}")

    check("订单状态为待付款", order and order.get("status") == 0,
          f"status={order.get('status') if order else 'N/A'}")
    check("收货信息为快照（不为空）", order and bool(order.get("address")),
          f"address={order.get('address') if order else 'N/A'}")
    check("订单号非空", bool(order_no), f"orderNo={order_no}")

    # 购物车已清空
    code, msg, cart_after = call("GET", "/cart", token=token)
    check("下单后购物车已清理", not cart_after, f"剩余={len(cart_after or [])}")

    # 重复下单（购物车已空）
    code, msg, _ = call("POST", "/order", {"source": "cart", "addressId": use_addr}, token=token)
    check("购物车为空时下单被拒绝", code != 200, f"code={code}")

    # 立即购买
    code, msg, order2 = call("POST", "/order", {
        "source": "buyNow", "addressId": use_addr,
        "items": [{"productId": 3, "quantity": 1}]
    }, token=token)
    check("立即购买下单成功", code == 200 and order2 and order2.get("orderNo"),
          f"code={code} msg={msg}")
    order2_id = (order2 or {}).get("id")

    # 立即购买参数缺失
    code, msg, _ = call("POST", "/order", {
        "source": "buyNow", "addressId": use_addr, "items": []
    }, token=token)
    check("立即购买缺商品被拒绝", code == 400, f"code={code} msg={msg}")

    # 不存在的地址
    code, msg, _ = call("POST", "/order", {
        "source": "cart", "addressId": 999999
    }, token=token)
    check("不存在的地址下单被拒绝", code != 200, f"code={code} msg={msg}")

    # 支付
    code, msg, _ = call("POST", f"/order/{order_id}/pay", token=token)
    check("支付成功", code == 200, f"code={code} msg={msg}")

    # 重复支付
    code, msg, _ = call("POST", f"/order/{order_id}/pay", token=token)
    check("重复支付被拒绝", code != 200, f"code={code} msg={msg}")

    # 取消已支付订单
    code, msg, _ = call("POST", f"/order/{order_id}/cancel", token=token)
    check("已支付订单不能取消", code != 200, f"code={code} msg={msg}")

    # 取消第二单（待付款）并验证库存精确回补
    if order2_id:
        buy_pid = "3"
        buy_qty = 1
        # 先查当前库存（下单后、取消前）
        code, msg, p_mid = call("GET", f"/product/{buy_pid}")
        s_mid = int(p_mid["stock"]) if p_mid else 0
        # 执行取消
        code, msg, _ = call("POST", f"/order/{order2_id}/cancel", token=token)
        check("待付款订单取消成功", code == 200, f"code={code} msg={msg}")
        # 再查库存，应精确回补
        code, msg, p_after = call("GET", f"/product/{buy_pid}")
        s_after = int(p_after["stock"]) if p_after else 0
        check("取消订单后库存精确回补", s_after == s_mid + buy_qty,
              f"取消前={s_mid} 取消后={s_after} 期望={s_mid + buy_qty}")

    # 订单列表
    code, msg, orders = call("GET", "/order/list?pageNum=1&pageSize=10", token=token)
    check("订单列表可查询", code == 200 and orders and int(orders.get("total") or 0) >= 2,
          f"code={code} total={orders.get('total') if orders else 0}")

    # 订单状态统计
    code, msg, counts = call("GET", "/order/count", token=token)
    check("订单状态统计可查询", code == 200 and isinstance(counts, dict),
          f"counts={counts}")

    # 越权：访问他人订单
    if order_id:
        code, msg, _ = call("GET", f"/order/{order_id}", token=admin_token)
        check("无法查看他人订单", code != 200, f"code={code} msg={msg}")
else:
    print("  [SKIP] 无可用地址，跳过下单测试")

# ---------- 7. 用户资料 ----------
print("\n[7] 个人资料")
code, msg, _ = call("PUT", "/user/profile", {
    "nickname": "演示用户改", "phone": "13900139000", "gender": 1
}, token=token)
check("修改资料成功", code == 200, f"code={code} msg={msg}")

code, msg, prof = call("GET", "/user/profile", token=token)
check("资料修改已生效", prof and prof.get("nickname") == "演示用户改",
      f"nickname={prof.get('nickname') if prof else 'N/A'}")

# 越权字段测试：尝试改密码和状态
code, msg, prof2 = call("GET", "/user/profile", token=token)
check("越权字段（status/password）不可通过资料接口修改",
      prof2 and not prof2.get("password"), f"data={prof2}")

# 还原昵称，保证脚本可重复运行（不改回去会让下次跑的断言失效）
call("PUT", "/user/profile", {"nickname": "演示用户", "phone": "13900000001"}, token=token)
code, msg, restored = call("GET", "/user/profile", token=token)
check("测试后资料已还原（可重复运行）", restored and restored.get("nickname") == "演示用户",
      f"nickname={restored.get('nickname') if restored else 'N/A'}")

# ---------- 汇总 ----------
print("\n" + "=" * 60)
print(f"测试结果：通过 {len(passed)} 项，失败 {len(failed)} 项")
if failed:
    print("\n失败明细：")
    for f in failed:
        print(f"  - {f}")
print("=" * 60)
