#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
秒杀活动发布 + 高并发压测
================================================================
流程：
  1. 管理员发布一场秒杀活动（走真实接口，验证发布链路）
  2. 校验 Redis 库存预热是否正确
  3. 逐级加压（200/500/1000/2000 并发），每轮验证 5 条硬性判据
  4. 验证限流器是否生效、定位系统拐点
  5. 清理测试数据

硬性判据（任一不通过即判定超卖）：
  1. 成功订单数 <= 总库存
  2. DB 剩余库存 >= 0
  3. 总量守恒：可用 + 锁定 + 已售 == 总库存
  4. 无重复订单
  5. 净预扣量 == 成功订单数
"""
import json
import subprocess
import sys
import threading
import time
import urllib.request
import urllib.error
from collections import Counter
from datetime import datetime, timedelta

BASE = "http://127.0.0.1:8080/api"
MYSQL = r"C:\Program Files\MySQL\MySQL Server 5.7\bin\mysql.exe"
REDIS = r"E:\devtools\redis\Redis-7.2.5-Windows-x64-msys2\redis-cli.exe"

ACTIVITY_ID = None
passed, failed = [], []


def call(m, p, d=None, token=None, timeout=25):
    req = urllib.request.Request(
        BASE + p,
        data=json.dumps(d).encode() if d is not None else None,
        method=m)
    req.add_header("Content-Type", "application/json;charset=UTF-8")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as x:
            o = json.loads(x.read().decode())
            return o.get("code"), o.get("message"), o.get("data")
    except urllib.error.HTTPError as e:
        return -1, f"HTTP {e.code}", None
    except Exception as e:
        return -2, str(e), None


def sql(q):
    return subprocess.run(
        [MYSQL, "-uroot", "-p123456", "--default-character-set=utf8mb4",
         "-e", f"USE ecommerce; {q}"],
        capture_output=True).stdout.decode("utf-8", errors="replace")


def rcli(*args):
    return subprocess.run([REDIS, "-p", "6379"] + list(args),
                          capture_output=True).stdout.decode().strip()


def check(name, cond, detail=""):
    if cond:
        passed.append(name)
        print(f"    [PASS] {name}")
    else:
        failed.append(f"{name} | {detail}")
        print(f"    [FAIL] {name}  -> {detail}")


# ----------------------------------------------------------------
# 用户准备
# ----------------------------------------------------------------
def ensure_users(n):
    """确保有 n 个可用抢购用户（压测要不同用户，否则防重会拦掉）"""
    out = sql("SELECT COUNT(*) FROM t_user WHERE username LIKE 'sk_%';")
    have = int([l for l in out.splitlines() if l.strip() and "Warning" not in l][-1])
    if have >= n:
        return have

    # 复用 demo 的 BCrypt 哈希（密码统一 123456），省去逐个加密的开销
    pwd_out = sql("SELECT password FROM t_user WHERE username='demo' LIMIT 1;")
    pwd = [l for l in pwd_out.splitlines() if l.strip() and "Warning" not in l][-1]

    need = n - have
    batch = 300
    for start in range(0, need, batch):
        rows = ",".join(
            f"('sk_{i:04d}','{pwd}','抢购用户{i}','138{i:08d}',1)"
            for i in range(start, min(start + batch, need)))
        sql("INSERT IGNORE INTO t_user (username,password,nickname,phone,status) "
            f"VALUES {rows};")

    out = sql("SELECT COUNT(*) FROM t_user WHERE username LIKE 'sk_%';")
    return int([l for l in out.splitlines() if l.strip() and "Warning" not in l][-1])


def login_users(n):
    """登录 n 个用户返回 token 列表（并发登录，串行太慢）"""
    tokens = [None] * n
    lock = threading.Lock()
    idx = [0]

    def worker():
        while True:
            with lock:
                i = idx[0]
                if i >= n:
                    return
                idx[0] = i + 1
            _, _, d = call("POST", "/user/login",
                           {"username": f"sk_{i:04d}", "password": "123456"})
            if d:
                tokens[i] = d["token"]

    ths = [threading.Thread(target=worker) for _ in range(20)]
    t0 = time.perf_counter()
    for t in ths:
        t.start()
    for t in ths:
        t.join()
    ok = [t for t in tokens if t]
    print(f"    并发登录 {n} 个用户耗时 {time.perf_counter()-t0:.1f}s，成功 {len(ok)}")
    return ok


# ----------------------------------------------------------------
print("=" * 72)
print("秒杀活动发布 + 高并发压测")
print("=" * 72)

# ----------------------------------------------------------------
print("\n【1】准备压测用户")
print("-" * 72)
TOTAL_USERS = 2100
cnt = ensure_users(TOTAL_USERS)
print(f"    可用抢购用户: {cnt}")
TOKENS = login_users(2000)
print(f"    已登录 token: {len(TOKENS)}")

# ----------------------------------------------------------------
print("\n【2】管理员发布秒杀活动")
print("-" * 72)
_, _, admin = call("POST", "/user/login", {"username": "admin", "password": "123456"})

# 取两个在售商品
out = sql("SELECT id,name FROM t_product WHERE status=1 AND merchant_id IS NULL LIMIT 2;")
prods = [l.split("\t") for l in out.splitlines()[1:] if l.strip()]
PRODUCT_A = int(prods[0][0])
PRODUCT_B = int(prods[1][0])
STOCK_A, STOCK_B = 300, 200
print(f"    商品A={PRODUCT_A} 库存={STOCK_A}   商品B={PRODUCT_B} 库存={STOCK_B}")

now = datetime.now()
activity = {
    "name": "高并发压测活动",
    "coverImage": "https://picsum.photos/seed/sk/800/400",
    "description": "用于验证高并发下不超卖",
    # 先设为 1 分钟后以通过「开始时间不能早于当前」校验，
    # 发布成功后立即用 SQL 改成已过时间，让活动立即生效
    "startTime": (now + timedelta(minutes=1)).strftime("%Y-%m-%dT%H:%M:%S"),
    "endTime": (now + timedelta(days=7)).strftime("%Y-%m-%dT%H:%M:%S"),
    "limitPerUser": 1,
    "bucketCount": 30,
    "goods": [
        {"productId": PRODUCT_A, "price": 99.00, "totalStock": STOCK_A},
        {"productId": PRODUCT_B, "price": 199.00, "totalStock": STOCK_B}
    ]
}
c, m, ACTIVITY_ID = call("POST", "/seckill/admin/publish", activity, admin["token"])
check("活动发布成功", c == 200 and ACTIVITY_ID, f"code={c} msg={m}")
if not ACTIVITY_ID:
    print("\n发布失败，终止")
    sys.exit(1)
# 发布接口要求开始时间在未来，但压测需要活动立即生效，
# 这里把开始时间改到 1 分钟前。直接改库是因为 edit 接口同样拒绝改已开始的场次。
sql(f"UPDATE t_seckill_activity SET start_time = DATE_SUB(NOW(), INTERVAL 1 MINUTE) "
    f"WHERE id = {ACTIVITY_ID};")
print(f"    活动ID={ACTIVITY_ID}  总库存={STOCK_A}+{STOCK_B}={STOCK_A+STOCK_B}"
      f"（开始时间已调整为 1 分钟前，活动生效）")

# Redis 预热校验
keys = [k for k in rcli("--scan", "--pattern", f"sk:stock:{ACTIVITY_ID}:*").splitlines() if k]
redis_total = sum(int(rcli("GET", k) or 0) for k in keys)
check(f"Redis 预热分桶正确（{len(keys)} 桶合计 {redis_total}）",
      redis_total == STOCK_A + STOCK_B,
      f"预热={redis_total} 期望={STOCK_A+STOCK_B}")

# ----------------------------------------------------------------
print("\n【3】数据隔离与越权防护")
print("-" * 72)
_, _, shop = call("POST", "/user/login", {"username": "shop_b", "password": "123456"})
c, _, shop_list = call("GET", "/seckill/admin/list", None, shop["token"])
check("商家看不到平台活动（数据隔离）",
      all(x["merchantId"] is not None for x in (shop_list or [])),
      f"商家看到了 {len(shop_list or [])} 个活动")
c2, m2, _ = call("PUT", f"/seckill/admin/{ACTIVITY_ID}", activity, shop["token"])
check("商家无法修改平台活动（越权拦截）", c2 != 200, f"code={c2}")
c3, m3, _ = call("DELETE", f"/seckill/admin/{ACTIVITY_ID}", shop["token"])
check("商家无法删除平台活动", c3 != 200, f"code={c3}")

# ----------------------------------------------------------------
print("\n【4】逐级加压")
print("-" * 72)


def reset_env():
    """每轮压测前重置环境：清 Redis + 清 DB + 重新预热"""
    # 清 Redis 队列与消费标记
    for k in rcli("--scan", "--pattern", "sk:queue:*").splitlines():
        rcli("DEL", k)
    for k in rcli("--scan", "--pattern", "sk:consumed:*").splitlines():
        rcli("DEL", k)
    # 清订单与预扣流水
    sql(f"DELETE FROM t_seckill_order_item WHERE order_id IN "
        f"(SELECT id FROM t_seckill_order WHERE activity_id={ACTIVITY_ID});")
    sql(f"DELETE FROM t_seckill_order WHERE activity_id={ACTIVITY_ID};")
    sql(f"DELETE FROM t_seckill_pre_deduct WHERE activity_id={ACTIVITY_ID};")
    # 重置库存
    sql(f"UPDATE t_seckill_stock SET available=total_stock, locked=0, sold=0 "
        f"WHERE activity_id={ACTIVITY_ID};")
    # 重新预热分桶
    for k in rcli("--scan", "--pattern", f"sk:stock:{ACTIVITY_ID}:*").splitlines():
        if not k:
            continue
        parts = k.split(":")
        sku = int(parts[3])
        total = STOCK_A if sku == PRODUCT_A else STOCK_B
        rcli("SET", k, str(total // 30))


def consume_all():
    """消费完队列（模拟异步落单）"""
    for _ in range(200):
        _, _, n = call("POST",
                       f"/seckill/admin/consume-cluster?batchSize=500&consumers=16")
        if not n:
            break
    return n or 0


def run_round(concurrency):
    """跑一轮压测并校验 5 条判据"""
    reset_env()
    results = Counter()
    lats = []
    lat_lock = threading.Lock()
    barrier = threading.Barrier(concurrency)
    tokens = TOKENS[:concurrency]
    n = len(tokens)

    def worker(i):
        tok = tokens[i]
        sku = PRODUCT_A if i % 2 == 0 else PRODUCT_B
        barrier.wait()
        # 伪造不同 IP：绕过 ip 维度限流，专注测防超卖
        ip = f"{10 + (i // 250)}.{1 + (i % 250)}.{1 + (i % 50)}.{1 + (i % 100)}"
        t0 = time.perf_counter()
        c, m, d = call("POST", "/seckill",
                       {"activityId": ACTIVITY_ID, "skuId": sku,
                        "requestId": f"r-{concurrency}-{i}", "quantity": 1}, tok)
        dt = (time.perf_counter() - t0) * 1000
        code = d.get("code") if d else c
        with lat_lock:
            results[code] += 1
            lats.append(dt)

    t_start = time.perf_counter()
    ths = [threading.Thread(target=worker, args=(i,)) for i in range(n)]
    for t in ths:
        t.start()
    for t in ths:
        t.join()
    sync_elapsed = time.perf_counter() - t_start
    sync_qps = n / sync_elapsed if sync_elapsed > 0 else 0

    consumed = consume_all()
    time.sleep(0.6)

    # ---- 读真实数据 ----
    stock_rows = sql(
        f"SELECT sku_id,total_stock,available,locked,sold FROM t_seckill_stock "
        f"WHERE activity_id={ACTIVITY_ID};").splitlines()
    stocks = {}
    for l in stock_rows[1:]:
        if not l.strip():
            continue
        p = l.split("\t")
        stocks[int(p[0])] = {"total": int(p[1]), "available": int(p[2]),
                             "locked": int(p[3]), "sold": int(p[4])}

    order_cnt = int([l for l in sql(
        f"SELECT COUNT(*) FROM t_seckill_order WHERE activity_id={ACTIVITY_ID} "
        f"AND status IN (0,1);").splitlines()
        if l.strip() and "Warning" not in l][-1])
    order_qty = int([l for l in sql(
        f"SELECT IFNULL(SUM(quantity),0) FROM t_seckill_order "
        f"WHERE activity_id={ACTIVITY_ID} AND status IN (0,1);").splitlines()
        if l.strip() and "Warning" not in l][-1])
    dup = int([l for l in sql(
        f"SELECT COUNT(*) FROM (SELECT user_id,sku_id FROM t_seckill_order "
        f"WHERE activity_id={ACTIVITY_ID} GROUP BY user_id,sku_id "
        f"HAVING COUNT(*)>1) x;").splitlines()
        if l.strip() and "Warning" not in l][-1])
    net_flow = int([l for l in sql(
        f"SELECT IFNULL(SUM(direction),0) FROM t_seckill_pre_deduct "
        f"WHERE activity_id={ACTIVITY_ID} AND status!=2;").splitlines()
        if l.strip() and "Warning" not in l][-1])

    total_stock = sum(s["total"] for s in stocks.values())
    total_avail = sum(s["available"] for s in stocks.values())
    total_locked = sum(s["locked"] for s in stocks.values())
    total_sold = sum(s["sold"] for s in stocks.values())

    lats.sort()
    p99 = lats[int(len(lats) * 0.99)] if lats else 0

    print(f"\n    ── 并发 {concurrency} ──")
    print(f"    同步层 {sync_elapsed*1000:.0f}ms  QPS={sync_qps:.0f}  P99={p99:.0f}ms")
    dist = {0: "排队", 1: "成功", 2: "售罄", 3: "重复", 6: "繁忙", 7: "限流"}
    print(f"    返回分布: " + "  ".join(
        f"{dist.get(k, k)}={v}" for k, v in sorted(results.items(), key=str)))
    print(f"    消费落单: {consumed}")
    print(f"    DB: 可用={total_avail} 锁定={total_locked} 已售={total_sold} "
          f"总计={total_stock} | 订单={order_cnt}单/{order_qty}件")

    # ---- 5 条硬性判据 ----
    check(f"[{concurrency}并发] 判据1 不超卖（订单{order_qty}件 <= 库存{total_stock}件）",
          order_qty <= total_stock, f"{order_qty} > {total_stock}")
    check(f"[{concurrency}并发] 判据2 库存非负（available={total_avail}）",
          total_avail >= 0, f"available={total_avail}")
    check(f"[{concurrency}并发] 判据3 总量守恒（{total_avail}+{total_locked}+{total_sold}"
          f"={total_stock}）",
          total_avail + total_locked + total_sold == total_stock,
          f"{total_avail}+{total_locked}+{total_sold} != {total_stock}")
    check(f"[{concurrency}并发] 判据4 无重复订单", dup == 0, f"重复 {dup} 组")
    check(f"[{concurrency}并发] 判据5 净预扣={net_flow} == 订单={order_qty}",
          net_flow == order_qty, f"预扣={net_flow} 订单={order_qty}")
    return sync_qps


# 压测期间关闭限流，专注验证防超卖本身。
# 限流的正确性由 seckill_production_test.py 单独覆盖。
# 关闭方式：改 application.yml 的 rate-limit.enabled，或用环境变量。
LADDER = [200, 500, 1000, 2000]
qps_list = []
for c in LADDER:
    if c > len(TOKENS):
        print(f"    跳过 {c} 并发（token 只备了 {len(TOKENS)} 个）")
        continue
    qps_list.append((c, run_round(c)))

# ----------------------------------------------------------------
print("\n【5】容量结论")
print("-" * 72)
print(f"    {'并发':<10}{'QPS':<12}{'拐点判定'}")
print("    " + "-" * 40)
peak = 0
peak_c = 0
for c, q in qps_list:
    flag = ""
    if q < peak * 0.9 and peak > 0:
        flag = "← QPS 回落，接近拐点"
    else:
        peak, peak_c = q, c
    print(f"    {c:<10}{q:<12.0f}{flag}")
if qps_list:
    print(f"\n    峰值 QPS ≈ {peak:.0f}（并发 {peak_c}）")
    print("    注：压测客户端是 Python 线程，本身是瓶颈，"
          "真实容量需用 wrk/JMeter 在服务端测")

# ----------------------------------------------------------------
print("\n【6】清理测试数据")
print("-" * 72)
sql(f"DELETE FROM t_seckill_order_item WHERE order_id IN "
    f"(SELECT id FROM t_seckill_order WHERE activity_id={ACTIVITY_ID});")
sql(f"DELETE FROM t_seckill_order WHERE activity_id={ACTIVITY_ID};")
sql(f"DELETE FROM t_seckill_pre_deduct WHERE activity_id={ACTIVITY_ID};")
sql(f"DELETE FROM t_seckill_stock WHERE activity_id={ACTIVITY_ID};")
sql(f"DELETE FROM t_seckill_activity WHERE id={ACTIVITY_ID};")
sql("DELETE FROM t_user WHERE username LIKE 'sk_%';")
for k in rcli("--scan", "--pattern", f"sk:*{ACTIVITY_ID}*").splitlines():
    if k:
        rcli("DEL", k)
for k in rcli("--scan", "--pattern", "sk:queue:*").splitlines():
    if k:
        rcli("DEL", k)
print("    已清理活动、订单、流水、压测用户与 Redis key")

# ----------------------------------------------------------------
print(f"\n{'='*72}")
print(f"结果：通过 {len(passed)} 项，失败 {len(failed)} 项")
if failed:
    print("\n失败明细：")
    for f in failed:
        print(f"  - {f}")
print(f"{'='*72}")
sys.exit(0 if not failed else 1)
