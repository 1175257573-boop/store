package com.ecommerce.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.dao.entity.SeckillActivity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 秒杀活动 Mapper。
 *
 * <p><b>关于注解 SQL 里的尖括号</b>（此处踩了三次坑，完整记录）：</p>
 * <ul>
 *   <li>裸 <= ：XML 解析器把它当标签起始，报「元素内容必须由格式正确的字符数据组成」</li>
 *   <li>只写 &lt;= 不加 script：MyBatis 不做实体解码，
 *       &lt; 原样发给 MySQL，报语法错</li>
 *   <li><b>正确做法</b>：script 包裹 + &lt; &gt; 转义。
 *       两者缺一不可：script 让 MyBatis 解码实体，转义让 XML 解析器不误判为标签。</li>
 * </ul>
 * <p><b>规律：注解 SQL 只要含尖括号，一律 script 包裹 + 实体转义。</b></p>
 */
@Mapper
public interface SeckillActivityMapper extends BaseMapper<SeckillActivity> {

    /** 按活动编号查 */
    @Select("SELECT * FROM t_seckill_activity WHERE activity_no = #{activityNo}")
    SeckillActivity selectByActivityNo(@Param("activityNo") String activityNo);

    /** 查进行中的活动（按开始时间倒序取最新一场） */
    @Select("<script>"
            + "SELECT * FROM t_seckill_activity WHERE status = 1 "
            + "AND start_time &lt;= #{now} AND end_time &gt;= #{now} "
            + "ORDER BY start_time DESC LIMIT 1"
            + "</script>")
    SeckillActivity selectActiveActivity(@Param("now") LocalDateTime now);

    /** 全部活动（管理端 / 测试用） */
    @Select("SELECT * FROM t_seckill_activity ORDER BY id")
    List<SeckillActivity> selectAll();

    /**
     * 结束过期活动。
     * <p>影响行数为 0 说明已被其他实例处理，天然幂等。</p>
     */
    @Update("<script>"
            + "UPDATE t_seckill_activity SET status = 2 "
            + "WHERE status = 1 AND end_time &lt; #{now}"
            + "</script>")
    int finishExpiredActivities(@Param("now") LocalDateTime now);
}
