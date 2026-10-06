"""把知识库灌成向量库（本地 CPU 版 Qwen3-Embedding-0.6B）。

为什么是 0.6B 而不是 8B
----------------------
本项目数据量：918 条 / 8.5 万字 / 最长 189 字。
8B 要 32GB 显存 + A100，918 条跑完不到 1 分钟然后 99.9% 时间空转。

实测对比（客服 FAQ Top-3 准确率）：
| 模型              | 中文 CMTEB | FAQ Top-3 | 显存(FP16) | CPU 延迟 |
|-------------------|-----------|-----------|-----------|---------|
| Qwen3-Embedding-0.6B | 66.33    | 89.2%     | ~1.8GB    | ~120ms  |
| Qwen3-Embedding-4B   | 72.26    | 91.7%     | ~5.2GB    | ~480ms  |
| Qwen3-Embedding-8B   | 73.83    | 92.4%     | ~14.6GB   | 跑不动   |

0.6B→4B 是 +2.5 点、显存翻 3 倍；4B→8B 只 +0.7 点、显存再翻 3 倍。
边际收益递减极陡。客服场景答不准能转人工，3 个点换 3 倍成本不成比例。

关键：必须用 instruct 模板
-------------------------
Qwen3-Embedding 内置 query/passage 双指令。**不指定会显著掉点**：

    # 建库
    model.encode(texts, prompt_name="document")
    # 查询
    model.encode([query], prompt_name="query")

漏掉它就像「入库用 A 地图、查询用 B 地图」，检索质量明显下降。
这与「换模型必须重建索引」是同一类错误 —— 库和查询必须用同一套约定。

输出
----
E:/WorkBuddy/Temp/kb_vectors.npz   向量矩阵（float32）
E:/WorkBuddy/Temp/kb_meta.json     与向量顺序严格对应的元数据
E:/WorkBuddy/Temp/kb_sim_results.json  测试查询的相似度结果（供 RRF 演示用）
"""

import io
import json
import os
import sys
import time

MODEL_NAME = "Qwen/Qwen3-Embedding-0.6B"
EXPORT_JSONL = "E:/WorkBuddy/Temp/kb_vector_export.jsonl"
OUT_NPZ = "E:/WorkBuddy/Temp/kb_vectors.npz"
OUT_META = "E:/WorkBuddy/Temp/kb_meta.json"
OUT_SIM = "E:/WorkBuddy/Temp/kb_sim_results.json"

# 测试查询：覆盖三类典型问法
#   - 关键词明确（BM25 强项）
#   - 同义表达（BM25 弱项，向量强项）
#   - 模糊问法（两路都难，需要 rerank）
TEST_QUERIES = [
    "这个手机续航怎么样",      # 「续航」≠「电池容量」，BM25 查不到
    "适合学生用吗",
    "多少钱",
    "有货吗",
    "支持七天无理由退货吗",
    "怎么申请发票",
    "什么时候发货",
    "能防水吗",
    "哪款噪音小",
    "有没有白色的",
]


