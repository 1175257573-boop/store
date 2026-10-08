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
    /**
     * 从用户输入里认出具体商品名。
     *
     * <p>两级匹配，都要求<b>语义相关</b>而不是「碰巧含这个字」：
     * <ol>
     *   <li>输入里含完整商品名 —— 直接命中</li>
     *   <li>输入里同时含品牌与品类词（如「曜石」「手机」）—— 组合命中</li>
     * </ol>
     *
     * <p><b>为什么不能只按品牌匹配</b>：某品牌下有几十个商品，
     * 只按品牌查会选中该品牌下任意一个 —— 实测踩过，
     * 用户问「曜石手机」却锁定了该品牌的《中国国家地理》。
     * 商品上下文锁错比不锁更糟：后续所有回答都会答错对象。
     */
    private String extractProduct(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        // ⚠️ 纯选项编号直接跳过商品识别。
        // 「2」会经 LIKE '%2%' 匹配到商品名里含 2 的商品
        // （实测：「2」选中了「智利车厘子 JJ级 2斤装」）——
        // 用户只是想选菜单，没在指任何商品。
        if (MENU_ONLY.matcher(message.strip()).matches()) {
            return null;
        }
        // ① 输入里直接含某个完整商品名
        List<KbProductChunkMapper.ProductNameVO> exact =
                chunkMapper.findProductsByName(message, 1);
        if (exact != null && !exact.isEmpty()) {
            return exact.get(0).getName();
        }
        // ② 品牌 + 品类词组合匹配
        String brand = guessBrand(message);
        if (brand != null) {
            String category = guessCategory(message);
            if (category != null) {
                List<KbProductChunkMapper.ProductNameVO> both =
                        chunkMapper.findProductByBrandAndCategory(brand, category, 1);
                if (both != null && !both.isEmpty()) {
                    return both.get(0).getName();
                }
            }
            // 只给品牌没给品类时<b>不锁定</b> —— 同品牌商品太多，
            // 猜错的代价（后续全答错）远大于不锁的代价（答得泛一点）
        }
        return null;
    }

    /** 品牌 = 商品名的第一个词。 */
    private String guessBrand(String message) {
        for (String b : BRANDS) {
            if (message.contains(b)) {
                return b;
            }
        }
        return null;
    }

    /** 品类词 —— 用户会用「曜石手机」指代「曜石 5G 智能手机」。 */
    private String guessCategory(String message) {
        for (String c : CATEGORY_HINTS) {
            if (message.contains(c)) {
                return c;
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

    /**
     * 纯选项编号（1-6 / A-F / ①-⑥），不是商品指代。
     * 「2」「③」「c. 参数」这类输入要跳过商品识别。
     */
    private static final java.util.regex.Pattern MENU_ONLY =
            java.util.regex.Pattern.compile(
                    "^[1-6a-fＡ-Ｆ①-⑥]\\s*[、.．,，:：]?\\s*.{0,12}$");

    /** 品牌 = 商品名第一个词，抽成常量避免每次请求都查全表。 */
    private static final Set<String> BRANDS = Set.of(
            "澜图", "曜石", "星野", "云栖", "极光", "维度", "矩阵", "锐界",
            "方糖", "拓维", "清野", "风驰", "暖冬", "沁园", "沐歌", "步履",
            "轻羽", "远行", "素白", "行者", "鲜集", "山野", "禾谷", "溪涧",
            "南亩", "知页", "墨香", "文津", "拾光", "观澜", "木言", "织梦",
            "安寝", "素居", "暖屋", "匠心", "得力", "简美", "办公通", "优格");

    /** 品类词：用户用简称指代商品（「曜石手机」→ 曜石 5G 智能手机）。 */
    private static final List<String> CATEGORY_HINTS = List.of(
            "手机", "笔记本", "电脑", "显示器", "投影仪", "耳机", "手表", "充电宝",
            "路由器", "空调", "冰箱", "洗衣机", "吸尘器", "投影", "破壁机",
            "加湿器", "热水器", "电风扇", "净化器", "扫地机器人",
            "跑鞋", "运动鞋", "高跟鞋", "衬衫", "牛仔裤", "羽绒服", "连衣裙",
            "针织衫", "双肩包", "单肩包", "棒球帽", "运动袜",
            "四件套", "枕头", "蚕丝被", "羽绒被", "窗帘", "地垫", "餐具", "炒锅",
            "收纳箱", "蜡烛",
            "图书", "书", "合订本",
            "键盘", "鼠标", "台灯", "计算器", "文件夹", "订书机", "收纳", "文具", "笔记本本");

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