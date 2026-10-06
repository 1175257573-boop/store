package com.ecommerce.service.impl;

import com.ecommerce.common.constant.BizConst;
import com.ecommerce.common.context.UserContextHolder;
import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.dao.entity.AfterSale;
import com.ecommerce.dao.entity.Merchant;
import com.ecommerce.dao.mapper.AfterSaleMapper;
import com.ecommerce.dao.mapper.MerchantMapper;
import com.ecommerce.dao.mapper.OrderMapper;
import com.ecommerce.dao.mapper.ProductMapper;
import com.ecommerce.service.MerchantDashboardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 商家数据看板实现。
 *
 * <p><b>数据口径原则</b>：销售额只统计「已支付及之后」的订单。
 * 待付款订单随时可能被取消，算进销售额会让数据虚高，商家据此决策会误判经营状况。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MerchantDashboardServiceImpl implements MerchantDashboardService {

    private final MerchantMapper merchantMapper;
    private final OrderMapper orderMapper;
    private final ProductMapper productMapper;
    private final AfterSaleMapper afterSaleMapper;

    @Override
    public Map<String, Object> getOverview() {
        Long merchantId = UserContextHolder.requireMerchantId();
        Merchant merchant = merchantMapper.selectById(merchantId);
        if (merchant == null) {
            throw new BusinessException("店铺不存在");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("shopName", merchant.getShopName());
        result.put("score", merchant.getScore());
        result.put("shopStatus", Merchant.statusText(merchant.getStatus()));

        // 今日 / 昨日 / 本月 / 累计
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        LocalDateTime yesterdayStart = todayStart.minusDays(1);
        LocalDateTime monthStart = LocalDate.now().withDayOfMonth(1).atStartOfDay();

        result.put("todaySales", sumSales(merchantId, todayStart));
        result.put("todayOrders", countOrders(merchantId, todayStart));
        result.put("yesterdaySales", sumSales(merchantId, yesterdayStart));
        result.put("monthSales", sumSales(merchantId, monthStart));
        result.put("totalSales", merchant.getTotalSales());
        result.put("totalOrders", merchant.getTotalOrder());

        // 商品维度
        result.put("productTotal", productMapper.countByMerchant(merchantId, null));
        result.put("productOnShelf", countOnShelf(merchantId));
        result.put("productPendingAudit", productMapper.countByMerchant(merchantId, 0));

        // 售后维度
        int pendingAfterSale = 0;
        for (Map<String, Object> row : afterSaleMapper.countByStatus(merchantId)) {
            if ("0".equals(String.valueOf(row.get("status")))) {
                Object cnt = row.get("cnt");
                pendingAfterSale = cnt == null ? 0 : Integer.parseInt(String.valueOf(cnt));
                break;
            }
        }
        result.put("pendingAfterSale", pendingAfterSale);

        // 环比：昨日为 0 时不做除法，前端显示「—」
        BigDecimal todaySales = toDecimal(result.get("todaySales"));
        BigDecimal yesterdaySales = toDecimal(result.get("yesterdaySales"));
        if (yesterdaySales.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal growth = todaySales.subtract(yesterdaySales)
                    .divide(yesterdaySales, 4, java.math.RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
            result.put("salesGrowthRate", growth.setScale(1, java.math.RoundingMode.HALF_UP));
        } else {
            result.put("salesGrowthRate", null);
        }
        return result;
    }

    @Override
    public Map<String, Object> getSalesTrend(int days) {
        Long merchantId = UserContextHolder.requireMerchantId();
        // 上限 90 天：再长的趋势图前端已经看不清了
        int safeDays = Math.min(Math.max(days, 1), 90);

        List<Map<String, Object>> raw = orderMapper.dailySalesTrend(merchantId, safeDays);

        // 补齐没有订单的日期：否则折线图会断断续续，看起来像数据丢了
        Map<String, Map<String, Object>> byDate = new HashMap<>();
        for (Map<String, Object> row : raw) {
            byDate.put(String.valueOf(row.get("d")), row);
        }

        List<String> dates = new ArrayList<>();
        List<BigDecimal> amounts = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        for (int i = safeDays - 1; i >= 0; i--) {
            LocalDate d = LocalDate.now().minusDays(i);
            String key = d.toString();
            dates.add(key);
            Map<String, Object> row = byDate.get(key);
            amounts.add(row == null ? BigDecimal.ZERO : toDecimal(row.get("amount")));
            counts.add(row == null ? 0 : Integer.parseInt(String.valueOf(row.get("cnt"))));
        }

        Map<String, Object> result = new HashMap<>();
        result.put("dates", dates);
        result.put("amounts", amounts);
        result.put("counts", counts);
        return result;
    }

    @Override
    public Map<String, Object> getTopProducts(int limit) {
        Long merchantId = UserContextHolder.requireMerchantId();
        int safeLimit = Math.min(Math.max(limit, 1), 20);
        List<Map<String, Object>> list = orderMapper.topProducts(merchantId, safeLimit);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : list) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("productId", row.get("product_id"));
            item.put("productName", row.get("product_name"));
            item.put("quantity", row.get("qty"));
            item.put("amount", toDecimal(row.get("amount")));
            result.add(item);
        }
        Map<String, Object> data = new HashMap<>();
        data.put("list", result);
        return data;
    }

    @Override
    public Map<String, Object> getBadges() {
        Long merchantId = UserContextHolder.requireMerchantId();
        Map<String, Object> result = new HashMap<>();

        Map<String, Integer> orderCounts = new HashMap<>();
        for (int s = 0; s <= BizConst.ORDER_CANCELLED; s++) {
            orderCounts.put("status" + s, 0);
        }
        for (Map<String, Object> row : orderMapper.countMerchantOrdersByStatus(merchantId)) {
            orderCounts.put("status" + row.get("status"),
                    Integer.parseInt(String.valueOf(row.get("cnt"))));
        }
        result.put("orders", orderCounts);
        result.put("pendingShip", orderCounts.get("status" + BizConst.ORDER_PAID));

        Map<String, Integer> saleCounts = new HashMap<>();
        for (int s = 0; s <= 4; s++) {
            saleCounts.put("status" + s, 0);
        }
        for (Map<String, Object> row : afterSaleMapper.countByStatus(merchantId)) {
            saleCounts.put("status" + row.get("status"),
                    Integer.parseInt(String.valueOf(row.get("cnt"))));
        }
        result.put("afterSales", saleCounts);
        result.put("pendingAfterSale", saleCounts.get("status0"));
        return result;
    }

    // ==================== 内部工具 ====================

    private BigDecimal sumSales(Long merchantId, LocalDateTime start) {
        Map<String, Object> row = orderMapper.sumSalesByMerchant(merchantId, start);
        // 按别名取值，不用 values() 的顺序 —— 顺序依赖在换 SQL 时会静默出错
        return row == null ? BigDecimal.ZERO : toDecimal(row.get("amount"));
    }

    private int countOrders(Long merchantId, LocalDateTime start) {
        Map<String, Object> row = orderMapper.sumSalesByMerchant(merchantId, start);
        if (row == null || row.get("cnt") == null) {
            return 0;
        }
        return Integer.parseInt(String.valueOf(row.get("cnt")));
    }

    private int countOnShelf(Long merchantId) {
        List<com.ecommerce.dao.entity.Product> products =
                productMapper.selectByMerchantCondition(
                        merchantId, null, BizConst.PRODUCT_ON_SHELF, null, null);
        return products.size();
    }

    private BigDecimal toDecimal(Object v) {
        if (v == null) {
            return BigDecimal.ZERO;
        }
        if (v instanceof BigDecimal b) {
            return b;
        }
        try {
            return new BigDecimal(String.valueOf(v));
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }
}
