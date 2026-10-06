package com.ecommerce.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.dao.entity.CartItem;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 购物车 Mapper。
 */
@Mapper
public interface CartItemMapper extends BaseMapper<CartItem> {

    /** 查询用户购物车全部条目 */
    @Select("SELECT * FROM t_cart_item WHERE user_id = #{userId} ORDER BY update_time DESC")
    List<CartItem> selectByUserId(@Param("userId") Long userId);

    /** 查询用户购物车中已勾选的条目（结算时使用） */
    @Select("SELECT * FROM t_cart_item WHERE user_id = #{userId} AND checked = 1 ORDER BY update_time DESC")
    List<CartItem> selectCheckedByUserId(@Param("userId") Long userId);

    /** 更新勾选状态 */
    @Update("UPDATE t_cart_item SET checked = #{checked} WHERE id = #{id} AND user_id = #{userId}")
    int updateChecked(@Param("id") Long id, @Param("userId") Long userId,
                      @Param("checked") Integer checked);

    /** 全选/全不选 */
    @Update("UPDATE t_cart_item SET checked = #{checked} WHERE user_id = #{userId}")
    int updateAllChecked(@Param("userId") Long userId, @Param("checked") Integer checked);

    /** 更新购买数量 */
    @Update("UPDATE t_cart_item SET quantity = #{quantity} WHERE id = #{id} AND user_id = #{userId}")
    int updateQuantity(@Param("id") Long id, @Param("userId") Long userId,
                       @Param("quantity") Integer quantity);

    /** 删除条目（带 userId 校验，防越权删除他人购物车） */
    @Delete("DELETE FROM t_cart_item WHERE id = #{id} AND user_id = #{userId}")
    int deleteByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    /** 批量删除 */
    @Delete("<script>DELETE FROM t_cart_item WHERE user_id = #{userId} AND id IN "
            + "<foreach collection='ids' item='i' open='(' separator=',' close=')'>#{i}</foreach></script>")
    int deleteBatch(@Param("userId") Long userId, @Param("ids") List<Long> ids);

    /** 下单成功后清理已结算条目 */
    @Delete("<script>DELETE FROM t_cart_item WHERE user_id = #{userId} AND id IN "
            + "<foreach collection='ids' item='i' open='(' separator=',' close=')'>#{i}</foreach></script>")
    int deleteByIds(@Param("userId") Long userId, @Param("ids") List<Long> ids);
}