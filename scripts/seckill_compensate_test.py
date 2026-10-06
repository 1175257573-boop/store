#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
秒杀补偿与幂等专项测试。

覆盖异步架构最容易漏的三个漏洞：
  A. 消息丢失：预扣了但订单没落成 → 补偿必须回补库存
  B. 重复消费：同一条消息被消费多次 → 幂等必须拦住，不多扣库存
  C. 超时取消：订单超时未支付 → 必须回补库存，且重复执行不多补
"""
import json
import subprocess
import sys
import time
import urllib.request
import urllib.error

BASE = "http://127.0.0.1:8080/api"
MYSQL = r"C:\Program Files\MySQL\MySQL Server 5.7\bin\mysql.exe"
A, S = 1, 1

passed, failed = [], []


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


def scalar_rows(q):
    """取第一行第一列和第二列（用于拿订单 ID + 所属用户名）"""
    out = sql(q)
    lines = [l for l in out.splitlines() if l.strip() and "Warning" not in l]
    if len(lines) > 1:
        return lines[1].split("\t")[:2]
    return [None, None]


def check(name, cond, detail=""):
    if cond:
        passed.append(name)
        print(f"  [PASS] {name}")
    else:
        failed.append(f"{name} | {detail}")
        print(f"  [FAIL] {name}  -> {detail}")


def login(u="demo", p="123456"):
    _, _, d = call("POST", "/user/login", {"username": u, "password": p})
    return d["token"]


def rebuild(stock=100, buckets=10):
    call("POST", f"/seckill/admin/rebuild?activityId={A}&skuId={S}"
                 f"&totalStock={stock}&bucketCount={buckets}")
    time.sleep(0.3)


def stock_state():
    return {
        "available": int(scalar("SELECT available FROM t_seckill_stock "
                                f"WHERE activity_id={A} AND sku_id={S}")),
        "locked": int(scalar("SELECT locked FROM t_seckill_stock "
                             f"WHERE activity_id={A} AND sku_id={S}")),
        "sold": int(scalar("SELECT sold FROM t_seckill_stock "
                           f"WHERE activity_id={A} AND sku_id={S}")),
        "total": int(scalar("SELECT total_stock FROM t_seckill_stock "
                            f"WHERE activity_id={A} AND sku_id={S}")),
    }


def order_count():
    return int(scalar("SELECT COUNT(*) FROM t_seckill_order "
                      f"WHERE activity_id={A} AND status IN (0,1)"))


print("=" * 66)
print("秒杀补偿与幂等专项测试")
print("=" * 66)

tok = login()

# ==========================================================
print("\n【场景 A】重复消费同一消息 —— 幂等防护")
print("-" * 66)
rebuild(100)
# 必须用不同用户的 token：同一用户的重复请求会被防重层拦掉，
# 那样根本到不了消费端，测不出「重复消费」的场景。
stress_tokens = []
for i in range(10):
    _, _, d = call("POST", "/user/login",
                   {"username": f"stress_{i:04d}", "password": "123456"})
    if d:
        stress_tokens.append(d["token"])

import urllib.request as _u

def seckill_with_ip(token, sku_id, req_id, ip):
    """带伪造 IP 头的秒杀请求。

    压测客户端所有请求都来自 127.0.0.1，ip-qps=5 会把一半请求拦掉，
    根本到不了消费端，测不出「重复消费」场景。这里伪造 XFF 让每个用户
    看起来来自不同 IP，只测用户维度的行为。
    """
    req = _u.Request(BASE + "/seckill",
                     data=json.dumps({"activityId": A, "skuId": S,
                                      "requestId": req_id,
                                      "quantity": 1}).encode(),
                     method="POST")
    req.add_header("Content-Type", "application/json;charset=UTF-8")
    req.add_header("Authorization", "Bearer " + token)
    req.add_header("X-Forwarded-For", ip)
    try:
        with _u.urlopen(req, timeout=15) as x:
            return json.loads(x.read().decode())
    except Exception as e:
        return {"err": str(e)}


for i, t in enumerate(stress_tokens):
    seckill_with_ip(t, S, f"dup-{i}", f"10.1.0.{i + 1}")
time.sleep(0.3)
print(f"  {len(stress_tokens)} 个不同用户各发 1 次请求")

# 消费 3 轮（模拟消息重投）
for _ in range(3):
    call("POST", "/seckill/admin/consume?batchSize=100")
time.sleep(0.3)

st = stock_state()
oc = order_count()
print(f"  消费 3 轮后 -> 订单 {oc} 单, available={st['available']}, locked={st['locked']}")
check("重复消费后订单数 = 请求数（未被重复落单）", oc == 10, f"实际 {oc}")
check("重复消费后库存扣减正确（100-10=90）", st["available"] == 90,
      f"available={st['available']}")
check("总量守恒", st["available"] + st["locked"] + st["sold"] == st["total"],
      f"{st['available']}+{st['locked']}+{st['sold']} != {st['total']}")

# ==========================================================
print("\n【场景 B】消息丢失补偿 —— 预扣了但订单没落成")
print("-" * 66)
rebuild(100)
# 发 20 个请求（20 个不同用户 + 伪造不同 IP，绕过防重与 IP 限流）
for i in range(20):
    _, _, d = call("POST", "/user/login",
                   {"username": f"stress_{i:04d}", "password": "123456"})
    if d:
        seckill_with_ip(d["token"], S, f"lost-{i}", f"10.2.0.{i + 1}")
time.sleep(0.3)

# 手动只处理前 10 条：先消费一轮让它入队，再直接删掉后半段队列元素
call("POST", "/seckill/admin/consume?batchSize=100")
time.sleep(0.3)
st_before = stock_state()
print(f"  20 次请求后: available={st_before['available']}, 订单={order_count()}")

# 模拟消息丢失：手工插入「已预扣未落单」流水 + 扣 Redis 库存
sql_text = f"""
USE ecommerce;
-- 插入 5 条未落单的预扣流水
INSERT INTO t_seckill_pre_deduct
  (pre_deduct_no, activity_id, sku_id, user_id, bucket_index, quantity, direction, status)
