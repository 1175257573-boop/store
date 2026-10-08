package com.ecommerce.service.chat;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ecommerce.dao.entity.KbFaq;
import com.ecommerce.dao.entity.KbProductChunk;
import com.ecommerce.dao.entity.Product;
import com.ecommerce.dao.mapper.KbFaqMapper;
import com.ecommerce.dao.mapper.KbProductChunkMapper;
import com.ecommerce.dao.mapper.ProductAttrMapper;
import com.ecommerce.dao.mapper.ProductMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 智能客服服务。
 *
 * <p>处理链路：
 * <pre>
 * 用户输入
 *   ├─ 意图识别（正则，&lt;1ms）
 *   ├─ 按意图分流
 *   │    ├─ 商品类 → BM25 检索 → 商品过滤 → Top3
 *   │    ├─ 店铺类 → 通用 FAQ
 *   │    └─ 控制类 → 固定话术
 *   └─ 组织回答 + 追问引导
 * </pre>
 *
 * <p><b>三条硬规则见 {@link ChatScripts}</b>，这里是最容易违反的地方。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final KbProductChunkMapper chunkMapper;
    private final KbFaqMapper faqMapper;
    private final ProductAttrMapper attrMapper;
    private final ProductMapper productMapper;

    /**
     * 会话状态。
     *
     * <p>生产环境应放 Redis（多实例时内存状态会不一致）。
     * 当前用内存 Map 且带 TTL 清理，单实例演示够用。
     */
    private final Map<String, SessionEntry> sessions = new ConcurrentHashMap<>();

    private static final long SESSION_TTL_MS = 30 * 60 * 1000L;
    private static final int MAX_SESSIONS = 5000;

    private record SessionEntry(ChatSession session, long lastAccess) {
    }

    // ==================== 会话 ====================

    /**
     * 开场白。
     *
     * <p>返回结果里必须带服务端生成的 sessionId：
     * 首次调用时前端没有 id，若不回传，后续请求就拿不到上下文，
     * 商品锁定与连续追问全部失效。
     */
    public ChatGreeting greeting(String sessionId, String productName) {
        String sid = normalizeSessionId(sessionId);
        ChatSession s = getOrCreate(sid);
        if (productName != null && !productName.isBlank()) {
            s.setProductName(productName);
        }
        save(sid, s);
        return new ChatGreeting(sid, ChatScripts.greeting(s.getProductName()));
    }

    /**
     * 开场结果：sessionId + 开场白。
     */
    public record ChatGreeting(String sessionId, String reply) {
    }

    /**
     * 处理一轮对话。
     */
    public ChatReply reply(String sessionId, String message) {
        long start = System.currentTimeMillis();
        String sid = normalizeSessionId(sessionId);
        ChatSession session = getOrCreate(sid);

        ChatRecognition rec = IntentRecognizer.recognize(message, session.getLastIntent());

        // 记录本轮意图，供下一轮上下文兜底
        if (rec.intent() != ChatIntent.UNKNOWN && rec.intent() != ChatIntent.GREETING) {
            session.setLastIntent(rec.intent());
        }

        String replyText;
        List<String> citations = new ArrayList<>();

        switch (rec.intent()) {
            case HUMAN -> replyText = ChatScripts.TRANSFER;
            case GREETING -> replyText = ChatScripts.followupMenu();
            case UNKNOWN -> replyText = ChatScripts.UNKNOWN_PREFIX + "\n\n"
                    + ChatScripts.followupMenu();
            case AFTER_SALE, PAYMENT, PROMOTION, HOWTO -> {
                replyText = answerShopQuestion(message, rec.intent(), citations);
            }
            default -> {
                // 商品类
                String answer = answerProductQuestion(message, session, rec, citations);
                replyText = answer + "\n\n" + ChatScripts.followupMenu();
            }
        }

        save(sid, session);
        return new ChatReply(
                sid,
                replyText,
                rec.intent().getCode(),
                rec.intent().getLabel(),
                rec.confidence(),
                rec.matched(),
                rec.source(),
                List.of("1", "2", "3", "4", "0"),
                citations,
                System.currentTimeMillis() - start);
    }

    // ==================== 商品类问题 ====================

    private String answerProductQuestion(String message, ChatSession session,
                                         ChatRecognition rec, List<String> citations) {
        // ① 商品上下文锁定：用户明确说过商品后，后续追问都限定它。
        //    「曜石手机有货吗」→「续航怎么样」是典型连续追问，
        //    第二句没有商品名，靠语义检索会混进同品类其他商品。
        String entity = extractProduct(message);
        if (entity != null) {
            session.setProductName(entity);
        }
        String product = session.getProductName();

        // ② 只回了选项、还没说商品 → 澄清，不硬答。
        //    「我确认不了」和「你还没告诉我哪款」对用户完全不同。
        if (rec.source().startsWith("menu") && product == null) {
            return ChatScripts.clarify(rec.intent());
        }

        // ③ BM25 检索
        List<KbProductChunk> chunks = searchChunks(message);

        // ④ 严格按商品过滤 —— 只改标题不改内容是「标题说曜石、内容讲极光」，
        //    用户会当成曜石的参数。命中不到就如实说，不拿别的商品充数。
        if (product != null) {
            List<KbProductChunk> filtered = filterByProduct(chunks, product);
            if (filtered.isEmpty()) {
                // 检索没命中时，退而按商品名精确查一次
                // （融合排序可能把目标商品挤出了 top-N）
                filtered = filterByProduct(
                        chunkMapper.listByProduct(findProductId(product)), product);
            }
            if (filtered.isEmpty()) {
                return ChatScripts.productNoRecord(product);
            }
            chunks = filtered;
        }

        if (chunks.isEmpty()) {
            return ChatScripts.NO_ANSWER;
        }

        // ⑤ 组织回答
        StringBuilder sb = new StringBuilder();
        if (product != null) {
            sb.append("关于「").append(product).append("」：\n");
        }
        int count = 0;
        for (KbProductChunk c : chunks) {
            if (count >= 3) {
                break;
            }
            if (c.getContent() == null || c.getContent().isBlank()) {
                continue;
            }
            sb.append("【").append(ChatScripts.chunkTypeLabel(c.getChunkType()))
                    .append("】").append(c.getContent()).append("\n\n");
            citations.add("chunk_" + c.getId());
            count++;
        }
        return count == 0 ? ChatScripts.NO_ANSWER : sb.toString().strip();
    }

    // ==================== 店铺类问题 ====================

    /**
     * 店铺类问题走通用 FAQ。
     *
     * <p>这类问题（退换货、发票、物流）与具体商品无关，
     * 绑到某个商品上反而会答错 —— 用户问的是店铺政策。
     */
    private String answerShopQuestion(String message, ChatIntent intent,
                                      List<String> citations) {
        List<KbFaq> shopFaqs = faqMapper.listShopFaq();
        if (shopFaqs.isEmpty()) {
            return ChatScripts.NO_ANSWER;
        }

        // ① 意图完全匹配 —— 最可靠，直接答
        for (KbFaq f : shopFaqs) {
            if (f.getIntent() != null && f.getIntent().equals(intent.getCode())) {
                citations.add("faq_" + f.getId());
                faqMapper.incrHitCount(f.getId());
                return ChatScripts.faqAnswer(f.getQuestion(), f.getAnswer());
            }
        }

        // ② BM25 模糊匹配
        String bm25 = IntentRecognizer.buildBm25Query(message);
        if (bm25 != null) {
            List<KbFaq> hits = faqMapper.searchByBm25(bm25, 3);
            for (KbFaq f : hits) {
                if (f.getProductId() == null) {
                    citations.add("faq_" + f.getId());
                    faqMapper.incrHitCount(f.getId());
                    return ChatScripts.faqAnswer(f.getQuestion(), f.getAnswer());
                }
            }
        }

        // ③ 答不出来就说答不出来
        return ChatScripts.NO_ANSWER;
    }

    // ==================== 检索与工具 ====================

    private List<KbProductChunk> searchChunks(String message) {
        String bm25 = IntentRecognizer.buildBm25Query(message);
        if (bm25 == null) {
            return List.of();
        }
        try {
            return chunkMapper.searchByBm25(bm25, 10);
        } catch (Exception e) {
            log.warn("BM25 检索失败，转降级: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 只保留真正属于该商品的知识块。
     *
     * <p>知识库生成时块正文以商品名开头，所以按前缀匹配最准。
     * 用 product_id 匹配不够 —— BM25 混着返回 FAQ 与知识块，
     * 只有知识块才有 product_id。
     */
    private List<KbProductChunk> filterByProduct(List<KbProductChunk> chunks,
                                                 String productName) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        List<KbProductChunk> out = new ArrayList<>();
        for (KbProductChunk c : chunks) {
            String content = c.getContent() == null ? "" : c.getContent();
            String title = c.getTitle() == null ? "" : c.getTitle();
            if (content.startsWith(productName) || title.contains(productName)) {
                out.add(c);
            }
        }
        return out;
    }

    /**
     * 从用户输入里认出具体商品名。
     *
     * <p>用 LIKE 而非精确匹配：用户只会说「曜石手机」，
     * 完整名是「曜石 5G 智能手机 Pro 12GB+256GB」。
     * 取<b>最长</b>匹配 —— 更长的名字更具体，才是用户要问的那款。
     */
    private String extractProduct(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        // 先试完整商品名（按长度倒序，取最长匹配）
        List<KbProductChunkMapper.ProductNameVO> exact =
                chunkMapper.findProductsByName(message, 1);
        if (exact != null && !exact.isEmpty()) {
            return exact.get(0).getName();
        }
        // 再试品牌名 + 品类词的组合，如「曜石手机」
        String brand = guessBrand(message);
        if (brand != null) {
            List<KbProductChunkMapper.ProductNameVO> byBrand =
                    chunkMapper.findProductsByName(brand, 1);
            if (byBrand != null && !byBrand.isEmpty()) {
                return byBrand.get(0).getName();
            }
        }
        return null;
    }

    private String guessBrand(String message) {
        // 品牌是商品名第一个词。从知识库里反查有哪些品牌
        List<KbProductChunkMapper.ProductNameVO> all =
                chunkMapper.findProductsByName("", 0);
        if (all == null) {
            return null;
        }
        Set<String> brands = new java.util.HashSet<>();
        for (KbProductChunkMapper.ProductNameVO v : all) {
            String name = v.getName();
            int sp = name.indexOf(' ');
            if (sp > 0) {
                brands.add(name.substring(0, sp));
            }
        }
        for (String b : brands) {
            if (message.contains(b)) {
                return b;
            }
        }
        return null;
    }

    private Long findProductId(String productName) {
        LambdaQueryWrapper<Product> w = new LambdaQueryWrapper<Product>()
                .eq(Product::getName, productName)
                .last("LIMIT 1");
        Product p = productMapper.selectOne(w);
        return p == null ? null : p.getId();
    }

    // ==================== 会话存储 ====================

    private String normalizeSessionId(String sessionId) {
        return (sessionId == null || sessionId.isBlank())
                ? UUID.randomUUID().toString().replace("-", "")
                : sessionId;
    }

    private ChatSession getOrCreate(String sid) {
        SessionEntry e = sessions.get(sid);
        long now = System.currentTimeMillis();
        if (e != null && now - e.lastAccess() < SESSION_TTL_MS) {
            return e.session();
        }
        return new ChatSession(sid);
    }

    private void save(String sid, ChatSession session) {
        // 顺手清理过期会话，避免内存泄漏
        if (sessions.size() > MAX_SESSIONS) {
            long now = System.currentTimeMillis();
            sessions.entrySet().removeIf(en ->
                    now - en.getValue().lastAccess() > SESSION_TTL_MS);
        }
        sessions.put(sid, new SessionEntry(session, System.currentTimeMillis()));
    }
}