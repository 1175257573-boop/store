package com.ecommerce.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ecommerce.dao.entity.User;
import com.ecommerce.service.dto.LoginDTO;
import com.ecommerce.service.dto.ProfileUpdateDTO;
import com.ecommerce.service.dto.RegisterDTO;
import com.ecommerce.service.vo.LoginVO;

/**
 * 用户服务接口。
 */
public interface UserService extends IService<User> {

    /**
     * 注册。
     *
     * @return 新用户 ID
     */
    Long register(RegisterDTO dto);

    /**
     * 登录，成功后签发 JWT。
     */
    LoginVO login(LoginDTO dto);

    /**
     * 查询当前登录用户信息。
     */
    User getCurrentUser();

    /**
     * 更新当前用户资料。
     */
    void updateProfile(ProfileUpdateDTO dto);

    /**
     * 修改密码。
     *
     * @param oldPassword 旧密码明文
     * @param newPassword 新密码明文
     */
    void changePassword(String oldPassword, String newPassword);
}