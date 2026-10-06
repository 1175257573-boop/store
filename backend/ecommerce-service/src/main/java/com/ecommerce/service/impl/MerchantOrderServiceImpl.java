package com.ecommerce.service.impl;

import com.ecommerce.common.constant.BizConst;
import com.ecommerce.common.context.UserContextHolder;
import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.common.result.ResultCode;
import com.ecommerce.dao.entity.AfterSale;
import com.ecommerce.dao.entity.Order;
import com.ecommerce.dao.entity.OrderItem;
import com.ecommerce.dao.mapper.AfterSaleMapper;
import com.ecommerce.dao.mapper.MerchantMapper;
import com.ecommerce.dao.mapper.OrderItemMapper;
import com.ecommerce.dao.mapper.OrderMapper;
import com.ecommerce.service.MerchantOrderService;
import com.ecommerce.service.dto.AfterSaleApplyDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 商家订单与售后实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MerchantOrderServiceImpl implements MerchantOrderService {

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final AfterSaleMapper afterSaleMapper;
    private final MerchantMapper merchantMapper;
    private final RefundHandler refundHandler;

    // ==================== 订单 ====================

    @Override
    public List<Order> listMyOrders(Integer status, String keyword) {
        Long merchantId = UserContextHolder.requireMerchantId();
        return orderMapper.selectMerchantOrders(merchantId, status, keyword);
    }

    @Override
    public Order getOrderDetail(Long orderId) {
        Long merchantId = UserContextHolder.requireMerchantId();
        return requireOwnOrder(orderId, merchantId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void ship(Long orderId, String shipCompany, String shipNo) {
        Long merchantId = UserContextHolder.requireMerchantId();
        if (shipCompany == null || shipCompany.isBlank()) {
            throw new BusinessException("请选择快递公司");
        }
        if (shipNo == null || shipNo.isBlank()) {
            throw new BusinessException("请填写快递单号");
        }

        // SQL 里同时带 merchant_id 和 status=1 两个条件：
        // 越权发货与重复发货都会被影响 0 行拦下
        int rows = orderMapper.shipOrder(orderId, merchantId,
                shipCompany, shipNo, LocalDateTime.now());
        if (rows == 0) {
            // 区分「不是我的订单」和「状态不对」，给用户准确提示
            Order exists = orderMapper.selectById(orderId);
            if (exists == null) {
                throw new BusinessException(ResultCode.ORDER_NOT_EXIST);
            }
            if (!merchantId.equals(exists.getMerchantId())) {
                throw new BusinessException(ResultCode.DATA_NOT_BELONG_TO_YOU);
            }
            throw new BusinessException("只有已付款的订单才能发货");
        }
        log.info("商家发货: order={}, company={}, no={}", orderId, shipCompany, shipNo);
    }

    @Override
    public Map<String, Integer> countMyOrders() {
        Long merchantId = UserContextHolder.requireMerchantId();
        Map<String, Integer> result = new HashMap<>();
        for (int s = 0; s <= BizConst.ORDER_CANCELLED; s++) {
            result.put("status" + s, 0);
        }
        for (Map<String, Object> row : orderMapper.countMerchantOrdersByStatus(merchantId)) {
            Object status = row.get("status");
            Object cnt = row.get("cnt");
            result.put("status" + status, cnt == null ? 0 : Integer.parseInt(String.valueOf(cnt)));
        }
        return result;
    }

    // ==================== 售后 ====================

    @Override
    public String applyAfterSale(AfterSaleApplyDTO dto) {
        return refundHandler.apply(dto);
    }

    @Override
    public List<AfterSale> listMyAfterSales(Integer status) {
        Long userId = UserContextHolder.requireUserId();
        return afterSaleMapper.selectByCondition(null, userId, status);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void revokeAfterSale(Long saleId) {
        Long userId = UserContextHolder.requireUserId();
        if (afterSaleMapper.revoke(saleId, userId) == 0) {
            throw new BusinessException("售后单不存在或已处理，无法撤销");
        }
    }

    @Override
    public List<AfterSale> listMerchantAfterSales(Integer status) {
        Long merchantId = UserContextHolder.requireMerchantId();
        return afterSaleMapper.selectByCondition(merchantId, null, status);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void approveAfterSale(Long saleId, String remark) {
        Long merchantId = UserContextHolder.requireMerchantId();
        if (afterSaleMapper.approve(saleId, merchantId, remark, LocalDateTime.now()) == 0) {
            throw new BusinessException("售后单不存在、不属于本店或已处理");
        }
        log.info("商家同意售后: sale={}, remark={}", saleId, remark);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rejectAfterSale(Long saleId, String remark) {
        Long merchantId = UserContextHolder.requireMerchantId();
        if (remark == null || remark.isBlank()) {
            throw new BusinessException("拒绝售后必须填写原因");
        }
        if (afterSaleMapper.reject(saleId, merchantId, remark, LocalDateTime.now()) == 0) {
            throw new BusinessException("售后单不存在、不属于本店或已处理");
        }
        log.info("商家拒绝售后: sale={}, remark={}", saleId, remark);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void completeRefund(Long saleId) {
        Long merchantId = UserContextHolder.requireMerchantId();
        refundHandler.complete(saleId, merchantId);
    }

    @Override
    public AfterSale getAfterSale(Long saleId) {
        Long userId = UserContextHolder.requireUserId();
        AfterSale sale = afterSaleMapper.selectById(saleId);
        if (sale == null) {
            throw new BusinessException(ResultCode.AFTER_SALE_NOT_EXIST);
        }
        // 用户只能看自己的售后单；商家和管理员可以看
        var current = UserContextHolder.get();
        boolean canView = current != null && (current.isAdmin() || current.isMerchant()
                || sale.getUserId().equals(userId));
        if (!canView) {
            throw new BusinessException(ResultCode.DATA_NOT_BELONG_TO_YOU);
        }
        return sale;
    }

    @Override
    public Map<String, Integer> countMyAfterSales() {
        Long merchantId = UserContextHolder.requireMerchantId();
        Map<String, Integer> result = new HashMap<>();
        for (int s = 0; s <= 4; s++) {
            result.put("status" + s, 0);
        }
        for (Map<String, Object> row : afterSaleMapper.countByStatus(merchantId)) {
            Object status = row.get("status");
            Object cnt = row.get("cnt");
            result.put("status" + status, cnt == null ? 0 : Integer.parseInt(String.valueOf(cnt)));
        }
        return result;
    }

    // ==================== 内部工具 ====================

    /**
     * 校验订单归属本店。
     * <p>用 {@code selectByIdAndMerchant} 而不是 {@code selectById}——
     * 后者不区分归属，会被用来看别人的订单（含收货地址、手机号）。</p>
     */
    private Order requireOwnOrder(Long orderId, Long merchantId) {
        Order order = orderMapper.selectByIdAndMerchant(orderId, merchantId);
        if (order == null) {
            throw new BusinessException(ResultCode.DATA_NOT_BELONG_TO_YOU);
        }
        return order;
    }
}
