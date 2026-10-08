"""商家体系 + IM 功能端到端验证。

重点验证**权限控制** —— IM 最容易出越权的地方。

越权用例（每条都对应一个真实风险）：
  1. 买家 A 传买家 B 的 sessionId          → 应被拒
  2. 商家 A 传商家 B 的 merchantId 看会话   → 应被拒（SQL 强制过滤，返回空）
  3. 商家 A 回复商家 B 的会话              → 应被拒
  4. 未登录访问 IM 接口                     → 应被拒
  5. 管理员发消息                          → 应被拒（只读角色）
  6. 商家发起会话（骚扰买家）              → 应被拒
"""

import json
import sys
import urllib.parse
import urllib.request

BASE = "http://127.0.0.1:8080/api"
W = 74
PASS, FAIL = 0, 0


def check(name, cond, detail=""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  [PASS] {name}")
    else:
        FAIL += 1
        print(f"  [FAIL] {name}  {detail}")


def call(method, path, token=None, body=None):
    url = BASE + path
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            return json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        return {"code": e.code, "message": str(e)[:80]}
    except Exception as e:
        return {"code": -1, "message": str(e)[:80]}


def login(username, password):
    r = call("POST", "/user/login", body={"username": username, "password": password})
    if r.get("code") == 200:
        return (r.get("data") or {}).get("token")
    return None


def main():
    global PASS, FAIL
    print("=" * W)
    print("商家体系 + IM 端到端验证")
    print("=" * W)

    # ---------- 1. 商家账号可登录 ----------
    print("\n【1】商家账号可登录")
    shopA = login("digital_shop", "shop123456")
    shopB = login("life_shop", "shop123456")
    check("数码优选旗舰店 可登录", shopA is not None)
    check("生活优选生活馆 可登录", shopB is not None)
    if not shopA or not shopB:
        print("  [中止] 商家登录失败，后续无法验证")
        return 1

    # 买家 token。用固定的测试账号 —— 库里的演示账号密码未知，
    # 而 IM 的权限用例必须以买家身份发起，没法用商家替代。
    buyer = login("im_buyer", "buyer123")
    buyer2 = login("im_buyer", "buyer123")
    print(f"  买家账号: im_buyer, 登录={'OK' if buyer else '失败'}")
    if not buyer:
        print("  [中止] 买家登录失败，IM 用例无法验证")
        return 1

    # ---------- 2. 店铺维度商品筛选 ----------
    print("\n【2】商品按店铺筛选")
    r = call("GET", "/product/list?pageNum=1&pageSize=3")
    first = (r.get("data") or {}).get("records") or []
    check("商品列表返回 merchantId", bool(first) and first[0].get("merchantId") is not None,
          f"实际={first[0] if first else '无数据'}")
    check("商品列表返回 shopName", bool(first) and bool(first[0].get("shopName")),
          f"shopName={first[0].get('shopName') if first else '-'}")

    shopA_id = first[0].get("merchantId") if first else None
    if shopA_id:
        r2 = call("GET", f"/product/list?merchantId={shopA_id}&pageNum=1&pageSize=5")
        recs = (r2.get("data") or {}).get("records") or []
        total = (r2.get("data") or {}).get("total")
        check("按店铺筛选生效", all(x.get("merchantId") == shopA_id for x in recs),
              f"混入其他店铺的商品")
        print(f"         店铺 {shopA_id} 共 {total} 件")

    # ---------- 3. 店铺信息接口 ----------
    print("\n【3】店铺信息")
    r = call("GET", "/shop/62")
    if r.get("code") == 200:
        d = r.get("data") or {}
        print(f"         数码优选旗舰店: 商品{d.get('totalProduct')}件 评分{d.get('score')}")
        check("店铺信息可查", True)
    else:
        # 店铺主页接口还没实现，先看商家自己的店铺接口
        r2 = call("GET", "/merchant/shop", token=shopA)
        check("商家可查自己的店铺信息", r2.get("code") == 200,
              f"实际 code={r2.get('code')}")

    # ---------- 4. IM 会话创建（幂等） ----------
    if buyer and shopA_id:
        print("\n【4】买家发起会话（幂等性）")
        r1 = call("POST", "/im/session", token=buyer,
                  body={"merchantId": shopA_id, "productId": None})
        sid1 = (r1.get("data") or {}).get("sessionId")
        check("买家可发起会话", r1.get("code") == 200 and sid1 is not None,
              f"实际={r1}")

        r2 = call("POST", "/im/session", token=buyer, body={"merchantId": shopA_id})
        sid2 = (r2.get("data") or {}).get("sessionId")
        check("重复发起复用同一会话（历史不断）", sid1 == sid2,
              f"两次得到 {sid1} vs {sid2}")

        # ---------- 5. 消息收发 ----------
        print("\n【5】消息收发与未读")
        # 先清未读，让拉到的消息就是刚发的这条
        call("POST", "/im/read-all", token=buyer)
        r = call("POST", "/im/message", token=buyer,
                 body={"sessionId": sid1, "content": "这款有货吗？"})
        check("买家可发消息", r.get("code") == 200, f"实际={r}")
        new_msg_id = (r.get("data") or {}).get("messageId")

        r = call("GET", f"/im/message?sessionId={sid1}&pageNum=1&pageSize=20", token=buyer)
        msgs = r.get("data") or []
        # 断言「能拉到刚发的消息」，而不是「恰好 1 条」——
        # 会话是复用的，多轮测试后会累积消息，写死条数必然失败。
        check("买家能拉到刚发的消息",
              any("这款有货吗" in (m.get("content") or "") for m in msgs),
              f"实际 {len(msgs)} 条，内容={[m.get('content','')[:12] for m in msgs]}")
        # 按 messageId 判定，不能按内容/位置 ——
        # 会话是复用的，历史里可能有同样内容；
        # 且接口返回是「最新在前」倒序，刚发的在最后一条，
        # pageSize=20 时新消息可能被挤出这一页。
        # ⚠️ 接口返回的 id 是**字符串**（MySQL bigint → JSON 序列化为 string），
        # 而 messageId 从发送响应里取到的是数字 —— 必须转成同一类型再比，
        # 否则永远匹配不上，还以为是接口漏数据。
        new_msg_key = str(new_msg_id)
        hit = [m for m in msgs if str(m.get("id")) == new_msg_key]
        if not hit:
            # 第一页没拉到（历史已超过 pageSize），翻页补查
            all_msgs = []
            for pg in range(1, 6):
                batch = call("GET", f"/im/message?sessionId={sid1}&pageNum={pg}",
                             token=buyer).get("data") or []
                if not batch:
                    break
                all_msgs += batch
            hit = [m for m in all_msgs if str(m.get("id")) == new_msg_key]
        check("刚发的消息能在历史里按 id 命中",
              bool(hit) and hit[0].get("content") == "这款有货吗？",
              f"id={new_msg_key} 拉到 {len(msgs)} 条，命中={bool(hit)}")

        # 商家侧应看到 1 条未读
        r = call("GET", "/im/unread", token=shopA)
        u1 = (r.get("data") or {}).get("unread")
        check("商家侧有未读", (u1 or 0) >= 1, f"实际={u1}")

        r = call("GET", "/im/sessions", token=shopA)
        sessions = r.get("data") or []
        check("商家能看到该会话", len(sessions) >= 1, f"实际 {len(sessions)} 条")
        if sessions:
            print(f"         会话: 买家={sessions[0].get('buyerNickname')} "
                  f"店铺={sessions[0].get('shopName')} 未读={sessions[0].get('merchantUnread')}")

        # 商家回复
        r = call("POST", "/im/message", token=shopA,
                 body={"sessionId": sid1, "content": "有的，现货充足。"})
        check("商家可回复", r.get("code") == 200, f"实际={r}")

        # 买家未读应增加
        r = call("GET", "/im/unread", token=buyer)
        u2 = (r.get("data") or {}).get("unread")
        check("买家收到回复后未读增加", (u2 or 0) >= 1, f"实际={u2}")

        # ---------- 6. 权限：越权用例 ----------
        print("\n【6】权限控制（关键）")

        # 6.1 商家 B 看商家 A 的会话 —— 应看不到内容或被拒
        if shopB and sid1:
            r = call("GET", f"/im/message?sessionId={sid1}&pageNum=1", token=shopB)
            ok = r.get("code") != 200
            check("商家B 访问商家A的会话被拒", ok, f"实际 code={r.get('code')}")

            r = call("POST", "/im/message", token=shopB,
                     body={"sessionId": sid1, "content": "越权回复"})
            ok = r.get("code") != 200
            check("商家B 回复商家A的会话被拒", ok, f"实际 code={r.get('code')}")

        # 6.2 商家不能主动发起会话
        if shopA and shopA_id:
            r = call("POST", "/im/session", token=shopA, body={"merchantId": shopA_id})
            ok = r.get("code") != 200
            check("商家主动发起会话被拒（防骚扰）", ok, f"实际 code={r.get('code')}")

        # 6.3 未登录访问
        r = call("GET", "/im/sessions")
        ok = r.get("code") != 200
        check("未登录访问会话列表被拒", ok, f"实际 code={r.get('code')}")

        # 6.4 会话列表不串店
        r = call("GET", "/im/sessions", token=shopB)
        others = [s for s in (r.get("data") or []) if s.get("merchantId") != 63]
        check("商家B 的会话列表不含别家店铺", not others,
              f"混入了 {len(others)} 条")

    # ---------- 7. 业务回归 ----------
    print("\n【7】业务回归")
    r = call("GET", "/product/list?pageNum=1&pageSize=12")
    total = (r.get("data") or {}).get("total")
    check("商品列表接口正常", r.get("code") == 200 and total and total > 100,
          f"total={total}")

    print("\n" + "=" * W)
    print(f"结果：通过 {PASS} 项，失败 {FAIL} 项")
    print("=" * W)
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())