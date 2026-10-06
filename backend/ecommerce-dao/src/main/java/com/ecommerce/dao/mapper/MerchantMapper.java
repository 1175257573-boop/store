package com.ecommerce.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.dao.entity.Merchant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 店铺 Mapper。
 */
@Mapper
public interface MerchantMapper extends BaseMapper<Merchant> {

    /** 按店主用户查店铺 */
    @Select("SELECT * FROM t_merchant WHERE user_id = #{userId} LIMIT 1")
    Merchant selectByUserId(@Param("userId") Long userId);

    /** 按店铺名查（入驻时查重） */
    @Select("SELECT * FROM t_merchant WHERE shop_name = #{shopName} LIMIT 1")
    Merchant selectByShopName(@Param("shopName") String shopName);

    /** 店铺列表（管理端用，支持按状态筛选） */
    @Select("<script>SELECT * FROM t_merchant "
            + "<where>"
            + "  <if test='status != null'> AND status = #{status} </if>"
            + "  <if test='keyword != null and keyword.length() > 0'>"
            + "    AND (shop_name LIKE CONCAT('%', #{keyword}, '%')"
            + "         OR contact_name LIKE CONCAT('%', #{keyword}, '%'))"
            + "  </if>"
            + "</where> ORDER BY id DESC</script>")
    List<Merchant> selectByCondition(@Param("status") Integer status,
                                     @Param("keyword") String keyword);

    /**
     * 冻结/解冻店铺。
     * <p>带原状态条件：影响 0 行说明状态已变，调用方据此判断是否需提示。</p>
     */
    @Update("UPDATE t_merchant SET status = #{status} WHERE id = #{merchantId} AND status <> 3")
    int updateShopStatus(@Param("merchantId") Long merchantId,
                         @Param("status") Integer status);

    /**
     * 累加店铺统计（发货确认时调用）。
     * <p>用 SQL 原子自增而非读改写，避免并发丢计数。</p>
     */
    @Update("UPDATE t_merchant SET total_order = total_order + #{orderCount}, "
            + "total_sales = total_sales + #{sales} "
            + "WHERE id = #{merchantId}")
    int accumulateStats(@Param("merchantId") Long merchantId,
                        @Param("orderCount") int orderCount,
                        @Param("sales") java.math.BigDecimal sales);

    /** 重算商品数（商品增删改后调用，避免冗余字段与实际不一致） */
    @Update("UPDATE t_merchant SET total_product = ("
            + "  SELECT COUNT(*) FROM t_product WHERE merchant_id = #{merchantId}"
            + ") WHERE id = #{merchantId}")
    int refreshProductCount(@Param("merchantId") Long merchantId);
}
