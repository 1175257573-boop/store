package com.ecommerce.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.dao.entity.Address;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 收货地址 Mapper。
 */
@Mapper
public interface AddressMapper extends BaseMapper<Address> {

    /** 查询用户全部地址，默认地址优先 */
    @Select("SELECT * FROM t_address WHERE user_id = #{userId} "
            + "ORDER BY is_default DESC, update_time DESC")
    List<Address> selectByUserId(@Param("userId") Long userId);

    /** 查询默认地址 */
    @Select("SELECT * FROM t_address WHERE user_id = #{userId} AND is_default = 1 LIMIT 1")
    Address selectDefault(@Param("userId") Long userId);

    /**
     * 统计用户地址数量。
     * <p>显式写 SQL 而不用 {@code IService.count(lambdaQuery())}：后者在
     * MyBatis-Plus 3.5.7 下会抛
     * {@code MybatisPlusException: can not use this method for "getSqlFirst"}
     * ——OGNL 求值 Wrapper 的 {@code sqlFirst} 属性失败。</p>
     */
    @Select("SELECT COUNT(*) FROM t_address WHERE user_id = #{userId}")
    Long countByUser(@Param("userId") Long userId);

    /** 先把该用户所有地址置为非默认 */
    @Update("UPDATE t_address SET is_default = 0 WHERE user_id = #{userId}")
    int clearDefault(@Param("userId") Long userId);

    /** 设为默认地址 */
    @Update("UPDATE t_address SET is_default = 1 WHERE id = #{id} AND user_id = #{userId}")
    int setDefault(@Param("id") Long id, @Param("userId") Long userId);
}