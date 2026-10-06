package com.ecommerce.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.dao.entity.SeckillOrderItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 秒杀订单明细 Mapper。
 */
@Mapper
public interface SeckillOrderItemMapper extends BaseMapper<SeckillOrderItem> {

    @Select("SELECT * FROM t_seckill_order_item WHERE order_id = #{orderId}")
    List<SeckillOrderItem> selectByOrderId(@Param("orderId") Long orderId);
}
