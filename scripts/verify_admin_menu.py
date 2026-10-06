#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
管理员后台菜单与角色识别验证

复现并验证三个已修复的问题：
  1. 管理员登录后被引导去「申请商家入驻」
  2. 「入驻审核」点进去是申请店铺页
  3. 「商品审核」点进去是入驻审核页，且商品审核无入口
"""
import base64
import json
import re
import sys
import urllib.request
import urllib.error

FRONTEND = "E:/WorkBuddy/store/ecommerce/frontend/src"
passed, failed = [], []


def check(name, cond, detail=""):
    if cond:
        passed.append(name)
        print(f"  [PASS] {name}")
    else:
        failed.append(f"{name} | {detail}")
        print(f"  [FAIL] {name}  -> {detail}")


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


print("=" * 70)
print("管理员后台菜单与角色识别验证")
print("=" * 70)

# ----------------------------------------------------------------
print("\n【1】路由定义与菜单的对应关系")
print("-" * 70)

router = read(f"{FRONTEND}/router/index.js")
layout = read(f"{FRONTEND}/layout/MerchantLayout.vue")

# 抽取路由 path -> 组件映射
route_map = dict(re.findall(
    r"path:\s*'([\w/-]+)',\s*name:\s*'[\w-]+',\s*\n\s*component:\s*\(\)\s*=>\s*import\('@/views/merchant/(\w+)\.vue'\)",
    router))
print("  路由定义:")
for k, v in route_map.items():
    print(f"    {k:<28} -> {v}.vue")

# 校验菜单每一项的 index 都能对应到真实路由，且组件语义正确
menu_items = re.findall(
    r'<el-menu-item[^>]*index="(/merchant/[\w-]*)"[^>]*>\s*<el-icon><(\w+)\s*/></el-icon>([^<]+)',
    layout)
print("\n  菜单项:")
for idx, icon, label in menu_items:
    label = label.strip()
    print(f"    {label:<12} index={idx}")
    check(f"菜单「{label}」路径 {idx} 有对应路由",
          any(idx == f"/merchant/{k}" or idx == k for k in route_map),
          f"路由表里没有 {idx}")

# 核心校验：入驻审核与商品审核不能错位
audit_route = next((k for k, v in route_map.items() if v == "ApplyAuditView"), None)
paudit_route = next((k for k, v in route_map.items() if v == "ProductAuditView"), None)
print(f"\n  ApplyAuditView 路由: {audit_route}")
print(f"  ProductAuditView 路由: {paudit_route}")

menu_idx = {idx: label.strip() for idx, _, label in menu_items}
check("「入驻审核」指向 ApplyAuditView",
      menu_idx.get(f"/merchant/{audit_route}") == "入驻审核",
      f"实际指向 {menu_idx.get(f'/merchant/{audit_route}')}")
check("「商品审核」指向 ProductAuditView",
      menu_idx.get(f"/merchant/{paudit_route}") == "商品审核",
      f"实际指向 {menu_idx.get(f'/merchant/{paudit_route}')}")

# 管理员菜单里不该有「申请店铺」
check("管理员菜单里没有「申请店铺/商家入驻」入口",
      "申请店铺" not in layout and "商家入驻" not in
      [l.strip() for _, _, l in menu_items],
      "管理员菜单不该出现入驻申请页")

# 商品审核必须有入口
check("商品审核有菜单入口（非孤儿路由）",
      f"/merchant/{paudit_route}" in menu_idx,
      f"路由 {paudit_route} 没有任何菜单项指向它")

# ----------------------------------------------------------------
print("\n【2】角色识别：JWT 兜底")
print("-" * 70)

store = read(f"{FRONTEND}/stores/user.js")
check("store 从 JWT 载荷兜底解析 role",
      "roleFromToken" in store and "atob" in store,
      "缺少 JWT 兜底逻辑")
check("role 是计算属性而非直接读 userInfo",
      "const role = computed" in store,
      "role 应对 userInfo.role 缺失做兜底")
check("导出 role 供路由守卫使用",
      "role," in store.split("return {")[-1],
      "return 里没有导出 role")

guard = router.split("router.beforeEach")[-1]
check("路由守卫复用 store 而非自己读 localStorage",
      "useUserStore()" in guard and "JSON.parse(raw)" not in guard,
      "守卫不应重复解析 localStorage")

# ----------------------------------------------------------------
print("\n【3】按角色区分可见菜单")
print("-" * 70)

check("商家页面用 merchantOnly 标记",
      "merchantOnly: true" in router,
      "dashboard/product/order/after-sale/shop 应标 merchantOnly")
check("管理员访问商家页会被引导",
      "to.meta.merchantOnly && isAdmin" in guard,
      "管理员没有自己的店，不应进经营页")
# 重定向应复用 store 的 isAdmin（内部含 JWT 兜底），
# 而不是自己读 localStorage 判角色
check("默认落地页按角色分流",
      "useUserStore().isAdmin ? '/merchant/audit'" in router,
      "管理员进 /merchant 应落到审核页")

# ----------------------------------------------------------------
print("\n【4】后端角色数据正确性")
print("-" * 70)


def call(method, path, data=None, token=None):
    req = urllib.request.Request(
        "http://127.0.0.1:8080/api" + path,
        data=json.dumps(data).encode() if data is not None else None,
        method=method)
    req.add_header("Content-Type", "application/json;charset=UTF-8")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=15) as x:
            o = json.loads(x.read().decode())
            return o.get("code"), o.get("message"), o.get("data")
    except urllib.error.HTTPError as e:
        return -1, f"HTTP {e.code}", None
    except Exception as e:
        return -2, str(e), None


code, msg, admin = call("POST", "/user/login",
                       {"username": "admin", "password": "123456"})
check("管理员登录成功", code == 200 and admin, f"{code} {msg}")
check("登录返回 role=2", admin and admin.get("role") == 2,
      f"role={admin.get('role') if admin else 'N/A'}")
check("登录返回 roleText", admin and admin.get("roleText") == "平台管理员",
      f"roleText={admin.get('roleText') if admin else 'N/A'}")

# JWT 里必须带 role，否则前端兜底无效
if admin:
    payload = admin["token"].split(".")[1]
    payload += "=" * (-len(payload) % 4)
    claims = json.loads(base64.urlsafe_b64decode(payload))
    check("JWT 载荷含 role claim", claims.get("role") == 2,
          f"JWT role={claims.get('role')}")

# 管理员访问两个审核接口
code, msg, applies = call("GET", "/merchant/admin/applies?status=0",
                          token=admin["token"])
check("管理员可查入驻申请列表", code == 200 and applies is not None,
      f"{code} {msg}")

code, msg, audits = call("GET", "/merchant/admin/audits", token=admin["token"])
check("管理员可查待审核商品", code == 200 and audits is not None,
      f"{code} {msg}")
print(f"       当前待审核商品数: {len(audits or [])}")

# 商家访问审核接口应被拒
_, _, shop = call("POST", "/user/login", {"username": "shop_a", "password": "123456"})
if shop:
    code, msg, _ = call("GET", "/merchant/admin/applies", token=shop["token"])
    check("商家访问入驻审核接口被拒", code == 6002, f"{code} {msg}")

print(f"\n{'='*70}")
print(f"结果：通过 {len(passed)} 项，失败 {len(failed)} 项")
if failed:
    print("\n失败明细：")
    for f in failed:
        print(f"  - {f}")
print(f"{'='*70}")
sys.exit(0 if not failed else 1)
