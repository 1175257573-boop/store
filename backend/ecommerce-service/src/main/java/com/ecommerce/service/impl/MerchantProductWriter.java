package com.ecommerce.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ecommerce.common.constant.BizConst;
import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.common.result.ResultCode;
import com.ecommerce.dao.entity.Product;
import com.ecommerce.dao.entity.ProductSku;
import com.ecommerce.dao.entity.ProductSpec;
import com.ecommerce.dao.entity.ProductSpecValue;
import com.ecommerce.dao.mapper.ProductMapper;
import com.ecommerce.dao.mapper.ProductSkuMapper;
import com.ecommerce.dao.mapper.ProductSpecMapper;
import com.ecommerce.dao.mapper.ProductSpecValueMapper;
import com.ecommerce.service.dto.MerchantProductDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 商品与 SKU 的事务写入边界。
 *
 * <p>抽独立 Bean 是必须的：{@code @Transactional} 基于 AOP 代理，
 * 同类内部调用不生效。这里要保证「商品 + 规格 + SKU」要么全成功要么全回滚，
 * 不能出现「有商品没有 SKU」的半成品数据。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MerchantProductWriter {

    private final ProductMapper productMapper;
    private final ProductSkuMapper skuMapper;
    private final ProductSpecMapper specMapper;
    private final ProductSpecValueMapper specValueMapper;

    /**
     * 创建商品及其 SKU。
     *
     * @param merchantId 所属商家
     * @return 商品 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public Long create(MerchantProductDTO dto, Long merchantId) {
        // ---- 1. 商品主表 ----
        Product product = new Product();
        applyDto(product, dto);
        product.setMerchantId(merchantId);
        // 新建商品一律进审核队列
        product.setAuditStatus(0);
        product.setStatus(0);
        product.setSales(0);
        product.setViewCount(0);
        product.setCreateTime(LocalDateTime.now());
        product.setUpdateTime(LocalDateTime.now());
        productMapper.insert(product);

        // ---- 2. 规格与规格值 ----
        if (dto.getSpecs() != null && !dto.getSpecs().isEmpty()) {
            saveSpecs(product.getId(), dto.getSpecs());
        }

        // ---- 3. SKU ----
        int totalStock = saveSkus(product.getId(), merchantId, dto.getSkus());
        // 商品表的总库存取所有 SKU 之和，保证两个口径一致
        product.setStock(totalStock);
        productMapper.updateById(product);

        log.info("商家发布商品: product={}, merchant={}, skus={}, stock={}",
                product.getId(), merchantId, dto.getSkus().size(), totalStock);
        return product.getId();
    }

    /**
     * 更新商品。
     * <p>先删旧 SKU 再重建：规格组合变了以后没法一一对应，
     * 全量替换比做差异比对更简单也更不容易出错。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void update(Long productId, MerchantProductDTO dto, Long merchantId) {
        Product exist = requireOwnProduct(productId, merchantId);

        applyDto(exist, dto);
        // 编辑已上架商品要重置为待审核，防止「先审核通过再偷改内容」
        exist.setAuditStatus(0);
        exist.setStatus(0);
        exist.setUpdateTime(LocalDateTime.now());

        // 清掉旧规格与 SKU（复用 deleteChildren，避免两处逻辑不一致）
        deleteChildren(productId);

        if (dto.getSpecs() != null && !dto.getSpecs().isEmpty()) {
            saveSpecs(productId, dto.getSpecs());
        }
        int totalStock = saveSkus(productId, merchantId, dto.getSkus());
        exist.setStock(totalStock);
        productMapper.updateById(exist);

        log.info("商家更新商品: product={}, merchant={}", productId, merchantId);
    }

    /**
     * 删除商品（连带 SKU、规格、明细）。
     * <p>物理删除而非逻辑删除：商家商品下架后不该再出现在任何列表里。
     * 已有订单的商品走「下架」而非「删除」，否则历史订单会查不到商品信息。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long productId, Long merchantId) {
        Product exist = requireOwnProduct(productId, merchantId);
        // 有销量的商品不允许删除，只能下架：删了历史订单就断链了
        if (exist.getSales() != null && exist.getSales() > 0) {
            throw new BusinessException("该商品已有销量，不能删除，请改为下架");
        }
        deleteChildren(productId);
        productMapper.deleteById(productId);
        log.info("商家删除商品: product={}, merchant={}", productId, merchantId);
    }

    /** 删除商品的附属数据（供删除与编辑复用） */
    private void deleteChildren(Long productId) {
        List<Long> specIds = specMapper.selectList(
                        new LambdaQueryWrapper<ProductSpec>()
                                .select(ProductSpec::getId)
                                .eq(ProductSpec::getProductId, productId))
                .stream().map(ProductSpec::getId).toList();
        if (!specIds.isEmpty()) {
            specValueMapper.delete(new LambdaQueryWrapper<ProductSpecValue>()
                    .in(ProductSpecValue::getSpecId, specIds));
        }
        specMapper.delete(new LambdaQueryWrapper<ProductSpec>()
                .eq(ProductSpec::getProductId, productId));
        skuMapper.delete(new LambdaQueryWrapper<ProductSku>()
                .eq(ProductSku::getProductId, productId));
    }

    /** 保存规格组与规格值 */
    private void saveSpecs(Long productId, List<MerchantProductDTO.SpecGroup> specs) {
        int sort = 0;
        for (MerchantProductDTO.SpecGroup group : specs) {
            ProductSpec spec = new ProductSpec();
            spec.setProductId(productId);
            spec.setName(group.getName());
            spec.setSortOrder(sort++);
            specMapper.insert(spec);

            int vSort = 0;
            for (String value : group.getValues()) {
                ProductSpecValue sv = new ProductSpecValue();
                sv.setSpecId(spec.getId());
                sv.setValue(value);
                sv.setSortOrder(vSort++);
                specValueMapper.insert(sv);
            }
        }
    }

    /** 保存 SKU，返回库存合计 */
    private int saveSkus(Long productId, Long merchantId,
                         List<MerchantProductDTO.SkuItem> items) {
        int total = 0;
        int sort = 0;
        for (MerchantProductDTO.SkuItem item : items) {
            ProductSku sku = new ProductSku();
            sku.setProductId(productId);
            sku.setMerchantId(merchantId);
            // 单规格商品统一用"默认"，避免前端要处理空字符串
            sku.setSpecText(item.getSpecText() == null || item.getSpecText().isBlank()
                    ? "默认" : item.getSpecText());
            sku.setPrice(item.getPrice());
            sku.setStock(item.getStock());
            sku.setSales(0);
            sku.setSkuCode(item.getSkuCode());
            sku.setStatus(item.getStatus() == null ? 1 : item.getStatus());
            sku.setSortOrder(sort++);
            sku.setCreateTime(LocalDateTime.now());
            sku.setUpdateTime(LocalDateTime.now());
            skuMapper.insert(sku);
            total += item.getStock() == null ? 0 : item.getStock();
        }
        return total;
    }

    /**
     * 校验商品归属。
     * <p>用 {@code selectByIdAndMerchant} 而不是 {@code selectById}——
     * 后者不区分归属，会被用来改别人的商品。</p>
     */
    private Product requireOwnProduct(Long productId, Long merchantId) {
        Product product = productMapper.selectByIdAndMerchant(productId, merchantId);
        if (product == null) {
            throw new BusinessException(ResultCode.PRODUCT_NOT_BELONG_TO_MERCHANT);
        }
        return product;
    }

    /** DTO 字段搬到实体 */
    private void applyDto(Product product, MerchantProductDTO dto) {
        product.setCategoryId(dto.getCategoryId());
        product.setName(dto.getName());
        product.setSubtitle(dto.getSubtitle());
        product.setDescription(dto.getDescription());
        product.setMainImage(dto.getMainImage());
        product.setPrice(dto.getPrice());
        product.setOriginPrice(dto.getOriginPrice());
        if (dto.getStock() != null) {
            product.setStock(dto.getStock());
        }
    }
}
