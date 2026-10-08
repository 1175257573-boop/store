package com.ecommerce.service.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 意图识别器准确率测试。
 *
 * <p>与 Python 版 {@code verify_intent_recognition.py} 用同一套测试集，
 * 保证 Java 重写后行为一致 —— 那边 56 条主测试集 + 22 条过拟合检验集全过。
 *
 * <p><b>两个测试集缺一不可</b>：主测试集是我照着正则写的，全过只能证明
 * 「覆盖了我自己写的样例」；过拟合检验集用没见过的表达方式，
 * 是唯一能发现"假的 100%"的手段（Python 版第一版真实水平只有 70%）。
 */
class IntentRecognizerTest {

    /** 主测试集：正常 / 口语 / 选项 / 歧义 / 兜底 */
    private static final Map<String, ChatIntent> CASES = new LinkedHashMap<>();

    static {
        // 明确关键词
        CASES.put("这个多少钱", ChatIntent.PRICE);
        CASES.put("价格是多少", ChatIntent.PRICE);
        CASES.put("太贵了，能便宜点吗", ChatIntent.PRICE);
        CASES.put("有货吗", ChatIntent.STOCK);
        CASES.put("什么时候发货", ChatIntent.STOCK);
        CASES.put("发什么快递", ChatIntent.STOCK);
        CASES.put("包邮吗", ChatIntent.STOCK);
        CASES.put("支持自提吗", ChatIntent.STOCK);
        CASES.put("续航怎么样", ChatIntent.SPEC);
        CASES.put("电池多少毫安", ChatIntent.SPEC);
        CASES.put("是防水的吗", ChatIntent.SPEC);
        CASES.put("什么材质", ChatIntent.SPEC);
        CASES.put("支持七天无理由退货吗", ChatIntent.AFTER_SALE);
        CASES.put("怎么申请退款", ChatIntent.AFTER_SALE);
        CASES.put("坏了怎么办，能保修吗", ChatIntent.AFTER_SALE);
        CASES.put("退货运费谁出", ChatIntent.AFTER_SALE);
        CASES.put("可以开发票吗", ChatIntent.PAYMENT);
        CASES.put("支持什么支付方式", ChatIntent.PAYMENT);
        CASES.put("有什么优惠活动吗", ChatIntent.PROMOTION);
        CASES.put("优惠券在哪领", ChatIntent.PROMOTION);

        // 口语化
        CASES.put("能用多久", ChatIntent.SPEC);
        CASES.put("电耐用吗", ChatIntent.SPEC);
        CASES.put("声音大不大", ChatIntent.SPEC);
        CASES.put("吵不吵", ChatIntent.SPEC);
        CASES.put("防水不", ChatIntent.SPEC);
        CASES.put("啥时候到货", ChatIntent.STOCK);
        CASES.put("几天能到", ChatIntent.STOCK);
        CASES.put("我想要黑色的有吗", ChatIntent.SPEC);
        CASES.put("有没有赠品", ChatIntent.PROMOTION);
        CASES.put("能便宜多少", ChatIntent.PRICE);

        // 选项编号
        CASES.put("1", ChatIntent.PRICE);
        CASES.put("2", ChatIntent.STOCK);
        CASES.put("3", ChatIntent.SPEC);
        CASES.put("4", ChatIntent.COMPARE);
        CASES.put("5", ChatIntent.AFTER_SALE);
        CASES.put("6", ChatIntent.PAYMENT);
        CASES.put("A", ChatIntent.PRICE);
        CASES.put("c", ChatIntent.SPEC);
        CASES.put("②", ChatIntent.STOCK);
        CASES.put("2、我想看什么时候发货", ChatIntent.STOCK);
        CASES.put("1多少钱", ChatIntent.PRICE);
        CASES.put("3. 参数", ChatIntent.SPEC);
        CASES.put("⑤", ChatIntent.AFTER_SALE);

        // 歧义与冲突
        CASES.put("怎么购买", ChatIntent.HOWTO);
        CASES.put("怎么下单", ChatIntent.HOWTO);
        CASES.put("哪个比较好", ChatIntent.COMPARE);
        CASES.put("推荐一个", ChatIntent.COMPARE);
        CASES.put("哪个便宜点", ChatIntent.COMPARE);
        CASES.put("转人工", ChatIntent.HUMAN);
        CASES.put("我要找客服", ChatIntent.HUMAN);
        CASES.put("再见", ChatIntent.HUMAN);
        CASES.put("在吗", ChatIntent.GREETING);
        CASES.put("你好", ChatIntent.GREETING);

        // 兜底
        CASES.put("嗯嗯", ChatIntent.UNKNOWN);
        CASES.put("那个", ChatIntent.UNKNOWN);
        CASES.put("哈哈哈哈", ChatIntent.UNKNOWN);
    }

    /** 过拟合检验集：规则 —— 每一条都不能出现在 CASES 里 */
    private static final Map<String, ChatIntent> NEW_CASES = new LinkedHashMap<>();