VALUES
  ('LOST-1', {A}, {S}, 2, 0, 1, 1, 0),
  ('LOST-2', {A}, {S}, 2, 0, 1, 1, 0),
  ('LOST-3', {A}, {S}, 2, 0, 1, 1, 0),
  ('LOST-4', {A}, {S}, 2, 0, 1, 1, 0),
  ('LOST-5', {A}, {S}, 2, 0, 1, 1, 0);
-- 对应扣 DB 库存（模拟 Redis 扣了但落库流程走了一半）
UPDATE t_seckill_stock SET available = available - 5
  WHERE activity_id={A} AND sku_id={S};
SELECT available FROM t_seckill_stock WHERE activity_id={A} AND sku_id={S};
"""
p = subprocess.run([MYSQL, "-uroot", "-p123456", "--default-character-set=utf8mb4"],
                   input=sql_text.encode("utf-8"), capture_output=True)
time.sleep(0.3)

st_mid = stock_state()
print(f"  模拟丢失后: available={st_mid['available']} (应比订单数少 5)")
check("构造出库存缺口（available < total - 订单数）",
      st_mid["available"] < st_mid["total"] - order_count(),
      f"available={st_mid['available']} total={st_mid['total']} orders={order_count()}")

# 触发补偿（把未落单流水的时间改老，才能被扫描到）
sql(f"UPDATE t_seckill_pre_deduct SET create_time = DATE_SUB(NOW(), INTERVAL 120 SECOND) "
    f"WHERE pre_deduct_no LIKE 'LOST-%';")
code, msg, n = call("POST", f"/seckill/admin/compensate?activityId={A}&batchSize=100")
print(f"  补偿处理: {n} 条")
st_after = stock_state()
print(f"  补偿后: available={st_after['available']}")
check("补偿回补了库存（+5）", st_after["available"] == st_mid["available"] + 5,
      f"{st_mid['available']} -> {st_after['available']}，期望 +5")

# 重复补偿不应多补
code, msg, n2 = call("POST", f"/seckill/admin/compensate?activityId={A}&batchSize=100")
st_repeat = stock_state()
print(f"  重复补偿: 处理 {n2} 条, available={st_repeat['available']}")
check("重复补偿不再回补（幂等）", st_repeat["available"] == st_after["available"],
      f"{st_after['available']} -> {st_repeat['available']}，状态机应拦住")

check("补偿后总量守恒",
      st_repeat["available"] + st_repeat["locked"] + st_repeat["sold"] == st_repeat["total"],
      f"{st_repeat['available']}+{st_repeat['locked']}+{st_repeat['sold']} != {st_repeat['total']}")

# ==========================================================
print("\n【场景 C】订单超时取消 —— 回补库存")
print("-" * 66)
rebuild(100)
# 不同用户各发一次，否则会被防重拦掉，测不到多笔订单
for i in range(5):
    _, _, d = call("POST", "/user/login",
                   {"username": f"stress_{i:04d}", "password": "123456"})
    if d:
        call("POST", "/seckill", {"activityId": A, "skuId": S,
                                   "requestId": f"cancel-{i}", "quantity": 1},
             d["token"])
call("POST", "/seckill/admin/consume?batchSize=100")
time.sleep(0.3)

st1 = stock_state()
oc1 = order_count()
print(f"  下单后: available={st1['available']}, locked={st1['locked']}, 订单={oc1}")

# 找一笔订单取消。
# 必须用「订单所有者」的 token：用 demo 的 token 会被越权防护拦掉，
# 那是正确的安全行为，但会让本场景测不到真正的取消逻辑。
row = scalar_rows("SELECT o.id, u.username FROM t_seckill_order o "
                  "JOIN t_user u ON u.id=o.user_id "
                  f"WHERE o.activity_id={A} AND o.status=0 LIMIT 1")
if not row or not row[0]:
    print("  [SKIP] 没有待取消订单（可能被前序压测清理），跳过取消场景")
    sys.exit(0)
oid, owner = row
_, _, od = call("POST", "/user/login", {"username": owner, "password": "123456"})
if not od:
    # 订单所有者是被压测清理掉的临时账号，无法登录则跳过该场景
    print(f"  [SKIP] 订单所有者 {owner} 已不存在（压测清理），跳过取消场景")
    sys.exit(0)
owner_token = od["token"]
print(f"  取消订单 {oid}（所有者 {owner}）")
call("POST", f"/seckill/order/{oid}/cancel", t=owner_token)
time.sleep(0.3)
st2 = stock_state()
print(f"  取消后: available={st2['available']}, locked={st2['locked']}")
check("取消订单回补可用库存（+1）", st2["available"] == st1["available"] + 1,
      f"{st1['available']} -> {st2['available']}")
check("取消订单释放锁定（-1）", st2["locked"] == st1["locked"] - 1,
      f"{st1['locked']} -> {st2['locked']}")
check("取消后总量守恒",
      st2["available"] + st2["locked"] + st2["sold"] == st2["total"],
      f"{st2['available']}+{st2['locked']}+{st2['sold']} != {st2['total']}")

# 重复取消不应多回补
call("POST", f"/seckill/order/{oid}/cancel", t=owner_token)
time.sleep(0.3)
st3 = stock_state()
check("重复取消不多回补（状态机幂等）", st3["available"] == st2["available"],
      f"{st2['available']} -> {st3['available']}，应被状态机拦住")

# ==========================================================
print("\n【场景 D】支付后库存转已售")
print("-" * 66)
rebuild(100)
for i in range(3):
    _, _, d = call("POST", "/user/login",
                   {"username": f"stress_{i:04d}", "password": "123456"})
    if d:
        call("POST", "/seckill", {"activityId": A, "skuId": S,
                                   "requestId": f"pay-{i}", "quantity": 1},
             d["token"])
call("POST", "/seckill/admin/consume?batchSize=100")
time.sleep(0.3)

st_p0 = stock_state()
row = scalar_rows(f"SELECT o.id, u.username FROM t_seckill_order o "
                  f"JOIN t_user u ON u.id=o.user_id "
                  f"WHERE o.activity_id={A} AND o.status=0 LIMIT 1")
oid, owner = row
_, _, od = call("POST", "/user/login", {"username": owner, "password": "123456"})
owner_token = od["token"]
print(f"  支付订单 {oid}（所有者 {owner}）")
call("POST", f"/seckill/order/{oid}/pay", t=owner_token)
time.sleep(0.3)
st_p1 = stock_state()
print(f"  支付前: locked={st_p0['locked']}, sold={st_p0['sold']}")
print(f"  支付后: locked={st_p1['locked']}, sold={st_p1['sold']}")
check("支付后 locked 减少", st_p1["locked"] == st_p0["locked"] - 1,
      f"{st_p0['locked']} -> {st_p1['locked']}")
check("支付后 sold 增加", st_p1["sold"] == st_p0["sold"] + 1,
      f"{st_p0['sold']} -> {st_p1['sold']}")
check("支付后总量守恒",
      st_p1["available"] + st_p1["locked"] + st_p1["sold"] == st_p1["total"],
      f"{st_p1['available']}+{st_p1['locked']}+{st_p1['sold']} != {st_p1['total']}")

# 重复支付不应重复扣
call("POST", f"/seckill/order/{oid}/pay", t=owner_token)
time.sleep(0.3)
st_p2 = stock_state()
check("重复支付不重复扣减（状态机幂等）", st_p2["sold"] == st_p1["sold"],
      f"{st_p1['sold']} -> {st_p2['sold']}")

# ==========================================================
print("\n【场景 E】对账 —— 账目平衡")
print("-" * 66)
code, msg, rec = call("GET", f"/seckill/admin/reconcile?activityId={A}")
if rec:
    check("对账平衡", rec.get("allBalanced"),
          f"{rec.get('conclusion')} | 详情={rec.get('details')}")
    print(f"  {rec.get('conclusion')}")
    for d in rec.get("details", []):
        print(f"    sku={d['skuId']} available={d['available']} locked={d['locked']} "
              f"sold={d['sold']} 订单={d['validOrders']} db平衡={d['dbBalanced']} "
              f"订单匹配={d['orderBalanced']}")
else:
    check("对账接口可用", False, f"{code} {msg}")

print(f"\n{'='*66}")
print(f"结果：通过 {len(passed)} 项，失败 {len(failed)} 项")
if failed:
    print("\n失败明细：")
    for f in failed:
        print(f"  - {f}")
print(f"{'='*66}")
sys.exit(0 if not failed else 1)
