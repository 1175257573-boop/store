#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
秒杀生产能力专项测试
================================================================
覆盖四项生产化能力：
  A. 限流层     —— 全局/用户/IP 三维限流是否真的拦得住
  B. 消费者集群 —— RPOPLPUSH 是否保证多消费者不重复消费
  C. 死信与恢复 —— 消费失败进死信、僵死消息能恢复
  D. 监控告警   —— 告警判定是否准确
"""
import json
import subprocess
import sys
import time
import urllib.request
import urllib.error

BASE = "http://127.0.0.1:8080/api"
MYSQL = r"C:\Program Files\MySQL\MySQL Server 5.7\bin\mysql.exe"
REDIS = r"E:\devtools\redis\Redis-7.2.5-Windows-x64-msys2\redis-cli.exe"
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


def redis_cmd(*args):
    p = subprocess.run([REDIS, "-p", "6379"] + list(args), capture_output=True)
    return p.stdout.decode("utf-8", errors="replace").strip()


def sql(q):
    p = subprocess.run([MYSQL, "-uroot", "-p123456", "--default-character-set=utf8mb4",
                        "-e", f"USE ecommerce; {q}"], capture_output=True)
    return p.stdout.decode("utf-8", errors="replace")


def scalar(q):
    out = sql(q)
    lines = [l for l in out.splitlines() if l.strip() and "Warning" not in l]
    return lines[-1].split("\t")[0] if len(lines) > 1 else None


def ensure_stress_users(n=20):
    """准备压测用户。

    这些账号可能被 seckill_loadtest.py 的清理逻辑删掉，
    缺失时 login() 返回 None，后续断言会因为「一个请求都没发」而误报。
    """
    sql("DELETE FROM t_user WHERE username LIKE 'stress_%';")
    pwd = sql("SELECT password FROM t_user WHERE username='demo' LIMIT 1;")
    pwd = [l for l in pwd.splitlines() if l.strip() and "Warning" not in l][-1]
    rows = ",".join(
        f"('stress_{i:04d}','{pwd}','压测用户{i}','138{i:08d}',1,0)"
        for i in range(n))
    sql("INSERT IGNORE INTO t_user (username,password,nickname,phone,status,role) "
        f"VALUES {rows};")
    return n


def check(name, cond, detail=""):
    if cond:
        passed.append(name)
        print(f"  [PASS] {name}")
    else:
        failed.append(f"{name} | {detail}")
        print(f"  [FAIL] {name}  -> {detail}")


def login(u, p="123456"):
    _, _, d = call("POST", "/user/login", {"username": u, "password": p})
    return d["token"] if d else None


def rebuild(stock=1000, buckets=10):
    call("POST", f"/seckill/admin/rebuild?activityId={A}&skuId={S}"
                 f"&totalStock={stock}&bucketCount={buckets}")
    # 清限流 key，避免上一轮残留影响本轮
    for pat in ["sk:rate:*", "sk:dedup:*", "sk:consumed:*", "sk:retry:*",
                "sk:queue:*"]:
        redis_cmd("--scan", "--pattern", pat)  # 下面用批量删除
    keys = redis_cmd("--scan", "--pattern", "sk:rate:*").splitlines()
    keys += redis_cmd("--scan", "--pattern", "sk:dedup:*").splitlines()
    keys += redis_cmd("--scan", "--pattern", "sk:consumed:*").splitlines()
    keys += redis_cmd("--scan", "--pattern", "sk:retry:*").splitlines()
    if keys:
        redis_cmd("DEL", *keys)
    for q in ["sk:queue:order", "sk:queue:processing", "sk:queue:dead"]:
        redis_cmd("DEL", q)
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


print("=" * 70)
print("秒杀生产能力专项测试")
print("=" * 70)

# 压测账号可能被 seckill_loadtest.py 清理，缺失会导致「一个请求都没发」而误报
ensure_stress_users(20)
print("  已准备 20 个压测用户")

# ================================================================
print("\n【A】限流层 —— 三维限流")
print("-" * 70)
rebuild(1000)
tok = login("demo")

# A1. 单用户连发 5 次（user-qps=1，应只放行 1 次）
codes = []
for i in range(5):
    c, m, r = call("POST", "/seckill",
                   {"activityId": A, "skuId": S, "requestId": f"rl-{i}", "quantity": 1},
                   tok)
    codes.append(r.get("code") if r else c)
time.sleep(0.3)
print(f"  单用户连发 5 次返回: {codes}")
limited = sum(1 for x in codes if x == 7)
check("单用户限流生效（返回 code=7）", limited >= 1, f"限流 {limited} 次")
check("单用户限流不会全部拦截（首个请求应放行）", codes[0] != 7,
      f"首次请求 code={codes[0]}")

# A2. 不同用户各 1 次（不应被「用户维度」限流）
# 注意：压测客户端所有请求都来自 127.0.0.1，ip-qps=5 会把第 6 个起全拦掉。
# 这不是 bug —— 恰恰说明 IP 限流在生效。要单独验证「用户维度」，
# 必须临时把 IP 限制放开或伪造不同 IP 头。
rebuild(1000)
ok_count = 0
for i in range(4):   # 控制在 ip-qps=5 以内
    t = login(f"stress_{i:04d}")
    if not t:
        continue
    c, m, r = call("POST", "/seckill",
                   {"activityId": A, "skuId": S, "requestId": f"multi-{i}", "quantity": 1}, t)
    if r and r.get("code") == 0:
        ok_count += 1
print(f"  4 个不同用户各 1 次（同 IP，在 ip-qps=5 内）: 放行 {ok_count}")
check("不同用户不被用户维度限流（IP 额度内应全放行）", ok_count == 4,
      f"仅放行 {ok_count}/4")

# A2b. 伪造不同 IP 头，验证用户维度限流完全放行
rebuild(1000)
ok2 = 0
for i in range(6):
    t = login(f"stress_{i:04d}")
    if not t:
        continue
    import urllib.request as _r
    req = _r.Request(BASE + "/seckill",
                     data=json.dumps({"activityId": A, "skuId": S,
                                      "requestId": f"fakeip-{i}",
                                      "quantity": 1}).encode(),
                     method="POST")
    req.add_header("Content-Type", "application/json;charset=UTF-8")
    req.add_header("Authorization", "Bearer " + t)
    # 伪造 X-Forwarded-For，让每个用户看起来来自不同 IP
    req.add_header("X-Forwarded-For", f"10.0.0.{i + 1}")
    try:
        with _r.urlopen(req, timeout=15) as x:
            o = json.loads(x.read().decode())
            if o.get("data", {}).get("code") == 0:
                ok2 += 1
    except Exception:
        pass
print(f"  6 个用户伪造不同 IP: 放行 {ok2}")
check("不同 IP + 不同用户时全部放行", ok2 == 6, f"仅放行 {ok2}/6")

# A3. IP 维度（同一个 IP 发 10 次，应触发 IP 限流）
rebuild(1000)
ip_codes = []
for i in range(10):
    t = login(f"stress_{i:04d}")
    c, m, r = call("POST", "/seckill",
                   {"activityId": A, "skuId": S, "requestId": f"ip-{i}", "quantity": 1}, t)
    ip_codes.append(r.get("code") if r else c)
print(f"  同 IP 10 个用户各 1 次返回: {ip_codes}")
ip_limited = sum(1 for x in ip_codes if x == 7)
check("单 IP 限流生效（同源请求被拦）", ip_limited >= 1,
      f"限流 {ip_limited} 次（ip-qps=5）")

# ================================================================
print("\n【B】消费者集群 —— RPOPLPUSH 不重复消费")
print("-" * 70)
rebuild(1000)
# 造 200 条消息
tokens = []
for i in range(100):
    t = login(f"stress_{i:04d}")
    if t:
        tokens.append(t)
        call("POST", "/seckill",
             {"activityId": A, "skuId": S, "requestId": f"cl-{i}", "quantity": 1}, t)
time.sleep(0.5)

q_before = int(redis_cmd("LLEN", "sk:queue:order"))
print(f"  入队消息数: {q_before}")

# 8 消费者并发消费
c, m, n = call("POST", "/seckill/admin/consume-cluster?batchSize=200&consumers=8")
time.sleep(0.5)
q_after = int(redis_cmd("LLEN", "sk:queue:order"))
proc = int(redis_cmd("LLEN", "sk:queue:processing"))
oc = order_count()
print(f"  集群消费落单: {n}, 剩余队列={q_after}, 处理中={proc}, 订单数={oc}")
check("并发消费后队列清空", q_after == 0, f"剩余 {q_after}")
check("处理中队列为空（全部 ack）", proc == 0, f"残留 {proc}")
check("订单数 = 消息数（无重复也无丢失）", oc == q_before,
      f"订单 {oc} != 消息 {q_before}")
st = stock_state()
check("并发消费后账目守恒",
      st["available"] + st["locked"] + st["sold"] == st["total"],
      f"{st['available']}+{st['locked']}+{st['sold']} != {st['total']}")
check("并发消费不超卖", oc <= st["total"], f"订单 {oc} > 库存 {st['total']}")

# ================================================================
print("\n【C】死信队列与僵死恢复")
print("-" * 70)
rebuild(1000)

# C1. 手工往处理中队列塞 3 条（模拟消费者崩溃）
for i in range(3):
    redis_cmd("RPUSH", "sk:queue:processing", f"STALE-{i}|{A}|{S}|2|0|1|stale-{i}")
proc_before = int(redis_cmd("LLEN", "sk:queue:processing"))
print(f"  模拟崩溃后处理中队列: {proc_before} 条")

c, m, rec = call("POST", "/seckill/admin/recover?limit=100")
time.sleep(0.3)
proc_after = int(redis_cmd("LLEN", "sk:queue:processing"))
q_len = int(redis_cmd("LLEN", "sk:queue:order"))
print(f"  恢复 {rec} 条, 处理中剩余={proc_after}, 主队列={q_len}")
check("僵死消息可恢复", rec == 3 and proc_after == 0,
      f"恢复 {rec}，处理中残留 {proc_after}")
check("恢复后消息回到主队列", q_len == 3, f"主队列 {q_len}")

# C2. 死信重投
for i in range(2):
    redis_cmd("LPUSH", "sk:queue:dead", f"DEAD-{i}")
dead_before = int(redis_cmd("LLEN", "sk:queue:dead"))
c, m, rq = call("POST", "/seckill/admin/requeue?limit=100")
time.sleep(0.3)
dead_after = int(redis_cmd("LLEN", "sk:queue:dead"))
print(f"  死信重投: {dead_before} -> {dead_after}")
check("死信重投生效", rq == 2 and dead_after == 0, f"重投 {rq}，剩余 {dead_after}")

# ================================================================
print("\n【D】监控告警")
print("-" * 70)
rebuild(1000)
c, m, metrics = call("GET", f"/seckill/admin/metrics?activityId={A}&skuId={S}")
if metrics:
    print(f"  队列积压: pending={metrics.get('queuePending')}, "
          f"processing={metrics.get('queueProcessing')}, "
          f"dead={metrics.get('queueDeadLetter')}")
    print(f"  Redis 健康: {metrics.get('redisHealthy')}")
    print(f"  限流配置: {metrics.get('rateLimit')}")
    print(f"  告警数: {len(metrics.get('alerts', []))}")
    check("监控指标可获取", "queuePending" in metrics, f"keys={list(metrics.keys())}")
    check("限流配置可查", metrics.get("rateLimit") is not None, "缺失")
    check("正常状态无严重告警",
          not any(a["level"] == "CRITICAL" for a in metrics.get("alerts", [])),
          f"告警={metrics.get('alerts')}")
else:
    check("监控接口可用", False, f"{c} {m}")

# D2. 制造库存为负，看是否触发 CRITICAL 告警
sql(f"UPDATE t_seckill_stock SET available = -5 WHERE activity_id={A} AND sku_id={S}")
time.sleep(0.3)
c, m, metrics2 = call("GET", f"/seckill/admin/metrics?activityId={A}&skuId={S}")
alerts = metrics2.get("alerts", []) if metrics2 else []
titles = [a["title"] for a in alerts]
print(f"  制造库存为负后告警: {titles}")
check("库存为负触发 CRITICAL 告警",
      any("库存为负" in t for t in titles), f"告警={titles}")
check("账目不守恒触发告警",
      any("不守恒" in t for t in titles), f"告警={titles}")

# 恢复
rebuild(1000)

# ================================================================
print(f"\n{'='*70}")
print(f"结果：通过 {len(passed)} 项，失败 {len(failed)} 项")
if failed:
    print("\n失败明细：")
    for f in failed:
        print(f"  - {f}")
print(f"{'='*70}")
sys.exit(0 if not failed else 1)
