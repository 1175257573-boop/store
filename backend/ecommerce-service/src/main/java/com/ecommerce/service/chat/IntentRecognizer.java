package com.ecommerce.service.chat;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 正则意图识别器。
 *
 * <p><b>为什么用正则而不是模型</b>：意图识别是每轮对话都要做的高频操作。
 * 正则 &lt;1ms、零成本、命中哪个词一目了然（可审计）、且不需要标注数据（冷启动友好）。
 * 小模型 15ms、LLM 1-3s，用 LLM 做这个是用大炮打蚊子。
 *
 * <p><b>开场给选项</b>是本方案的必要条件而非 UI 装饰：自由输入的表述千变万化，
 * 而选项是受控的 —— 「1」「2」「3」正则能 100% 稳定命中，
 * 等于把用户"训练"到可控表达上。
 *
 * <p>本类的常量与规则均在 Python 版（{@code scripts/customer_service.py}）
 * 经 56 条主测试集 + 22 条过拟合检验集验证后移植，注释里的坑请勿轻易删除。
 */
@Slf4j
public class IntentRecognizer {

    // ==================== 选项编号 ====================

    /** 菜单项：序号 → 意图。选项用数字不用字母 —— 手机打字母要切输入法。 */
    private static final Map<String, ChatIntent> MENU_MAP = new LinkedHashMap<>();

    /**
     * 量词表：数字后面跟这些字是「数量」不是「选项编号」。
     * 没有它，「能给我优惠到 5 折吗」里的 5 会被认成选项 5（退换货），答案完全错方向。
     */
    private static final String MEASURE_WORDS = "折天件个台支只张个人月年时分秒米厘克斤套盒瓶包份次";

    /**
     * 选项编号在<b>开头</b>：{@code 2}、{@code  3. 参数}、{@code ②}。
     *
     * <p>关键：数字与量词之间允许有空格（「 5 折」），断言必须写成
     * {@code (?!\\s*[量词])} 而非 {@code (?![量词])}，
     * 后者只吃到空格会直接放行 —— 这个 bug 让实现多花了两轮才修对。
     */
    private static final Pattern MENU_PREFIX = Pattern.compile(
            "^\\s*([1-6a-fＡＢＣＤＥＦ①-⑥])"
            + "(?!\\s*[" + MEASURE_WORDS + "])"
            + "(?:\\s*[、.．,，:：]?\\s*)(.*)$",
            Pattern.CASE_INSENSITIVE);

    /**
     * 选项编号<b>不在开头</b>：{@code 我按错了，2}、{@code 那个2}。
     * 要求编号是独立 token（前后是空白/标点/结束），否则会从数字里乱认。
     */
    private static final Pattern MENU_LOOSE = Pattern.compile(
            "(?:^|[\\s，,。.、：:；;！!？?])([1-6a-fＡＢＣＤＥＦ①-⑥])"
            + "(?!\\s*[" + MEASURE_WORDS + "])"
            + "(?=$|[\\s，,。.、：:；;！!？?])",
            Pattern.CASE_INSENSITIVE);

    /**
     * BM25 查询前的停用词剥离。
     *
     * <p>为什么必须剥离：MySQL ngram 全文索引在 BOOLEAN MODE 下默认是 AND，
     * 查询里每个 2-gram 都必须命中。「支持七天无理由退货吗」中
     * 「货吗」这个 gram 在 500 个知识块里出现 0 次，整条查询直接被否掉。
     */
    private static final Set<String> STOPWORDS = Set.of(
            "吗", "呢", "吧", "啊", "呀", "哦", "的", "了", "是", "我", "你",
            "他", "她", "它", "这", "那", "有", "在", "会", "能", "要", "想",
            "请问", "请", "一下", "怎么样", "如何", "什么", "为什么", "多少");

    /** 控制词：转人工/结束 —— 必须最高优先级。 */
    private static final Pattern HUMAN_WORDS = Pattern.compile(
            "人工|转人工|客服|真人|人工服务|退出|结束|拜拜|再见");

