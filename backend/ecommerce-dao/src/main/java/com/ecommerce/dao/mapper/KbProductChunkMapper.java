package com.ecommerce.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.dao.entity.KbProductChunk;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 商品知识块 Mapper。
 *
 * <p>全文检索必须走手写 SQL：MyBatis-Plus 的 QueryWrapper 无法表达
 * {@code MATCH ... AGAINST}，而这是 BM25 检索的基础。
 */
@Mapper
public interface KbProductChunkMapper extends BaseMapper<KbProductChunk> {

    /**
     * BM25 全文检索（ngram 分词）。
     *
     * <p>注意：{@code query} 由 Service 层做白名单过滤后传入，
     * 这里只做 SQL 拼接，不接受用户原始输入。
     *
     * <p>AGAINST 必须写成 {@code AGAINST('关键词' IN BOOLEAN MODE)}，
     * 少了引号 MySQL 会把关键词当列名，报 ERROR 1054 Unknown column。
     */
    @Select("SELECT c.id, c.product_id, c.category_id, c.chunk_type, c.title, "
            + "       c.content, c.keywords, c.weight, c.product_id AS matched_product_id, "
            + "       MATCH(c.title, c.content, c.keywords) AGAINST(#{query} IN BOOLEAN MODE) AS score "
            + "FROM t_kb_product_chunk c "
            + "WHERE c.status = 1 "
            + "  AND MATCH(c.title, c.content, c.keywords) AGAINST(#{query} IN BOOLEAN MODE) "
            + "ORDER BY score DESC LIMIT #{limit}")
    List<KbProductChunk> searchByBm25(@Param("query") String query,
                                      @Param("limit") int limit);

    /**
     * 取某商品的全部知识块 —— 会话上下文锁定后用它，
     * 保证「问的是哪款商品，答的就是哪款商品」。
     */
    @Select("SELECT * FROM t_kb_product_chunk "
            + "WHERE product_id = #{productId} AND status = 1 "
            + "ORDER BY weight DESC, chunk_type")
    List<KbProductChunk> listByProduct(@Param("productId") Long productId);

    /**
     * 按商品名模糊找商品 —— 用户说「曜石手机」时定位具体商品。
     * 用 LIKE 而非全文索引：商品名短且用户输入更短，LIKE 足够。
     */
    @Select("SELECT id, name FROM t_product "
            + "WHERE name LIKE CONCAT('%', #{keyword}, '%') "
            + "ORDER BY LENGTH(name) DESC LIMIT #{limit}")
    List<ProductNameVO> findProductsByName(@Param("keyword") String keyword,
                                           @Param("limit") int limit);

    /**
     * 商品名 + 分类的轻量视图，供会话上下文用。
     */
    class ProductNameVO {
        private Long id;
        private String name;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }
}