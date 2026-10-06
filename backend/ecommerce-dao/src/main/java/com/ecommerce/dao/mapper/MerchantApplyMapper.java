package com.ecommerce.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.dao.entity.MerchantApply;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 入驻申请 Mapper。
 */
@Mapper
public interface MerchantApplyMapper extends BaseMapper<MerchantApply> {

    @Select("SELECT * FROM t_merchant_apply WHERE user_id = #{userId} "
            + "ORDER BY id DESC LIMIT 1")
    MerchantApply selectLatestByUser(@Param("userId") Long userId);

    /** 查该用户是否有待审核申请 */
    @Select("SELECT * FROM t_merchant_apply WHERE user_id = #{userId} AND status = 0 LIMIT 1")
    MerchantApply selectPendingByUser(@Param("userId") Long userId);

    /**
     * 审核通过。
     * <p>状态机守卫：只有 status=0 能改，重复提交审核接口时影响 0 行，
     * 天然幂等，不会重复建店。</p>
     */
    @Update("UPDATE t_merchant_apply SET status = 1, audit_user_id = #{auditUserId}, "
            + "audit_remark = #{remark}, audit_time = #{now}, merchant_id = #{merchantId} "
            + "WHERE id = #{id} AND status = 0")
    int approve(@Param("id") Long id,
                @Param("auditUserId") Long auditUserId,
                @Param("remark") String remark,
                @Param("merchantId") Long merchantId,
                @Param("now") java.time.LocalDateTime now);

    @Update("UPDATE t_merchant_apply SET status = 2, audit_user_id = #{auditUserId}, "
            + "audit_remark = #{remark}, audit_time = #{now} "
            + "WHERE id = #{id} AND status = 0")
    int reject(@Param("id") Long id,
               @Param("auditUserId") Long auditUserId,
               @Param("remark") String remark,
               @Param("now") java.time.LocalDateTime now);

    /** 用户主动撤销 */
    @Update("UPDATE t_merchant_apply SET status = 3 "
            + "WHERE id = #{id} AND user_id = #{userId} AND status = 0")
    int revoke(@Param("id") Long id, @Param("userId") Long userId);

    /** 申请列表（管理端） */
    @Select("<script>SELECT * FROM t_merchant_apply "
            + "<where>"
            + "  <if test='status != null'> AND status = #{status} </if>"
            + "  <if test='keyword != null and keyword.length() > 0'>"
            + "    AND (shop_name LIKE CONCAT('%', #{keyword}, '%')"
            + "         OR contact_name LIKE CONCAT('%', #{keyword}, '%'))"
            + "  </if>"
            + "</where> ORDER BY id DESC</script>")
    List<MerchantApply> selectByCondition(@Param("status") Integer status,
                                          @Param("keyword") String keyword);

    /**
     * 统计待审核的入驻申请数（管理员红点用）。
     */
    @Select("SELECT COUNT(*) FROM t_merchant_apply WHERE status = 0")
    Long countPending();
}
