#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
秒杀正确性压测脚本
================================================================
核心目的不是测 QPS，而是验证「不超卖」。

硬性判据（全部必须通过，否则判定压测失败）：
  1. 成功订单数 <= 总库存（不多卖）
  2. DB 剩余库存 >= 0（不为负）
  3. 总量守恒：available + locked + sold == total_stock
  4. 无重复订单：同一 (user, activity, sku) 只有一个订单
  5. 对账平衡：Redis 预扣与 DB 落单一致

用法：
  python seckill_stress.py --requests 300 --concurrency 60
  python seckill_stress.py --ladder
"""
import argparse
import json
import subprocess
import sys
import threading
import time
import urllib.request
import urllib.error
from collections import Counter

BASE = "http://127.0.0.1:8080/api"
MYSQL = r"C:\Program Files\MySQL\MySQL Server 5.7\bin\mysql.exe"
ACTIVITY_ID = 1
SKU_ID = 1
STRESS_USER_PREFIX = "stress_"

_lock = threading.Lock()
_results = Counter()
_latencies = []
_errors = []


def call(method, path, data=None, token=None, timeout=20, fake_ip=None):
    """返回 (code, message, data)"""
    url = BASE + path
    body = json.dumps(data).encode("utf-8") if data is not None else None
    req = urllib.request.Request(url, data=body, method=method)
    req.add_header("Content-Type", "application/json;charset=UTF-8")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    if fake_ip:
        # 伪造来源 IP：压测机所有请求都来自 127.0.0.1，
        # 不绕过 ip-qps 限流就只能压出 5 并发，测不出真实能力
        req.add_header("X-Forwarded-For", fake_ip)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            obj = json.loads(resp.read().decode("utf-8"))
            return obj.get("code"), obj.get("message"), obj.get("data")
    except urllib.error.HTTPError as e:
        return -1, f"HTTP {e.code}", None
    except Exception as e:
        return -2, str(e), None


def sql(query):
    """直连 MySQL 查数据，不依赖接口返回——接口本身也是被测对象之一"""
    p = subprocess.run(
        [MYSQL, "-uroot", "-p123456", "--default-character-set=utf8mb4", "-e",
         f"USE ecommerce; {query}"],
        capture_output=True
    )
    return p.stdout.decode("utf-8", errors="replace")


def login_all(count, workers=20):
    """批量登录压测用户，拿到各自的 token。

    必须并发登录：BCrypt 强度 10 单次约 50ms，串行登录 300 个要 15 秒，
    会把压测脚本本身拖成瓶颈。20 并发可压到 1 秒内。
    """
    tokens = []
    token_lock = threading.Lock()
    index = [0]

    def login_worker():
        while True:
            with token_lock:
                i = index[0]
                if i >= count:
                    return
                index[0] = i + 1
            u = f"{STRESS_USER_PREFIX}{i:04d}"
            code, msg, data = call("POST", "/user/login",
                                   {"username": u, "password": "123456"},
                                   timeout=20)
            if code == 200 and data:
                with token_lock:
                    tokens.append((u, data["token"]))

    ths = [threading.Thread(target=login_worker) for _ in range(workers)]
    for t in ths:
        t.start()
    for t in ths:
        t.join()
    return tokens


def prepare(stock=None, buckets=10):
    """重置活动 + 初始化 Redis 库存。指定 stock 时重建为该库存量。"""
    if stock:
        code, msg, _ = call("POST",
                            f"/seckill/admin/rebuild?activityId={ACTIVITY_ID}"
                            f"&skuId={SKU_ID}&totalStock={stock}&bucketCount={buckets}")
        if code != 200:
            raise RuntimeError(f"重建活动失败: {code} {msg}")
    else:
        call("POST", f"/seckill/admin/reset/{ACTIVITY_ID}")
        time.sleep(0.3)
        code, msg, _ = call("POST", f"/seckill/admin/init/{ACTIVITY_ID}")
        if code != 200:
            raise RuntimeError(f"初始化库存失败: {code} {msg}")
    time.sleep(0.3)


def consume_all(max_rounds=200, batch=200):
    """把队列里的消息全部消费完"""
    total = 0
    for _ in range(max_rounds):
        code, msg, n = call("POST", f"/seckill/admin/consume?batchSize={batch}")
        if code != 200:
            print(f"  消费调用失败: {code} {msg}")
            break
        n = n or 0
        total += n
        if n == 0:
            break
    return total


def worker(token, user_seq, requests, barrier):
    """单个虚拟用户线程：每个用户只发一次请求（模拟一人一次限购）"""
    local = []
    barrier.wait()  # 栅栏：让所有线程尽量同时发起，模拟秒杀瞬间
    req = {
        "activityId": ACTIVITY_ID,
        "skuId": SKU_ID,
        "requestId": f"req-{user_seq}",
        "quantity": 1
    }
    t0 = time.perf_counter()
    code, msg, data = call("POST", "/seckill", req, token=token,
                          fake_ip=f"{user_seq}.1.1.1")
    dt = (time.perf_counter() - t0) * 1000
    local.append(dt)
    with _lock:
        if code == 200 and data:
            _results[data.get("code")] += 1
        else:
            _results[f"ERR{code}"] += 1
            if len(_errors) < 10:
                _errors.append(f"{code}:{msg}")
        _latencies.extend(local)


def percentile(data, p):
    if not data:
        return 0
    d = sorted(data)
    idx = min(int(len(d) * p / 100), len(d) - 1)
    return d[idx]


def get_stock():
    """直查 DB 拿真实库存状态"""
    out = sql("SELECT total_stock, available, locked, sold FROM t_seckill_stock "
              f"WHERE activity_id={ACTIVITY_ID} AND sku_id={SKU_ID};")
    lines = [l for l in out.splitlines() if l.strip() and "Warning" not in l]
    if len(lines) < 2:
        return None
    parts = lines[1].split("\t")
    return {"total": int(parts[0]), "available": int(parts[1]),
            "locked": int(parts[2]), "sold": int(parts[3])}


def count_orders():
    out = sql("SELECT COUNT(*), IFNULL(SUM(quantity),0) FROM t_seckill_order "
              f"WHERE activity_id={ACTIVITY_ID} AND status IN (0,1);")
    lines = [l for l in out.splitlines() if l.strip() and "Warning" not in l]
    if len(lines) < 2:
        return 0, 0
    parts = lines[1].split("\t")
    return int(parts[0]), int(parts[1])


def count_dup():
    out = sql("SELECT COUNT(*) FROM (SELECT user_id, activity_id, sku_id "
              "FROM t_seckill_order GROUP BY user_id, activity_id, sku_id "
              "HAVING COUNT(*) > 1) t;")
    lines = [l for l in out.splitlines() if l.strip() and "Warning" not in l]
    return int(lines[1]) if len(lines) > 1 else 0


def run_stress(total_requests, concurrency, stock=None, buckets=10):
    global _results, _latencies, _errors
    _results = Counter()
    _latencies = []
    _errors = []

    prepare(stock, buckets)
    st = get_stock()
    total_stock = st["total"]

    print(f"\n{'='*66}")
    print(f"秒杀压测：{concurrency} 并发 × 1 次 = {concurrency} 请求"
          f"  |  总库存 {total_stock}  |  分桶 {buckets}")
    if concurrency > total_stock:
        print(f"  场景：并发({concurrency}) > 库存({total_stock})，"
              f"预期出现售罄且不超卖")
    print(f"{'='*66}")

    # 登录数量应等于并发数：每个虚拟用户只发一次请求（模拟一人一次限购），
    # 所以需要 concurrency 个不同用户的 token
    tokens = login_all(concurrency)
    if len(tokens) < concurrency:
        print(f"  仅登录到 {len(tokens)}/{concurrency} 个用户，"
              f"请先运行 seed_stress_users.py 扩充用户")
    real = len(tokens)
    conc = min(concurrency, real)
    if real < concurrency:
        print(f"  实际并发降为 {conc}（受可用用户数限制）")

    barrier = threading.Barrier(conc)
    threads = [threading.Thread(target=worker,
                                args=(tokens[i][1], i, 1, barrier))
               for i in range(conc)]

    t0 = time.perf_counter()
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    elapsed = time.perf_counter() - t0

    qps = conc / elapsed if elapsed > 0 else 0
    print(f"\n【性能】")
    print(f"  并发数     : {conc}")
    print(f"  总耗时     : {elapsed*1000:.0f} ms")
    print(f"  QPS        : {qps:.0f}")
    print(f"  P50        : {percentile(_latencies, 50):.1f} ms")
    print(f"  P95        : {percentile(_latencies, 95):.1f} ms")
    print(f"  P99        : {percentile(_latencies, 99):.1f} ms")
    print(f"  最大       : {max(_latencies) if _latencies else 0:.1f} ms")

    print(f"\n【同步层返回分布】")
    labels = {0: "排队中(已入队)", 1: "抢购成功", 2: "已售罄",
              3: "重复提交(防重)", 4: "活动未开始", 5: "活动已结束", 6: "系统繁忙"}
    for k, v in sorted(_results.items(), key=lambda x: str(x[0])):
        label = labels.get(k, f"错误({k})") if not str(k).startswith("ERR") else f"异常{k[3:]}"
        print(f"  {label:<18}: {v}")

    print(f"\n【异步落单】")
    consumed = consume_all()
    print(f"  消费落单数: {consumed}")

    time.sleep(0.5)
    return verify(total_stock)


def verify(total_stock):
    """5 条硬性判据，全部基于 DB 真实数据"""
    print(f"\n{'='*66}")
    print("【正确性校验】")
    print(f"{'='*66}")

    passed, failed = [], []
    stock = get_stock()
    order_cnt, order_qty = count_orders()
    dup = count_dup()

    print(f"\n  DB 库存: available={stock['available']}, locked={stock['locked']}, "
          f"sold={stock['sold']}, total={stock['total']}")
    print(f"  DB 订单: {order_cnt} 单 / {order_qty} 件\n")

    # 判据 1：不多卖
    if order_qty <= total_stock:
        passed.append("不多卖")
        print(f"  [PASS] 判据1 不超卖：订单 {order_qty} 件 <= 库存 {total_stock} 件")
    else:
        failed.append(f"超卖 {order_qty} > {total_stock}")
        print(f"  [FAIL] 判据1 超卖！订单 {order_qty} 件 > 库存 {total_stock} 件")

    # 判据 2：库存非负
    if stock['available'] >= 0:
        passed.append("库存非负")
        print(f"  [PASS] 判据2 库存非负：available={stock['available']}")
    else:
        failed.append(f"库存为负 {stock['available']}")
        print(f"  [FAIL] 判据2 库存为负：{stock['available']}")

    # 判据 3：总量守恒
    s = stock
    if s['available'] + s['locked'] + s['sold'] == s['total']:
        passed.append("总量守恒")
        print(f"  [PASS] 判据3 总量守恒：{s['available']}+{s['locked']}+{s['sold']}"
              f" = {s['total']}")
    else:
        failed.append("总量不守恒")
        print(f"  [FAIL] 判据3 总量不守恒：{s['available']}+{s['locked']}+{s['sold']}"
              f" != {s['total']}")

    # 判据 4：无重复订单
    if dup == 0:
        passed.append("无重复订单")
        print(f"  [PASS] 判据4 无重复订单（唯一索引生效）")
    else:
        failed.append(f"重复订单 {dup} 组")
        print(f"  [FAIL] 判据4 存在重复订单 {dup} 组")

    # 判据 5：对账平衡
    code, msg, rec = call("GET", f"/seckill/admin/reconcile?activityId={ACTIVITY_ID}")
    if rec and rec.get("allBalanced"):
        passed.append("对账平衡")
        print(f"  [PASS] 判据5 对账平衡：{rec.get('conclusion')}")
    else:
        failed.append("对账不平")
        print(f"  [FAIL] 判据5 对账不平：{rec.get('conclusion') if rec else 'N/A'}")
        if rec:
            for d in rec.get("details", []):
                print(f"         sku={d['skuId']} db={d['dbBalanced']} "
                      f"order={d['orderBalanced']} available={d['available']} "
                      f"orders={d['validOrders']}")

    print(f"\n{'='*66}")
    print(f"结果：通过 {len(passed)} / 5，失败 {len(failed)}")
    if failed:
        print("\n失败明细：")
        for f in failed:
            print(f"  - {f}")
    print(f"{'='*66}")
    return len(failed) == 0


def ladder():
    """容量阶梯压测：逐级加压，找系统拐点"""
    print("\n容量阶梯压测（找拐点）")
    print(f"{'并发':<8}{'QPS':<10}{'P95(ms)':<10}{'最大(ms)':<10}{'成功率':<10}{'判据'}")
    print("-" * 66)
    for c in [10, 30, 60, 100, 150, 200]:
        ok = run_stress(c, c)
        time.sleep(1)
        print()


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("--requests", type=int, default=60)
    p.add_argument("--concurrency", type=int, default=60)
    p.add_argument("--stock", type=int, default=None,
                   help="重建为指定库存量（用于构造少量库存 + 高并发的超卖压力场景）")
    p.add_argument("--buckets", type=int, default=10, help="库存分桶数")
    p.add_argument("--ladder", action="store_true", help="容量阶梯压测")
    args = p.parse_args()

    if args.ladder:
        ladder()
    else:
        ok = run_stress(args.requests, args.concurrency, args.stock, args.buckets)
        sys.exit(0 if ok else 1)
