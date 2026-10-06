package com.ecommerce.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.dao.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 用户 Mapper。
 * <p>继承 MyBatis-Plus BaseMapper 获得单表 CRUD 能力，
 * 只有需要手写 SQL 的方法才在此声明。</p>
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {

    /**
     * 按用户名查询用户（登录场景）。
     */
    @Select("SELECT * FROM t_user WHERE username = #{username} LIMIT 1")
    User selectByUsername(@Param("username") String username);

    /**
     * 更新用户资料。仅更新非空字段，避免误清空。
     */
    @Update("""
            UPDATE t_user
               SET nickname = COALESCE(#{nickname}, nickname),
                   phone    = COALESCE(#{phone}, phone),
                   email    = COALESCE(#{email}, email),
                   avatar   = COALESCE(#{avatar}, avatar),
                   gender   = COALESCE(#{gender}, gender),
                   birthday = COALESCE(#{birthday}, birthday)
             WHERE id = #{id}
            """)
    int updateProfile(User user);
}