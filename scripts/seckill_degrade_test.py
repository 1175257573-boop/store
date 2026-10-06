#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
降级预案验证：Redis 不可用时是否仍能安全服务。

验证要点：
  1. Redis 挂掉后请求仍能进入（不雪崩）
  2. 走 DB 直连路径，正确性由条件更新保证（不超卖）
  3. Redis 恢复后自动切回正常模式
"""
import json
import os
import subprocess
import sys
import time
import urllib.request
import urllib.error

BASE = "http://127.0.0.1:8080/api"
MYSQL = r"C:\Program Files\MySQL\MySQL Server 5.7\bin\mysql.exe"
REDIS_DIR = r"E:\devtools\redis\Redis-7.2.5-Windows-x64-msys2"
# 用 os.path.join 拼可执行文件路径：f-string 里写 Windows 反斜杠极易被转义吃掉
REDIS_CLI = os.path.join(REDIS_DIR, "redis-cli.exe")
REDIS_SERVER = os.path.join(REDIS_DIR, "redis-server.exe")
A, S = 1, 1

passed, failed = [], []


def call(m, p, d=None, t=None, timeout=25):
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
    _, _, d = call("POST", "/user/login", {"username": u, "password": p})
    return d["token"] if d else None


def stop_redis():
    subprocess.run(["taskkill", "/F", "/IM", "redis-server.exe"],
                   capture_output=True)
    time.sleep(2)


def start_redis():
    """启动 Redis。

    注意：不能给 --save 传空字符串——subprocess 会把它原样传下去，
    Redis 会把 "" 当成非法配置文件路径。直接省略 --save 即可（开发环境不需要持久化）。
    """
    subprocess.Popen(
        [REDIS_SERVER, "--port", "6379", "--appendonly", "no"],
        cwd=REDIS_DIR,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL
    )
    # 等待端口就绪，最多 10 秒
    for _ in range(20):
        time.sleep(0.5)
        p = subprocess.run([REDIS_CLI, "-p", "6379", "ping"],
                           capture_output=True)
        if b"PONG" in p.stdout:
            return True
    return False


def rebuild(stock=100):
    """重建活动到指定库存。

    注意：不能只调 /seckill/admin/rebuild —— 它会走 Redis 清 key，
    而本测试要模拟 Redis 挂掉的场景，届时该接口本身就会失败。
    这里直接改 DB，Redis 侧的 key 在测试开始前统一清理。
    """
    sql(f"UPDATE t_seckill_stock SET total_stock={stock}, available={stock}, "
        f"locked=0, sold=0 WHERE activity_id={A} AND sku_id={S};")
    # 先删明细再删订单（有外键，顺序不能反）
    sql(f"DELETE FROM t_seckill_order_item WHERE order_id IN "
        f"(SELECT id FROM t_seckill_order WHERE activity_id={A});")
    sql(f"DELETE FROM t_seckill_order WHERE activity_id={A};")
    sql(f"DELETE FROM t_seckill_pre_deduct WHERE activity_id={A};")
    # Redis 侧的防重/限流/消费标记必须清，否则「已参与过」会拦截本次请求
    p = subprocess.run([REDIS_CLI, "-p", "6379", "--scan", "--pattern", "sk:*"],
                       capture_output=True)
    keys = p.stdout.decode().split()
    # 保留库存 key（rebuild 之外还需要），其余全清
    keys = [k for k in keys if not k.startswith("sk:stock:")]
    if keys:
        subprocess.run([REDIS_CLI, "-p", "6379", "DEL"] + keys,
                       capture_output=True)
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


print("=" * 70)
print("降级预案验证（Redis 故障注入）")
print("=" * 70)

# ----------------------------------------------------------------
print("\n【阶段 1】基线：Redis 正常")
print("-" * 70)
rebuild(100)
# 用独立用户：0000-0099 可能已被前面的压测用过，会走防重分支
t1 = login("stress_0200")
c, m, r = call("POST", "/seckill",
               {"activityId": A, "skuId": S, "requestId": "base-1", "quantity": 1}, t1)
print(f"  正常模式返回: code={r.get('code') if r else c}, msg={r.get('message') if r else m}")
check("Redis 正常时走异步模式（code=0）", r and r.get("code") == 0,
      f"实际 {r.get('code') if r else c}")

# ----------------------------------------------------------------
print("\n【阶段 2】注入故障：关闭 Redis")
print("-" * 70)

# 【关键】必须在停 Redis 之前拿好 token。
# 登录要写 JWT 白名单到 Redis，Redis 挂了就登录不了，
# 一个 token 都拿不到，压根打不出请求，降级逻辑也就无从验证。
# 用 100-109 号用户（0000-0099 已被前面的压测用掉，会被防重拦）
tokens = {}
for i in range(100, 110):
    t = login(f"stress_{i:04d}")
    if t:
        tokens[f"stress_{i:04d}"] = t
print(f"  已提前登录 {len(tokens)} 个用户")

# 用 DB 直接重置到干净状态（rebuild 接口依赖 Redis，此时已不可用）
rebuild(100)

stop_redis()
print("  Redis 已停止")
# 等后端的 Redis 健康探测（3 秒缓存）过期，确保它已判定为不可用
time.sleep(4)

st_before = stock_state()
print(f"  故障前库存: {st_before}")

# 打请求
results = []
for i, (u, t) in enumerate(tokens.items()):
    if not t:
        continue
    c, m, r = call("POST", "/seckill",
                   {"activityId": A, "skuId": S,
                    "requestId": f"deg-{i}", "quantity": 1}, t)
    results.append({
        "code": r.get("code") if r else c,
        "degraded": (r or {}).get("degraded"),
        "orderNo": (r or {}).get("orderNo")
    })
time.sleep(0.5)

ok = [x for x in results if x["code"] in (0, 1)]
degraded = [x for x in results if x["degraded"]]
print(f"  降级模式返回分布: {[(x['code'], x['degraded']) for x in results]}")
print(f"  成功受理 {len(ok)} 个，其中标记降级 {len(degraded)} 个")

check("Redis 挂掉后请求不被全部拒绝（不雪崩）", len(ok) > 0,
      f"仅 {len(ok)}/{len(results)} 个被受理")
check("降级请求带 degraded 标记", len(degraded) > 0,
      f"仅 {len(degraded)} 个带标记")

st_after = stock_state()
print(f"  故障后库存: {st_after}")
check("降级后账目守恒",
      st_after["available"] + st_after["locked"] + st_after["sold"] == st_after["total"],
      f"{st_after['available']}+{st_after['locked']}+{st_after['sold']} != {st_after['total']}")
check("降级后库存不为负", st_after["available"] >= 0,
      f"available={st_after['available']}")

# 核心断言：降级路径（同步落单）也必须不超卖
oc = int(scalar("SELECT COUNT(*) FROM t_seckill_order "
                f"WHERE activity_id={A} AND status IN (0,1)"))
check("降级模式不超卖（订单数 <= 库存）", oc <= st_after["total"],
      f"订单 {oc} > 库存 {st_after['total']}")
check("降级模式有实际落单", oc > 0, f"订单数 {oc}")

# ----------------------------------------------------------------
print("\n【阶段 3】恢复 Redis")
print("-" * 70)
start_redis()
p = subprocess.run([REDIS_CLI, "-p", "6379", "ping"],
                   capture_output=True)
print(f"  Redis ping: {p.stdout.decode().strip()}")
check("Redis 已恢复", b"PONG" in p.stdout)

# 恢复后重建并初始化 Redis 库存，验证切回异步模式
rebuild(100)
call("POST", f"/seckill/admin/init/{A}")
time.sleep(4)   # 等后端 3 秒健康缓存过期，确认它已切回正常模式
t2 = login("stress_0380")
c, m, r = call("POST", "/seckill",
               {"activityId": A, "skuId": S, "requestId": "recover-1", "quantity": 1}, t2)
print(f"  恢复后返回: code={r.get('code') if r else c}, "
      f"degraded={(r or {}).get('degraded')}")
check("恢复后切回异步模式（code=0）", r and r.get("code") == 0,
      f"实际 {r.get('code') if r else c}")
check("恢复后不再标记降级", not (r or {}).get("degraded"),
      f"degraded={(r or {}).get('degraded')}")

st_final = stock_state()
check("恢复后账目仍守恒",
      st_final["available"] + st_final["locked"] + st_final["sold"] == st_final["total"],
      f"{st_final['available']}+{st_final['locked']}+{st_final['sold']} != {st_final['total']}")

print(f"\n{'='*70}")
print(f"结果：通过 {len(passed)} 项，失败 {len(failed)} 项")
if failed:
    print("\n失败明细：")
    for f in failed:
        print(f"  - {f}")
print(f"{'='*70}")
sys.exit(0 if not failed else 1)