    /** 问候语：整句只有问候，不含其他意图。 */
    private static final Pattern GREETING_WORDS = Pattern.compile(
            "^(在吗|您好|你好|hi|hello|哈喽|在不在|有人吗)[!！。~？?]*$",
            Pattern.CASE_INSENSITIVE);

    // ==================== 各意图的同义词表 ====================

    /**
     * 意图 → 关键词正则。
     *
     * <p>key 的顺序即 {@link ChatIntent} 声明顺序，命中多个时按声明顺序取。
     */
    private static final Map<ChatIntent, Pattern> KEYWORDS = new LinkedHashMap<>();

    static {
        MENU_MAP.put("1", ChatIntent.PRICE);
        MENU_MAP.put("2", ChatIntent.STOCK);
        MENU_MAP.put("3", ChatIntent.SPEC);
        MENU_MAP.put("4", ChatIntent.COMPARE);
        MENU_MAP.put("5", ChatIntent.AFTER_SALE);
        MENU_MAP.put("6", ChatIntent.PAYMENT);
        MENU_MAP.put("a", ChatIntent.PRICE);
        MENU_MAP.put("b", ChatIntent.STOCK);
        MENU_MAP.put("c", ChatIntent.SPEC);
        MENU_MAP.put("d", ChatIntent.COMPARE);
        MENU_MAP.put("e", ChatIntent.AFTER_SALE);
        MENU_MAP.put("f", ChatIntent.PAYMENT);
        for (int i = 1; i <= 6; i++) {
            MENU_MAP.put(String.valueOf("①②③④⑤⑥".charAt(i - 1)), ChatIntent.values()[i - 1]);
        }

        // COMPARE 必须在前：「哪个便宜点」是对比问题，不是单件价格问题
        KEYWORDS.put(ChatIntent.COMPARE, Pattern.compile(
                "哪个好|哪个比较好|哪个好点|比较(?!较好)|推荐|求推荐|建议(?!价)|选哪|怎么选"
                        + "|划算|值得买|买哪个|选哪个|区别(?!退)|对比|比一比"
                        + "|哪个(更|划算|合适|便宜|好)|哪个便宜"));
        // 「便宜」用负向断言排除「哪个便宜」，那个归 COMPARE
        KEYWORDS.put(ChatIntent.PRICE, Pattern.compile(
                "多少钱|价格|价钱|报价|贵不贵|(?<!哪个)便宜|少点|优惠点|打个折"
                        + "|折扣|最低价|几块|多少米|售价|price"
                        + "|卖(几|多)个?钱|几个钱|卖多少|几个米|值多少"));
        KEYWORDS.put(ChatIntent.STOCK, Pattern.compile(
                "有货|现货|库存|还有货|断货|缺货|发货|什么时候发|多久发|能发|发什么快递"
                        + "|物流|快递|包邮|运费|自提|配送"
                        + "|几天(能|可以)?到|几天(能|可以)?发|什么时候到货"
                        + "|到货|啥时候到|啥时候发|何时到|寄出|什么时候寄|啥时候寄|几天寄"));
        KEYWORDS.put(ChatIntent.SPEC, Pattern.compile(
                "参数|配置|规格|型号|尺寸|多大|多少毫安|电池|续航|能用多久|能用多长"
                        + "|耐用吗|耐用性|耗电|费电|掉电|撑(多)?长|用多久|能用(多)?久|顶得住"
                        + "|功率|能耗|防水|材质|材料|重量|屏幕|颜色|什么色"
                        + "|黑色|白色|银色|灰色|蓝色|绿色|红色|粉色|米色"
                        + "|适合|人群|送人|送礼"
                        + "|噪音|噪声|声音(大|小)?|吵|安静|静音|响不响|吵不吵"
                        + "|接口|什么接口|配什么|罩杯|尺码|多大码|几码"
                        + "|漏不漏|沉不沉|轻不轻|烫不烫|硬不硬|粘不粘|掉不掉|裂不裂|划不划|伤不伤"));
        // 注意：这里刻意<b>没有</b>「[不吗]\s*$」这类「疑问句尾」兜底规则。
        // 它看着能兜住「漏不漏」「沉不沉」这类口语，实际过宽 ——
        // 任何以「吗」结尾的句子都会命中 SPEC：
        //   「能给我优惠到 5 折吗」→ SPEC 抢在 PROMOTION 前，答案完全错方向。
        // 口语疑问句应该<b>穷举具体词</b>（漏不漏/沉不沉/吵不吵…），
        // 而不是匹配句尾标点。宁可漏判，不可错判。
        KEYWORDS.put(ChatIntent.AFTER_SALE, Pattern.compile(
                "退货|退款|退钱|换货|换新|售后|保修|维修|坏了|质量(?!量足)|故障"
                        + "|七天|无理由|运费谁出|怎么退|申请退|能退|可以退|包退"));
        KEYWORDS.put(ChatIntent.PAYMENT, Pattern.compile(
                "支付|付款|怎么付|发票|开票|增值税|取消订单|订单取消|能不能取消"));
        KEYWORDS.put(ChatIntent.PROMOTION, Pattern.compile(
                "优惠|活动|打折|满减|优惠券|领券|券|促销|特价|秒杀"
                        + "|赠品|送(?!货)|附赠|加送"));
        KEYWORDS.put(ChatIntent.HOWTO, Pattern.compile(
                "怎么买|怎么购买|如何购买|下单|加入购物车|怎么下单|购买流程"));
    }