    static {
        NEW_CASES.put("这玩意儿卖几个钱", ChatIntent.PRICE);
        NEW_CASES.put("太贵了能少点不", ChatIntent.PRICE);
        NEW_CASES.put("现货吗现在", ChatIntent.STOCK);
        NEW_CASES.put("啥时候能寄出", ChatIntent.STOCK);
        NEW_CASES.put("能撑多长时间", ChatIntent.SPEC);
        NEW_CASES.put("这玩意儿沉不沉", ChatIntent.SPEC);
        NEW_CASES.put("漏不漏水", ChatIntent.SPEC);
        NEW_CASES.put("大不大声音", ChatIntent.SPEC);
        NEW_CASES.put("我要退货", ChatIntent.AFTER_SALE);
        NEW_CASES.put("发票能开吗", ChatIntent.PAYMENT);
        NEW_CASES.put("有啥子优惠没", ChatIntent.PROMOTION);
        NEW_CASES.put("哪个更划算", ChatIntent.COMPARE);
        NEW_CASES.put("人工", ChatIntent.HUMAN);
        NEW_CASES.put("你谁", ChatIntent.UNKNOWN);
        NEW_CASES.put("额", ChatIntent.UNKNOWN);
        NEW_CASES.put(" 3 ", ChatIntent.SPEC);
        NEW_CASES.put("4、", ChatIntent.COMPARE);
        NEW_CASES.put("6. 发票", ChatIntent.PAYMENT);
        NEW_CASES.put("我按错了，2", ChatIntent.STOCK);
        NEW_CASES.put("3 想问价格", ChatIntent.PRICE);
        // 数字 + 量词：曾被误判为选项编号
        NEW_CASES.put("能给我优惠到 5 折吗", ChatIntent.PROMOTION);
        NEW_CASES.put("7 天无理由", ChatIntent.AFTER_SALE);
    }

    @Test
    @DisplayName("主测试集：意图识别准确率")
    void testMainCases() {
        int pass = 0;
        StringBuilder fail = new StringBuilder();
        for (Map.Entry<String, ChatIntent> e : CASES.entrySet()) {
            ChatRecognition r = IntentRecognizer.recognize(e.getKey(), null);
            if (r.intent() == e.getValue()) {
                pass++;
            } else {
                fail.append("\n  「").append(e.getKey()).append("」期望 ")
                        .append(e.getValue()).append("，实得 ").append(r.intent());
            }
        }
        System.out.printf("主测试集准确率: %d/%d = %.1f%%%n",
                pass, CASES.size(), pass * 100.0 / CASES.size());
        assertTrue(pass * 100.0 / CASES.size() >= 95.0,
                "主测试集准确率不足：" + pass + "/" + CASES.size() + fail);
    }

    @Test
    @DisplayName("过拟合检验集：泛化准确率")
    void testOverfitCases() {
        int pass = 0;
        StringBuilder fail = new StringBuilder();
        for (Map.Entry<String, ChatIntent> e : NEW_CASES.entrySet()) {
            ChatRecognition r = IntentRecognizer.recognize(e.getKey(), null);
            if (r.intent() == e.getValue()) {
                pass++;
            } else {
                fail.append("\n  「").append(e.getKey()).append("」期望 ")
                        .append(e.getValue()).append("，实得 ").append(r.intent());
            }
        }
        System.out.printf("泛化准确率: %d/%d = %.1f%%%n",
                pass, NEW_CASES.size(), pass * 100.0 / NEW_CASES.size());
        assertTrue(pass * 100.0 / NEW_CASES.size() >= 90.0,
                "泛化准确率不足 90%：" + pass + "/" + NEW_CASES.size() + fail);
    }

    @Test
    @DisplayName("数字+量词不得被误判为选项编号")
    void testMeasureWordsNotMenu() {
        // 「5 折」曾被认成选项 5（退换货），答案完全错方向
        assertEquals(ChatIntent.PROMOTION,
                IntentRecognizer.recognize("能给我优惠到 5 折吗", null).intent());
        assertEquals(ChatIntent.AFTER_SALE,
                IntentRecognizer.recognize("7 天无理由", null).intent());
    }

    @Test
    @DisplayName("BM25 查询白名单过滤：杜绝注入")
    void testBm25QuerySanitize() {
        // 单引号等注入字符必须被丢弃
        String q = IntentRecognizer.buildBm25Query("手机'; DROP TABLE t_user; --");
        assertTrue(q == null || !q.contains("'") && !q.contains(";"),
                "BM25 查询未过滤注入字符：" + q);
    }

    @Test
    @DisplayName("BM25 查询：短查询不剥离停用词")
    void testBm25ShortQuery() {
        // 「多少钱」剥掉「多少」后就空了 —— 短查询必须用整句
        assertEquals("多少钱", IntentRecognizer.buildBm25Query("多少钱"));
        assertEquals("有货吗", IntentRecognizer.buildBm25Query("有货吗"));
    }

    @Test
    @DisplayName("BM25 查询：长查询多词 OR 连接")
    void testBm25LongQuery() {
        String q = IntentRecognizer.buildBm25Query("支持七天无理由退货吗");
        assertTrue(q.contains(" OR "), "多词应用 OR 连接：" + q);
        assertTrue(!q.contains("吗"), "应剥离语气词：" + q);
    }
}