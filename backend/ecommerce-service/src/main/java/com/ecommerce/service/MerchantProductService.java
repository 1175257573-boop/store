package com.ecommerce.service;

import com.ecommerce.dao.entity.Product;
import com.ecommerce.dao.entity.ProductSku;
import com.ecommerce.service.dto.MerchantProductDTO;

import java.util.List;
import java.util.Map;

/**
 * 商家商品管理接口。
 *
 * <p>所有「按 ID 操作商品」的方法内部都做归属校验：
 * 查不到就报「不属于本店」，而不是「不存在」——
 * 后者会让攻击者无法区分「商品不存在」和「别人的商品」，
 * 反而更安全。</p>
 */
public interface MerchantProductService {

    /**
     * 发布商品（待审核状态）。
     * <p>新建商品一律进审核队列，审核通过才上架。</p>
     */
    Long create(MerchantProductDTO dto);

    /**
     * 编辑商品。
     * <p>编辑已上架商品会重置为待审核，防止「先审核后偷改」。</p>
     */
    void update(Long productId, MerchantProductDTO dto);

    /** 删除商品（连带删除其 SKU 与规格） */
    void delete(Long productId);

    /**
     * 上架 / 下架。
     * <p>未通过审核的商品不允许上架。</p>
     */
    void changeStatus(Long productId, Integer status);

    /** 商家端商品列表（只返回自己的） */
    List<Product> listMyProducts(Integer status, Integer auditStatus,
                                 Long categoryId, String keyword);

    /** 商品详情（含 SKU 与规格） */
    Map<String, Object> getDetail(Long productId);

    /** 商品的 SKU 列表 */
    List<ProductSku> listSkus(Long productId);

    /** 更新单个 SKU 的价格与库存 */
    void updateSku(Long productId, Long skuId,
                   java.math.BigDecimal price, Integer stock,
                   String skuCode, Integer status);

    /** 审核商品（平台管理员） */
    void audit(Long productId, boolean pass, String reason);

    /** 待审核商品列表（平台管理员） */
    List<Product> listPendingAudits(String keyword);

    /** 商家商品数统计（各审核状态） */
    Map<String, Integer> countMyProducts();
}
