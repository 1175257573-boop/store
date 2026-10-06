package com.ecommerce.service.impl;

import com.ecommerce.common.constant.BizConst;
import com.ecommerce.common.context.UserContextHolder;
import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.common.result.ResultCode;
import com.ecommerce.dao.entity.Product;
import com.ecommerce.dao.entity.ProductSku;
import com.ecommerce.dao.entity.ProductSpec;
import com.ecommerce.dao.entity.ProductSpecValue;
import com.ecommerce.dao.mapper.MerchantMapper;
import com.ecommerce.dao.mapper.ProductMapper;
import com.ecommerce.dao.mapper.ProductSkuMapper;
import com.ecommerce.dao.mapper.ProductSpecMapper;
import com.ecommerce.dao.mapper.ProductSpecValueMapper;
import com.ecommerce.service.MerchantProductService;
import com.ecommerce.service.dto.MerchantProductDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 商家商品管理实现。
 *
 * <p><b>数据隔离的核心</b>：所有涉及「按 ID 操作商品」的方法，
 * 都先用 {@code selectByIdAndMerchant(productId, merchantId)} 校验归属，
 * 查不到就报「不属于本店」。绝不能用 {@code selectById}——
 * 它不区分归属，会被用来改别人的商品。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MerchantProductServiceImpl implements MerchantProductService {

    private final ProductMapper productMapper;
    private final ProductSkuMapper skuMapper;
    private final ProductSpecMapper specMapper;
    private final ProductSpecValueMapper specValueMapper;
    private final MerchantMapper merchantMapper;
    private final MerchantProductWriter writer;

    @Override
    public Long create(MerchantProductDTO dto) {
        Long merchantId = requireOperableShop();
        Long productId = writer.create(dto, merchantId);
        merchantMapper.refreshProductCount(merchantId);
        return productId;
    }

    @Override
    public void update(Long productId, MerchantProductDTO dto) {
        Long merchantId = requireOperableShop();
        writer.update(productId, dto, merchantId);
        merchantMapper.refreshProductCount(merchantId);
    }

    @Override
    public void delete(Long productId) {
        Long merchantId = requireOperableShop();
        writer.delete(productId, merchantId);
        merchantMapper.refreshProductCount(merchantId);
    }

    @Override
    public void changeStatus(Long productId, Integer status) {
        Long merchantId = requireOperableShop();
        Product product = requireOwnProduct(productId, merchantId);

        if (status != null && status == BizConst.PRODUCT_ON_SHELF) {
            // 未通过审核不许上架：否则商家可以先上架再排队等审核，绕过审核
            if (product.getAuditStatus() == null || product.getAuditStatus() != 1) {
                throw new BusinessException(ResultCode.PRODUCT_AUDIT_PENDING);
            }
        }
        product.setStatus(status);
        product.setUpdateTime(LocalDateTime.now());
        productMapper.updateById(product);

        // 商品状态与 SKU 状态联动，避免「商品上架但 SKU 全禁用」导致买不了
        if (status != null && status == BizConst.PRODUCT_ON_SHELF) {
            skuMapper.enableByProduct(productId);
        } else {
            skuMapper.disableByProduct(productId);
        }
    }

    @Override
    public List<Product> listMyProducts(Integer status, Integer auditStatus,
                                        Long categoryId, String keyword) {
        Long merchantId = UserContextHolder.requireMerchantId();
        return productMapper.selectByMerchantCondition(
                merchantId, categoryId, status, auditStatus, keyword);
    }

    @Override
    public Map<String, Object> getDetail(Long productId) {
        Long merchantId = UserContextHolder.requireMerchantId();
        Product product = requireOwnProduct(productId, merchantId);

        Map<String, Object> result = new HashMap<>();
        result.put("product", product);
        result.put("skus", skuMapper.selectByProductId(productId));

        // 规格组 + 规格值组装成前端要的嵌套结构
        List<ProductSpec> specs = specMapper.selectByProductId(productId);
        List<ProductSpecValue> values = specValueMapper.selectByProductId(productId);
        for (ProductSpec spec : specs) {
            spec.setValues(values.stream()
                    .filter(v -> spec.getId().equals(v.getSpecId()))
                    .toList());
        }
        result.put("specs", specs);
        return result;
    }

    @Override
    public List<ProductSku> listSkus(Long productId) {
        Long merchantId = UserContextHolder.requireMerchantId();
        requireOwnProduct(productId, merchantId);
        return skuMapper.selectByProductId(productId);
    }

    @Override
    public void updateSku(Long productId, Long skuId, BigDecimal price,
                          Integer stock, String skuCode, Integer status) {
        Long merchantId = requireOperableShop();
        // 先校验商品归属，防止改别人店铺的 SKU
        requireOwnProduct(productId, merchantId);

        int rows = skuMapper.updateSku(skuId, merchantId, price, stock, skuCode, status);
        if (rows == 0) {
            // SKU 不存在或不属于本店，措辞统一为「不属于本店」避免泄露信息
            throw new BusinessException(ResultCode.PRODUCT_NOT_BELONG_TO_MERCHANT);
        }
        // 商品总库存 = 所有 SKU 库存之和，两个口径必须一致
        refreshProductStock(productId);
    }

    @Override
    public void audit(Long productId, boolean pass, String reason) {
        UserContextHolder.requireAdmin();

        Product product = productMapper.selectById(productId);
        if (product == null) {
            throw new BusinessException("商品不存在");
        }
        if (product.getAuditStatus() != null && product.getAuditStatus() != 0) {
            throw new BusinessException("该商品已审核，不能重复审核");
        }

        if (pass) {
            // 审核通过：只改为「已通过」，上架由商家自己点
            // 这样商家能在审核后继续调整价格再上架，运营也更灵活
            productMapper.auditProduct(productId, 1, null, BizConst.PRODUCT_OFF_SHELF);
            log.info("商品审核通过: product={}, name={}", productId, product.getName());
        } else {
            if (reason == null || reason.isBlank()) {
                throw new BusinessException("拒绝审核必须填写原因");
            }
            productMapper.auditProduct(productId, 2, reason, BizConst.PRODUCT_OFF_SHELF);
            skuMapper.disableByProduct(productId);
            log.info("商品审核拒绝: product={}, reason={}", productId, reason);
        }
    }

    @Override
    public List<Product> listPendingAudits(String keyword) {
        UserContextHolder.requireAdmin();
        return productMapper.selectByMerchantCondition(
                null, null, null, 0, keyword);
    }

    @Override
    public Map<String, Integer> countMyProducts() {
        Long merchantId = UserContextHolder.requireMerchantId();
        Map<String, Integer> result = new HashMap<>();
        result.put("total", productMapper.countByMerchant(merchantId, null));
        result.put("pending", productMapper.countByMerchant(merchantId, 0));
        result.put("approved", productMapper.countByMerchant(merchantId, 1));
        result.put("rejected", productMapper.countByMerchant(merchantId, 2));
        result.put("onShelf", productMapper.countByMerchant(merchantId, null));
        return result;
    }

    /**
     * 校验当前操作的店铺可写。
     * <p>冻结的店铺不能改商品——否则会出现「店铺被冻结但商品还在改」的漏洞，
     * 平台封禁就失去意义了。</p>
     */
    private Long requireOperableShop() {
        Long merchantId = UserContextHolder.requireMerchantId();
        var merchant = merchantMapper.selectById(merchantId);
        if (merchant == null) {
            throw new BusinessException(ResultCode.NOT_MERCHANT_YET);
        }
        if (merchant.getStatus() != null && merchant.getStatus() == 2) {
            throw new BusinessException(ResultCode.SHOP_FROZEN);
        }
        return merchantId;
    }

    /** 校验商品归属本店 */
    private Product requireOwnProduct(Long productId, Long merchantId) {
        Product product = productMapper.selectByIdAndMerchant(productId, merchantId);
        if (product == null) {
            throw new BusinessException(ResultCode.PRODUCT_NOT_BELONG_TO_MERCHANT);
        }
        return product;
    }

    /** 重算商品总库存 */
    private void refreshProductStock(Long productId) {
        int total = skuMapper.selectByProductId(productId).stream()
                .mapToInt(s -> s.getStock() == null ? 0 : s.getStock())
                .sum();
        Product upd = new Product();
        upd.setId(productId);
        upd.setStock(total);
        productMapper.updateById(upd);
    }
}