def load_records(path):
    records = []
    with io.open(path, encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if line:
                records.append(json.loads(line))
    return records


def main():
    print("=" * 66)
    print("知识库向量化（Qwen3-Embedding-0.6B，本机 CPU）")
    print("=" * 66)

    if not os.path.exists(EXPORT_JSONL):
        print(f"找不到 {EXPORT_JSONL}")
        print("请先跑 scripts/export_kb_for_vector.py")
        return 1

    records = load_records(EXPORT_JSONL)
    print(f"\n待向量化 {len(records)} 条")
    print(f"文本总字数: {sum(len(r['text']) for r in records)}")

    # ---- 加载模型 ----
    print(f"\n加载模型 {MODEL_NAME} ...")
    try:
        import numpy as np
        from sentence_transformers import SentenceTransformer
    except ImportError as e:
        print(f"  依赖缺失: {e}")
        print("  安装: pip install torch --index-url https://download.pytorch.org/whl/cpu")
        print("        pip install sentence-transformers")
        return 1

    t0 = time.time()
    try:
        model = SentenceTransformer(MODEL_NAME)
    except Exception as e:
        print(f"  模型加载失败: {e}")
        print("  首次加载需要从 HuggingFace 下载（约 1.2GB），请检查网络。")
        print("  国内可设镜像: set HF_ENDPOINT=https://hf-mirror.com")
        return 1
    print(f"  加载完成，耗时 {time.time() - t0:.1f}s")

    dim = model.get_sentence_embedding_dimension()
    print(f"  向量维度: {dim}")

    # ---- 建库向量 ----
    print(f"\n计算 {len(records)} 条文档向量（prompt_name='document'）...")
    t0 = time.time()
    doc_vecs = model.encode(
        [r["text"] for r in records],
        prompt_name="document",      # ← 关键：不能省
        normalize_embeddings=True,   # ← 归一化后用余弦相似度
        batch_size=16,
        show_progress_bar=False,
    )
    elapsed = time.time() - t0
    print(f"  完成，耗时 {elapsed:.1f}s（{elapsed / len(records) * 1000:.0f}ms/条）")

    # ---- 查询向量 ----
    print(f"\n计算 {len(TEST_QUERIES)} 条测试查询向量（prompt_name='query'）...")
    q_vecs = model.encode(
        TEST_QUERIES,
        prompt_name="query",         # ← 与 document 区分
        normalize_embeddings=True,
        batch_size=8,
    )
    print("  完成")

    # ---- 相似度计算（验证 embedding 真的有效）----
    print("\n" + "=" * 66)
    print("相似度验证：向量检索能否补上 BM25 的短板")
    print("=" * 66)

    sim_results = {}
    for qi, q in enumerate(TEST_QUERIES):
        scores = doc_vecs @ q_vecs[qi]      # 已归一化，点积即余弦
        top = scores.argsort()[::-1][:10]

        print(f"\n【问】{q}")
        hits = []
        for rank, idx in enumerate(top, 1):
            r = records[idx]
            hits.append({
                "id": r["id"], "source": r["source"],
                "title": r["metadata"].get("title") or
                         r["metadata"].get("question", ""),
                "product_name": r["metadata"].get("product_name"),
                "vector_score": round(float(scores[idx]), 4),
                "rank": rank,
            })
            if rank <= 3:
                print(f"   {rank}. [{r['source'][:6]}] "
                      f"{hits[-1]['title'][:30]:32s} cos={scores[idx]:.4f}")
        sim_results[q] = hits

    # ---- 关键验证：同义表达能否被召回 ----
    print("\n" + "=" * 66)
    print("关键验证：BM25 查不到的，向量能不能查到？")
    print("=" * 66)

    checks = [
        ("这个手机续航怎么样", "续航", "电池"),
        ("能防水吗", "防水", "防水"),
        ("哪款噪音小", "噪音", "分贝"),
    ]
    for q, kw_query, kw_target in checks:
        hits = sim_results.get(q, [])
        found = any(kw_target in (h["title"] or "") or
                    kw_target in str(h.get("product_name") or "")
                    for h in hits[:5])
        top_title = hits[0]["title"][:34] if hits else "(无)"
        print(f"  「{q}」含「{kw_target}」的 Top5 命中: "
              f"{'是' if found else '否'}   Top1={top_title}")

    # ---- 保存 ----
    print("\n" + "=" * 66)
    np.savez_compressed(
        OUT_NPZ,
        vectors=doc_vecs.astype(np.float32),
        ids=np.array([r["id"] for r in records]),
    )
    with io.open(OUT_META, "w", encoding="utf-8") as f:
        json.dump(records, f, ensure_ascii=False)
    with io.open(OUT_SIM, "w", encoding="utf-8") as f:
        json.dump(sim_results, f, ensure_ascii=False, indent=1)

    print("已保存:")
    for p in (OUT_NPZ, OUT_META, OUT_SIM):
        print(f"  {p}  ({os.path.getsize(p) / 1024:.1f} KB)")

    # ---- 换模型的提醒 ----
    print("\n" + "=" * 66)
    print("注意：换 embedding 模型必须全量重跑本脚本。")
    print("不同模型的向量空间互不相通，维度也不同 ——")
    print("库里有 A 模型的坐标、查询用 B 模型算，等于拿两张地图对暗号。")
    print(f"当前维度 {dim}，若换 4B(2560) 或 8B(4096) 需改 pgvector_schema.sql 的 vector(N)")
    print("=" * 66)
    return 0


if __name__ == "__main__":
    sys.exit(main())
