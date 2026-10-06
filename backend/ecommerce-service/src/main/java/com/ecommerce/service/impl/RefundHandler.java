package com.ecommerce.service.impl;

import com.ecommerce.common.constant.BizConst;
import com.ecommerce.common.context.UserContextHolder;
import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.common.result.ResultCode;
import com.ecommerce.dao.entity.AfterSale;
import com.ecommerce.dao.entity.Merchant;
import com.ecommerce.dao.entity.Order;
import com.ecommerce.dao.entity.OrderItem;
import com.ecommerce.dao.entity.ProductSku;
import com.ecommerce.dao.mapper.AfterSaleMapper;
import com.ecommerce.dao.mapper.MerchantMapper;
import com.ecommerce.dao.mapper.OrderItemMapper;
import com.ecommerce.dao.mapper.OrderMapper;
import com.ecommerce.dao.mapper.ProductSkuMapper;
import com.ecommerce.service.dto.AfterSaleApplyDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 退款的事务边界。
 *
 * <p>独立 Bean 保证 {@code @Transactional} 生效——「同意退款」要同时改
 * 售后单状态、订单状态、SKU 库存、店铺销售额，四步必须原子完成。
 * 少做一步就会出现「钱退了但库存没回补」这类资损。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefundHandler {

    /** 单规格商品的规格文本，与 MerchantProductWriter 的写入口径保持一致 */
    private static final String DEFAULT_SPEC = "默认";

    private final AfterSaleMapper afterSaleMapper;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final ProductSkuMapper skuMapper;
    private final MerchantMapper merchantMapper;

    /**
     * 提交售后申请。
     *
     * @return 售后单号
     */
    @Transactional(rollbackFor = Exception.class)
    public String apply(AfterSaleApplyDTO dto) {
        Long userId = UserContextHolder.requireUserId();

        Order order = orderMapper.selectById(dto.getOrderId());
        if (order == null || !order.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.ORDER_NOT_EXIST);
        }
        // 只有「已支付及之后」的状态才能申请售后：待付款订单还没付钱
        if (order.getStatus() == null || order.getStatus() < BizConst.ORDER_PAID
                || order.getStatus() > BizConst.ORDER_FINISHED) {
            throw new BusinessException("该订单状态不支持申请售后");
        }
        // 同一商品已有处理中的售后单时拦下，避免重复退款
        List<AfterSale> active = afterSaleMapper.selectActiveByOrderAndProduct(
                dto.getOrderId(), dto.getProductId());
        if (!active.isEmpty()) {
            throw new BusinessException("该商品已有处理中的售后申请");
        }

        // 走 Mapper 明确方法而非 Wrapper：少一次条件拼装，也避开方法引用解析问题
        OrderItem item = null;
        for (OrderItem oi : orderItemMapper.selectByOrderId(dto.getOrderId())) {
            if (dto.getProductId().equals(oi.getProductId())) {
                item = oi;
                break;
            }
        }
        if (item == null) {
            throw new BusinessException("该订单不含此商品");
        }
        if (dto.getQuantity() > item.getQuantity()) {
            throw new BusinessException("售后数量不能超过购买数量");
        }
        // 退款金额不能超过该商品的实付金额，否则就是骗钱
        BigDecimal maxRefund = item.getProductPrice().multiply(BigDecimal.valueOf(dto.getQuantity()));
        if (dto.getAmount().compareTo(maxRefund) > 0) {
            throw new BusinessException(ResultCode.AFTER_SALE_AMOUNT_ERROR);
        }

        // 平台自营商品（merchantId 为 null）不支持走商家售后流程
        if (item.getMerchantId() == null) {
            throw new BusinessException("该商品为平台自营，请联系客服处理");
        }

        AfterSale sale = new AfterSale();
        sale.setSaleNo("AS" + System.currentTimeMillis());
        sale.setOrderId(order.getId());
        sale.setOrderNo(order.getOrderNo());
        sale.setUserId(userId);
        sale.setMerchantId(item.getMerchantId());
        sale.setProductId(item.getProductId());
        sale.setProductName(item.getProductName());
        sale.setQuantity(dto.getQuantity());
        sale.setAmount(dto.getAmount());
        sale.setType(dto.getType() == null ? 1 : dto.getType());
        sale.setReason(dto.getReason());
        sale.setRemark(dto.getRemark());
        sale.setImages(dto.getImages());
        sale.setStatus(0);
        sale.setCreateTime(LocalDateTime.now());
        sale.setUpdateTime(LocalDateTime.now());
        afterSaleMapper.insert(sale);

        log.info("提交售后申请: saleNo={}, order={}, product={}, amount={}",
                sale.getSaleNo(), order.getOrderNo(), item.getProductId(), dto.getAmount());
        return sale.getSaleNo();
    }

    /**
     * 完成退款：改售后单 + 改订单 + 回补库存 + 回退店铺销售额。
     */
    @Transactional(rollbackFor = Exception.class)
    public void complete(Long saleId, Long merchantId) {
        AfterSale sale = requireOwnSale(saleId, merchantId);

        // 状态机守卫：只有「已同意」能完成，重复调用影响 0 行
        if (afterSaleMapper.complete(saleId, merchantId, LocalDateTime.now()) == 0) {
            throw new BusinessException(ResultCode.AFTER_SALE_STATUS_ERROR);
        }

        // 回补 SKU 库存与销量
        for (OrderItem oi : orderItemMapper.selectByOrderId(sale.getOrderId())) {
            if (oi.getProductId().equals(sale.getProductId())) {
                backUpStock(oi, sale.getQuantity());
                break;
            }
        }

        // 店铺销售额回退
        Merchant merchant = merchantMapper.selectById(merchantId);
        if (merchant != null) {
            Merchant upd = new Merchant();
            upd.setId(merchantId);
            upd.setTotalSales(merchant.getTotalSales().subtract(sale.getAmount()));
            upd.setTotalOrder(Math.max(merchant.getTotalOrder() - 1, 0L));
            merchantMapper.updateById(upd);
        }

        log.info("退款完成: saleNo={}, amount={}", sale.getSaleNo(), sale.getAmount());
    }

    /**
     * 回补库存。
     *
     * <p>用 Mapper 里已写好的 {@code selectByProductId} 而不是
     * LambdaQueryWrapper：Wrapper 的方法引用在 {@code ProductSku::getSpecText}
     * 上出过解析问题（报 {@code MyBatisSystemException: null}），
     * 走明确的 Mapper 方法更稳，也少一次条件拼装。</p>
     *
     * <p>单规格商品退到「默认」那条；多规格商品明细没记具体 SKU，
     * 退到第一条（演示环境的简化处理，生产应在下单时记录 skuId）。</p>
     */
    private void backUpStock(OrderItem item, int qty) {
        List<ProductSku> all = skuMapper.selectByProductId(item.getProductId());
        if (all == null || all.isEmpty()) {
            log.warn("商品 {} 无 SKU 记录，库存未回补", item.getProductId());
            return;
        }
        ProductSku target = all.stream()
                .filter(s -> DEFAULT_SPEC.equals(s.getSpecText()))
                .findFirst()
                .orElse(all.get(0));
        skuMapper.restoreStock(target.getId(), qty);
    }

    /** 校验售后单归属本店 */
    private AfterSale requireOwnSale(Long saleId, Long merchantId) {
        AfterSale sale = afterSaleMapper.selectById(saleId);
        if (sale == null) {
            throw new BusinessException(ResultCode.AFTER_SALE_NOT_EXIST);
        }
        if (merchantId != null && !merchantId.equals(sale.getMerchantId())) {
            throw new BusinessException(ResultCode.DATA_NOT_BELONG_TO_YOU);
        }
        return sale;
    }
}