    // ==================== 对外方法 ====================

    /**
     * 识别用户意图。
     *
     * @param input       用户输入
     * @param lastIntent  上一轮意图，用于极短输入的上下文兜底
     */
    public static ChatRecognition recognize(String input, ChatIntent lastIntent) {
        if (input == null || input.isBlank()) {
            return new ChatRecognition(ChatIntent.UNKNOWN, 0.0, "", "none");
        }
        String text = input.strip();
        String low = text.toLowerCase();

        // ① 转人工 / 结束 —— 最高优先级
        if (HUMAN_WORDS.matcher(text).find()) {
            return new ChatRecognition(ChatIntent.HUMAN, 1.0, "人工", "keyword");
        }

        // ② 问候语
        if (GREETING_WORDS.matcher(low.strip()).matches()) {
            return new ChatRecognition(ChatIntent.GREETING, 1.0, "问候", "keyword");
        }

        // ③ 选项编号在开头
        Matcher m = MENU_PREFIX.matcher(text);
        if (m.matches()) {
            String num = m.group(1);
            String rest = m.group(2) == null ? "" : m.group(2);
            ChatIntent byNum = MENU_MAP.get(normalizeNum(num));
            if (byNum != null) {
                // 编号后面还跟着话（「2 我想看什么时候发货」）：
                // 若能识别出更具体的意图且与选项不冲突，用它 —— 更精确
                if (!rest.isBlank()) {
                    ChatRecognition sub = matchKeywords(rest);
                    if (sub.intent() != ChatIntent.UNKNOWN
                            && sub.intent() != byNum) {
                        return new ChatRecognition(sub.intent(), sub.confidence(),
                                num + " + " + sub.matched(), "menu+keyword");
                    }
                }
                return new ChatRecognition(byNum, 1.0, num, "menu");
            }
        }

        // ④ 选项编号不在开头
        Matcher loose = MENU_LOOSE.matcher(text);
        if (loose.find()) {
            ChatIntent byNum = MENU_MAP.get(normalizeNum(loose.group(1)));
            if (byNum != null) {
                return new ChatRecognition(byNum, 0.9, loose.group(1), "menu_loose");
            }
        }

        // ⑤ 关键词正则
        ChatRecognition r = matchKeywords(text);
        if (r.intent() != ChatIntent.UNKNOWN) {
            return r;
        }

        // ⑥ 上下文兜底：极短输入（「嗯」「那个」）沿用上一轮意图。
        // 正则处理不了「那它呢」这类依赖上下文的短句，靠会话状态补。
        if (lastIntent != null && lastIntent != ChatIntent.UNKNOWN && text.length() <= 4) {
            return new ChatRecognition(lastIntent, 0.5, "上下文延续", "context");
        }

        return new ChatRecognition(ChatIntent.UNKNOWN, 0.0, "", "none");
    }

