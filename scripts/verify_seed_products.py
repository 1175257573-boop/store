"""验证 100 条演示商品在接口层完全可用。

走真实 HTTP 接口而不是查库 —— 数据能查出来不代表接口能返回。
"""
import json
import urllib.parse
import urllib.request

B = "http://127.0.0.1:8080/api"


def get(p):
    r = urllib.request.Request(B + p)
    with urllib.request.urlopen(r, timeout=20) as x:
        return json.loads(x.read().decode())


def q(s):
    return urllib.parse.quote(s)


print("=== 搜索（走 /list?keyword=）===")
for kw in ["笔记本", "手机", "跑鞋", "咖啡", "耳机"]:
    d = get(f"/product/list?keyword={q(kw)}&pageNum=1&pageSize=3")["data"]
    recs = d.get("records") or []
    names = [r["name"][:24] for r in recs[:2]]
    print(f"  '{kw}': total={d.get('total')}, 命中 {names}")

print("\n=== 排序验证 ===")
for s, label in [("sales", "销量降"), ("priceAsc", "价格升"),
                          ("priceDesc", "价格降"), ("newest", "最新")]:
    d = get(f"/product/list?sortBy={s}&pageNum=1&pageSize=3")["data"]
    recs = d.get("records") or []
    txt = [r["name"][:16] + "/" + str(r["price"]) for r in recs[:2]]
    print(f"  {label}: {txt}")

print("\n=== 详情 + SKU ===")
# 取一条本批演示商品（按关键词命中），才能验证新插入的 SKU
d = get("/product/list?keyword=" + q("曜石") + "&pageNum=1&pageSize=3")["data"]
pid = d["records"][0]["id"]
det = get(f"/product/{pid}")["data"]
skus = det.get("skuList") or det.get("skus") or []
print(f"  {det.get('name')}")
print(f"  售价 {det.get('price')} / 划线价 {det.get('originPrice')} "
      f"/ 库存 {det.get('stock')} / 销量 {det.get('sales')}")
print(f"  描述: {(det.get('description') or '')[:100]}")
print(f"  SKU {len(skus)} 个:")
for s in skus[:4]:
    print(f"    {s.get('specText')} | {s.get('price')} "
          f"| 库存 {s.get('stock')} | {s.get('skuCode')}")

print("\n=== 分类接口 ===")
for c in get("/product/categories")["data"]:
    print(f"  {c.get('name')} {c.get('icon')}")

print("\n=== 关联推荐（验证详情页推荐位有数据）===")
rel = get(f"/product/{pid}/related?limit=4")["data"]
print(f"  推荐 {len(rel)} 条: {[r['name'][:18] for r in rel[:3]]}")

print("\n=== 分页边界（末页不应报错）===")
for pn in [1, 10, 11]:
    d = get(f"/product/list?pageNum={pn}&pageSize=12")["data"]
    recs = d.get("records") or []
    print(f"  第{pn}页: {len(recs)} 条, total={d.get('total')}, "
          f"pages={d.get('pages')}")
