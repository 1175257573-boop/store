package com.ecommerce.service;

import com.ecommerce.dao.entity.AfterSale;
import com.ecommerce.dao.entity.Order;
import com.ecommerce.dao.entity.OrderItem;
import com.ecommerce.service.dto.AfterSaleApplyDTO;

import java.util.List;
import java.util.Map;

/**
 * 商家订单与售后接口。
 */
public interface MerchantOrderService {

    /**
     * 商家订单列表（只返回本店的）。
     *
     * @param status 订单状态，null 为全部
     */
    List<Order> listMyOrders(Integer status, String keyword);

    /** 订单详情（含明细），非本店订单会被拒绝 */
    Order getOrderDetail(Long orderId);

    /**
     * 发货。
     *
     * @param shipCompany 快递公司
     * @param shipNo      快递单号
     */
    void ship(Long orderId, String shipCompany, String shipNo);

    /** 各状态订单数（商家端角标） */
    Map<String, Integer> countMyOrders();

    /**
     * 申请售后（用户侧）。
     *
     * @return 售后单号
     */
    String applyAfterSale(AfterSaleApplyDTO dto);

    /** 我的售后申请（用户侧） */
    List<AfterSale> listMyAfterSales(Integer status);

    /** 撤销售后申请（用户侧，仅待处理可撤） */
    void revokeAfterSale(Long saleId);

    /** 商家端售后列表 */
    List<AfterSale> listMerchantAfterSales(Integer status);

    /** 商家同意售后 */
    void approveAfterSale(Long saleId, String remark);

    /** 商家拒绝售后 */
    void rejectAfterSale(Long saleId, String remark);

    /**
     * 完成退款。
     * <p>真正打款成功后调用，同时回补商品库存与销量、店铺销售额。</p>
     */
    void completeRefund(Long saleId);

    /** 售后详情（非本店会被拒绝） */
    AfterSale getAfterSale(Long saleId);

    /** 商家售后统计（各状态数量） */
    Map<String, Integer> countMyAfterSales();
}
