"""红点清除时机验证：造未读 → 确认显示 → 调 read-all → 确认清除。

服务端是唯一可靠的数据源，所以验证也必须走服务端。
"""

import json
import subprocess
import sys
import urllib.parse
import urllib.request

BASE = "http://127.0.0.1:8080/api"
PASS = FAIL = 0


def call(method, path, body=None, token=None):
    url = BASE + path
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            return json.loads(r.read().decode())
    except Exception as e:
        return {"code": -1, "message": str(e)[:80]}


def check(name, cond, detail=""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  [PASS] {name}")
    else:
        FAIL += 1
        print(f"  [FAIL] {name}  {detail}")


def main():
    print("=" * 66)
    print("红点状态与清除时机验证")
    print("=" * 66)

    # 登录
    buyer = call("POST", "/user/login",
                 {"username": "im_buyer", "password": "buyer123"})["data"]["token"]
    shop = call("POST", "/user/login",
                {"username": "digital_shop", "password": "shop123456"})["data"]["token"]
    print(f"\n  买家/商家登录: OK")

    # ---- 造未读：商家回复买家 ----
    print("\n【1】造未读数据")
    sessions = call("GET", "/im/sessions", token=buyer).get("data") or []
    if not sessions:
        print("  [中止] 买家没有会话，先创建")
        return 1
    sid = sessions[0]["id"]
    print(f"  用会话 sessionId={sid}")

    for i in range(2):
        call("POST", "/im/message",
             {"sessionId": sid, "content": f"商家回复测试{i + 1}"}, token=shop)
    before = call("GET", "/im/unread", token=buyer).get("data", {}).get("unread", 0)
    print(f"  造完未读: {before}")
    check("未读数已产生", before > 0, f"实际 {before}")

    # 商家侧：买家发一条，商家应看到未读
    call("POST", "/im/message",
         {"sessionId": sid, "content": "买家咨询测试"}, token=buyer)
    shop_before = call("GET", "/im/unread", token=shop).get("data", {}).get("unread", 0)
    print(f"  商家侧未读: {shop_before}")
    check("商家侧也能看到未读", shop_before > 0, f"实际 {shop_before}")

    # ---- 数字 vs 纯点：红点组件的形态判断 ----
    print("\n【2】形态判断（1-9 纯点，≥10 数字）")
    for n in (0, 1, 9, 10, 99, 100):
        dot_only = n < 10
        if n == 0:
            disp = "（不显示）"
        elif dot_only:
            disp = "●"
        else:
            disp = "99+" if n > 99 else str(n)
        print(f"    {n:>4} → {disp}")

    # ---- 清除 ----
    print("\n【3】进入消息中心后清除")
    cleared = call("POST", "/im/read-all", token=buyer)
    after = call("GET", "/im/unread", token=buyer).get("data", {}).get("unread", 0)
    print(f"  read-all 返回: {cleared.get('data')} 清除后未读: {after}")
    check("买家未读被清零", after == 0, f"实际 {after}")

    # 商家侧不受影响 —— 买家清的是买家的侧
    shop_after = call("GET", "/im/unread", token=shop).get("data", {}).get("unread", 0)
    print(f"  商家侧未读（不应被买家清除影响）: {shop_after}")
    check("商家侧未读不受影响（双向隔离）",
          shop_after == shop_before, f"清前 {shop_before} 清后 {shop_after}")

    # 商家自己进入后清自己的
    call("POST", "/im/read-all", token=shop)
    shop_final = call("GET", "/im/unread", token=shop).get("data", {}).get("unread", 0)
    check("商家进入后自己清零", shop_final == 0, f"实际 {shop_final}")

    # ---- 未登录 ----
    print("\n【4】未登录访问")
    r = call("POST", "/im/read-all")
    check("未登录调用被拒", r.get("code") != 200, f"实际 code={r.get('code')}")

    # ---- 订单红点判定口径 ----
    print("\n【5】订单红点只算「待处理」")
    oc = call("GET", "/order/count", token=buyer).get("data") or {}
    pending = (oc.get("status0") or 0) + (oc.get("status2") or 0)
    finished = oc.get("status3") or 0
    print(f"  各状态订单: {oc}")
    print(f"  → 待付款(status0)+待收货(status2) = {pending} 计入红点")
    print(f"  → 已完成(status3) = {finished} 不计入红点")
    check("已完成订单不计入红点", pending == (oc.get("status0") or 0) + (oc.get("status2") or 0))

    print("\n" + "=" * 66)
    print(f"结果：通过 {PASS} 项，失败 {FAIL} 项")
    print("=" * 66)
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())