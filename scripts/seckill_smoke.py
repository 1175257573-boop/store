import json
import urllib.request
import urllib.error

BASE = "http://127.0.0.1:8080/api"
A, S = 1, 1


def call(m, p, d=None, t=None):
    r = urllib.request.Request(BASE + p,
                               data=json.dumps(d).encode() if d is not None else None,
                               method=m)
    r.add_header("Content-Type", "application/json;charset=UTF-8")
    if t:
        r.add_header("Authorization", "Bearer " + t)
    try:
        with urllib.request.urlopen(r, timeout=15) as x:
            o = json.loads(x.read().decode())
            return o.get("code"), o.get("message"), o.get("data")
    except urllib.error.HTTPError as e:
        return -1, f"HTTP {e.code}", None
    except Exception as e:
        return -2, str(e), None


print("=== 1. 登录 ===")
_, _, lg = call("POST", "/user/login", {"username": "demo", "password": "123456"})
tok = lg["token"]
print("  token 获取:", "OK" if tok else "FAIL")

print("\n=== 2. 重置活动 + 初始化库存 ===")
print("  reset:", call("POST", f"/seckill/admin/reset/{A}"))
print("  init :", call("POST", f"/seckill/admin/init/{A}"))

print("\n=== 3. 查库存详情 ===")
c, m, d = call("GET", f"/seckill/stock?activityId={A}&skuId={S}")
if d:
    print(f"  DB库存: available={d['dbStock']['available']} total={d['dbStock']['totalStock']}")
    print(f"  分桶数: {d['bucketCount']}")
    print(f"  Redis分桶: {d['redisBuckets']}")
    print(f"  Redis合计: {d['redisTotal']}")
    print(f"  队列长度: {d['queueLength']}")
else:
    print("  FAIL", c, m)

print("\n=== 4. 发起秒杀（第一次）===")
req = {"activityId": A, "skuId": S, "requestId": "test-req-1", "quantity": 1}
c, m, r = call("POST", "/seckill", req, tok)
print(f"  返回: code={r.get('code') if r else c} msg={r.get('message') if r else m} "
      f"remaining={r.get('remaining') if r else 'N/A'}")

print("\n=== 5. 同一用户重复提交（应被防重拦截）===")
c, m, r2 = call("POST", "/seckill", {**req, "requestId": "test-req-2"}, tok)
print(f"  返回: code={r2.get('code') if r2 else c} msg={r2.get('message') if r2 else m}"
      "   (期望 code=3 重复提交)")

print("\n=== 6. 消费队列（异步落单）===")
c, m, n = call("POST", "/seckill/admin/consume?batchSize=100")
print(f"  落单数: {c == 200 and n}  {m if c != 200 else ''}")

print("\n=== 7. 落单后库存 ===")
c, m, d2 = call("GET", f"/seckill/stock?activityId={A}&skuId={S}")
if d2:
    print(f"  DB: available={d2['dbStock']['available']} locked={d2['dbStock']['locked']} "
          f"sold={d2['dbStock']['sold']}")
    print(f"  Redis合计: {d2['redisTotal']}")
    print(f"  有效订单数: {d2['validOrderCount']}")
    print(f"  队列长度: {d2['queueLength']}")

print("\n=== 8. 对账 ===")
c, m, rec = call("GET", f"/seckill/admin/reconcile?activityId={A}")
if rec:
    print(f"  账目平衡: {rec['allBalanced']}")
    print(f"  结论: {rec['conclusion']}")
    print(f"  有效订单: {rec['validOrders']}  净预扣: {rec['netPreDeduct']}")

print("\n=== 9. 我的秒杀订单 ===")
c, m, orders = call("GET", "/seckill/order/my", t=tok)
if orders:
    for o in orders:
        print(f"  {o['orderNo']} sku={o['skuId']} qty={o['quantity']} "
              f"status={o['status']} amount={o['amount']}")
else:
    print("  无订单", c, m)
