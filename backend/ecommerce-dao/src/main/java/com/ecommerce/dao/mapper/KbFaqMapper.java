package com.ecommerce.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.dao.entity.KbFaq;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 客服问答对 Mapper。
 */
@Mapper
public interface KbFaqMapper extends BaseMapper<KbFaq> {

    /**
     * 通用问答（与具体商品无关：物流、售后、支付等）。
     * 店铺类问题走这里 —— 答通用政策不该绑到某个商品上。
     */
    @Select("SELECT * FROM t_kb_faq "
            + "WHERE product_id IS NULL AND status = 1 "
            + "ORDER BY priority ASC, id")
    List<KbFaq> listShopFaq();

    /**
     * BM25 全文检索问答对。
     * 与知识块同理：{@code query} 由 Service 层白名单过滤后传入。
     */
    @Select("SELECT f.*, "
            + "  MATCH(f.question, f.question_kw, f.answer) AGAINST(#{query} IN BOOLEAN MODE) AS score "
            + "FROM t_kb_faq f "
            + "WHERE f.status = 1 "
            + "  AND MATCH(f.question, f.question_kw, f.answer) AGAINST(#{query} IN BOOLEAN MODE) "
            + "ORDER BY score DESC LIMIT #{limit}")
    List<KbFaq> searchByBm25(@Param("query") String query,
                              @Param("limit") int limit);

    /**
     * 按意图取问答 —— 意图识别出结果后用它拿最相关的标准答案。
     */
    @Select("SELECT * FROM t_kb_faq "
            + "WHERE status = 1 AND intent = #{intent} "
            + "ORDER BY priority ASC, hit_count DESC, id LIMIT #{limit}")
    List<KbFaq> listByIntent(@Param("intent") String intent,
                             @Param("limit") int limit);

    /**
     * 命中次数 +1，用于后续按热门问题排序。
     * 用 SQL 原子自增，避免读-改-写竞态。
     */
    @Update("UPDATE t_kb_faq SET hit_count = hit_count + 1 WHERE id = #{id}")
    int incrHitCount(@Param("id") Long id);
}