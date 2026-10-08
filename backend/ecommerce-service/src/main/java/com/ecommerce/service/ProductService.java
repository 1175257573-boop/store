package com.ecommerce.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.ecommerce.dao.entity.Category;
import com.ecommerce.dao.entity.Product;
import com.ecommerce.service.vo.ProductDetailVO;
import com.ecommerce.service.vo.ProductVO;

import java.util.List;

/**
 * 商品服务接口。
 */
public interface ProductService extends IService<Product> {

    /**
     * 分页查询商品。
     *
     * @param pageNum    页码，从 1 开始
     * @param pageSize   每页条数
     * @param categoryId 分类 ID，可为 null 表示全部
     * @param keyword    名称关键字，可为 null
     * @param sortBy     排序字段：default/sales/priceAsc/priceDesc
     * @param merchantId 店铺ID筛选，null 表示不限店铺
     */
    IPage<ProductVO> pageProducts(int pageNum, int pageSize, Long categoryId,
                                  String keyword, String sortBy, Long merchantId);

    /**
     * 查询商品详情，同时浏览量 +1。
     * <p>走 Redis 缓存，但浏览量不入缓存（每次都要真实自增）。</p>
     */
    ProductDetailVO getDetail(Long id);

    /**
     * 查询所有启用中的分类。
     */
    List<Category> listCategories();

    /**
     * 猜你喜欢：同分类下的其他在售商品。
     */
    List<ProductVO> listRelated(Long productId, int limit);
}