    /**
     * 关键词匹配。命中多个意图时：命中次数多的优先，次数相同按枚举声明顺序。
     */
    private static ChatRecognition matchKeywords(String text) {
        record Hit(ChatIntent intent, String keyword, int count, int order) {
        }
        List<Hit> hits = new ArrayList<>();
        int order = 0;
        for (Map.Entry<ChatIntent, Pattern> e : KEYWORDS.entrySet()) {
            Matcher mm = e.getValue().matcher(text);
            int count = 0;
            String first = null;
            while (mm.find()) {
                if (count == 0 && mm.group().length() > 0) {
                    first = mm.group();
                }
                count++;
            }
            if (count > 0) {
                hits.add(new Hit(e.getKey(), first == null ? "" : first, count, order));
            }
            order++;
        }
        if (hits.isEmpty()) {
            return new ChatRecognition(ChatIntent.UNKNOWN, 0.0, "", "none");
        }
        Hit best = hits.stream()
                .min(Comparator.comparingInt((Hit h) -> -h.count()).thenComparingInt(Hit::order))
                .orElseThrow();
        // 命中多个关键词置信度更高，上限 0.95（选项才是 1.0）
        double conf = Math.min(0.6 + 0.15 * best.count(), 0.95);
        return new ChatRecognition(best.intent(), conf, best.keyword(), "keyword");
    }

    /**
     * 编号归一化：数字、字母、带圈数字、全角字母都要能认。
     * 带圈数字（①②③）在 UTF-8 里是单码点，可直接 index。
     */
    private static String normalizeNum(String ch) {
        if ("①②③④⑤⑥".contains(ch)) {
            return String.valueOf("①②③④⑤⑥".indexOf(ch) + 1);
        }
        String s = ch.toLowerCase();
        // 全角字母 → 半角
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 'ａ' && c <= 'ｆ') {
                sb.append((char) (c - 'ａ' + 'a'));
            } else if (c >= 'Ａ' && c <= 'Ｆ') {
                sb.append((char) (c - 'Ａ' + 'a'));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 生成 BM25 查询表达式。
     *
     * <p>两步：
     * <ol>
     *   <li><b>白名单过滤</b>，而非转义 —— 白名单从入口杜绝 SQL 注入。
     *       靠转义治标不治本：只要模板少一个引号，或有人改成 NATURAL MODE，就又炸了。</li>
     *   <li><b>停用词剥离 + 2-4 字滑窗</b>，切出的 gram 用 OR 连接
     *       （BOOLEAN MODE 下空格也是 AND，多词 OR 才是「任一命中即可」）。</li>
     * </ol>
     *
     * <p>短查询（≤4 字）直接用整句 —— 「多少钱」「有货吗」剥掉停用词后就空了，
     * 这里的「多少」「有」不是噪音，是检索意图本身。
     */
    public static String buildBm25Query(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        // 白名单：汉字、字母、数字、空格
        StringBuilder sb = new StringBuilder();
        for (char c : input.toCharArray()) {
            if (Character.isLetterOrDigit(c) || Character.isWhitespace(c)) {
                sb.append(c);
            }
        }
        String cleaned = sb.toString().strip();
        if (cleaned.isEmpty()) {
            return null;
        }
        if (cleaned.length() <= 4) {
            return cleaned;
        }

        String working = cleaned;
        for (String sw : STOPWORDS) {
            working = working.replace(sw, " ");
        }

        List<String> tokens = new ArrayList<>();
        for (String seg : working.split("[^\\p{IsHan}a-zA-Z0-9]+")) {
            if (seg.isBlank()) {
                continue;
            }
            boolean ascii = true;
            for (char c : seg.toCharArray()) {
                if (c > 127) {
                    ascii = false;
                    break;
                }
            }
            if (ascii) {
                tokens.add(seg);
            } else {
                // 中文按 2-4 字滑窗切（ngram 索引单位就是 2-gram）
                int n = seg.length();
                for (int size = 4; size >= 2; size--) {
                    for (int i = 0; i + size <= n; i++) {
                        tokens.add(seg.substring(i, i + size));
                    }
                }
            }
        }
        if (tokens.isEmpty()) {
            return cleaned;
        }
        return String.join(" OR ", tokens.stream().limit(12).toList());
    }
}