package com.ecommerce.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ecommerce.common.constant.BizConst;
import com.ecommerce.common.constant.RedisKey;
import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.common.result.ResultCode;
import com.ecommerce.dao.entity.Category;
import com.ecommerce.dao.entity.Merchant;
import com.ecommerce.dao.entity.Product;
import com.ecommerce.dao.mapper.CategoryMapper;
import com.ecommerce.dao.mapper.ProductMapper;
import com.ecommerce.dao.mapper.MerchantMapper;
import com.ecommerce.dao.mapper.ProductSkuMapper;
import com.ecommerce.service.ProductService;
import com.ecommerce.service.util.RedisCacheUtil;
import com.ecommerce.service.vo.ProductDetailVO;
import com.ecommerce.service.vo.ProductVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 商品服务实现。
 * <p>分类列表与商品详情走 Redis 缓存；商品列表因组合条件多、命中率低，
 * 直接查库避免缓存 key 爆炸带来的额外复杂度。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductServiceImpl extends ServiceImpl<ProductMapper, Product> implements ProductService {

    private final CategoryMapper categoryMapper;
    private final ProductSkuMapper skuMapper;
    private final MerchantMapper merchantMapper;
    private final RedisCacheUtil cacheUtil;

    /** 分类缓存 1 天 */
    private static final Duration CATEGORY_TTL = Duration.ofDays(1);
    /** 商品详情缓存 30 分钟 */
    private static final Duration DETAIL_TTL = Duration.ofMinutes(30);

    @Override
    public IPage<ProductVO> pageProducts(int pageNum, int pageSize, Long categoryId,
                                         String keyword, String sortBy,
                                         Long merchantId) {
        // pageSize 上限 100，防止前端传入超大值把库拖垮
        int safeSize = Math.min(Math.max(pageSize, 1), 100);
        int safeNum = Math.max(pageNum, 1);

        LambdaQueryWrapper<Product> wrapper = new LambdaQueryWrapper<Product>()
                .eq(Product::getStatus, BizConst.PRODUCT_ON_SHELF);

        if (categoryId != null) {
            wrapper.eq(Product::getCategoryId, categoryId);
        }
        // 店铺维度筛选：店铺主页与「只看某店商品」都走这里
        if (merchantId != null) {
            wrapper.eq(Product::getMerchantId, merchantId);
        }
        if (keyword != null && !keyword.isBlank()) {
            // 转义 LIKE 通配符，避免用户输入 % 时全表扫描
            String safeKeyword = keyword.replace("%", "").replace("_", "").trim();
            if (!safeKeyword.isEmpty()) {
                wrapper.and(w -> w.like(Product::getName, safeKeyword)
                        .or().like(Product::getSubtitle, safeKeyword));
            }
        }

        switch (sortBy == null ? "default" : sortBy) {
            case "sales" -> wrapper.orderByDesc(Product::getSales);
            case "priceAsc" -> wrapper.orderByAsc(Product::getPrice);
            case "priceDesc" -> wrapper.orderByDesc(Product::getPrice);
            case "newest" -> wrapper.orderByDesc(Product::getCreateTime);
            default -> wrapper.orderByDesc(Product::getSales);
        }

        Page<Product> page = this.page(new Page<>(safeNum, safeSize), wrapper);

        // 分类 ID -> 名称 映射，一次性查询避免 N+1
        Map<Long, String> categoryMap = loadCategoryMap();
        // 店铺 ID -> 名称 映射，同样一次性查询
        Map<Long, String> shopMap = loadShopMap();

        IPage<Product> raw = page;
        // 转成 VO 分页：保留分页元信息，仅替换 records
        Page<ProductVO> voPage = new Page<>(raw.getCurrent(), raw.getSize(), raw.getTotal());
        voPage.setRecords(raw.getRecords().stream()
                .map(p -> ProductVO.from(p, categoryMap.get(p.getCategoryId()),
                        shopMap.get(p.getMerchantId())))
                .collect(Collectors.toList()));
        return voPage;
    }

    @Override
    public ProductDetailVO getDetail(Long id) {
        // 浏览量真实自增，不走缓存
        baseMapper.incrViewCount(id);

        ProductDetailVO vo = cacheUtil.getOrLoad(
                RedisKey.PRODUCT_DETAIL + id,
                ProductDetailVO.class,
                () -> {
                    Product p = getById(id);
                    if (p == null) {
                        return null;
                    }
                    // 店铺名与店铺简介在缓存构建时一并填好。
                    // merchantId 会随每次请求回查覆盖（见下），
                    // 但 shopName 只能在构建时填 —— 它不在「实时字段」之列，
                    // 而商品归属变更极少发生，放缓存里没问题。
                    Merchant shop = p.getMerchantId() == null
                            ? null : merchantMapper.selectById(p.getMerchantId());
                    ProductDetailVO d = ProductDetailVO.from(
                            p, loadCategoryMap().get(p.getCategoryId()));
                    if (shop != null) {
                        d.setShopName(shop.getShopName());
                        d.setShopDesc(shop.getShopDesc());
                        d.setShopScore(shop.getScore());
                    }
                    return d;
                },
                DETAIL_TTL);

        if (vo == null) {
            throw new BusinessException(ResultCode.PRODUCT_NOT_EXIST);
        }
        if (vo.getStatus() == BizConst.PRODUCT_OFF_SHELF) {
            throw new BusinessException(ResultCode.PRODUCT_OFF_SHELF);
        }
        // 缓存里只有商品创建时的快照，stock/sales/viewCount 都会随业务变动。
        // 这三个字段每次都回查库覆盖，保证「详情页看到的库存就是真实可售库存」——
        // 电商场景下库存展示不实时会直接导致超卖投诉。
        Product latest = baseMapper.selectById(id);
        if (latest != null) {
            vo.setStock(latest.getStock());
            vo.setSales(latest.getSales());
            vo.setViewCount(latest.getViewCount());
            // 归属也回查：商家调整商品所属店铺后应立即生效，
            // 否则「联系商家」会打进旧店铺，前端看着却像是新店铺。
            vo.setMerchantId(latest.getMerchantId());
        }
        // SKU 同样每次回查，不进缓存快照。
        // 原因与上面三个字段一致：SKU 的 price/stock 会被下单流程实时改写，
        // 缓存里的价格会直接导致用户按旧价下单、库存显示虚高。
        // 顺带把只有启用中的 SKU 返回给前端，避免买到已下架的规格。
        vo.setSkuList(skuMapper.selectByProductId(id).stream()
                .filter(s -> s.getStatus() == null || s.getStatus() == BizConst.YES)
                .toList());
        return vo;
    }

    @Override
    public List<Category> listCategories() {
        return cacheUtil.getOrLoad(RedisKey.CATEGORY_LIST, List.class, () -> {
            List<Category> list = categoryMapper.selectList(
                    new LambdaQueryWrapper<Category>()
                            .eq(Category::getStatus, BizConst.YES)
                            .orderByDesc(Category::getSortOrder));
            return list;
        }, CATEGORY_TTL);
    }

    @Override
    public List<ProductVO> listRelated(Long productId, int limit) {
        Product product = getById(productId);
        if (product == null) {
            return List.of();
        }
        Map<Long, String> categoryMap = loadCategoryMap();
        List<Product> list = this.list(new LambdaQueryWrapper<Product>()
                .eq(Product::getCategoryId, product.getCategoryId())
                .eq(Product::getStatus, BizConst.PRODUCT_ON_SHELF)
                .ne(Product::getId, productId)
                .orderByDesc(Product::getSales)
                .last("LIMIT " + Math.min(Math.max(limit, 1), 20)));
        return list.stream()
                .map(p -> ProductVO.from(p, categoryMap.get(p.getCategoryId())))
                .collect(Collectors.toList());
    }

    /** 加载分类 ID -> 名称 映射 */
    private Map<Long, String> loadCategoryMap() {
        List<Category> categories = categoryMapper.selectList(null);
        return categories.stream().collect(Collectors.toMap(Category::getId, Category::getName,
                (a, b) -> a));
    }

    /**
     * 店铺 ID -> 店铺名 映射。
     *
     * <p>店铺数量少（几十家），全量查一次的开销可接受，
     * 比按商品逐个查店铺的 N+1 好得多。
     * 只取正常营业的店铺，停用的店名不展示。
     */
    private Map<Long, String> loadShopMap() {
        List<Merchant> merchants = merchantMapper.selectList(
                new LambdaQueryWrapper<Merchant>()
                        .eq(Merchant::getStatus, BizConst.MERCHANT_NORMAL));
        if (merchants.isEmpty()) {
            return Map.of();
        }
        return merchants.stream().collect(
                Collectors.toMap(Merchant::getId, Merchant::getShopName, (a, b) -> a));
    }
}
