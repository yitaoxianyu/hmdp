package com.hmdp.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //校验手机号是否符合
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }
        //发送验证码
        String code = RandomUtil.randomNumbers(6);
        log.debug("验证码为:{}",code);

        session.setAttribute("code",code);

        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        if (RegexUtils.isPhoneInvalid(loginForm.getPhone())) {
            return Result.fail("手机号格式错误");
        }

        Object cacheCode = session.getAttribute("code");
        if(cacheCode == null || !cacheCode.equals(loginForm.getCode()))
            return Result.fail("验证码过期或错误");

        User user = query().eq("phone",loginForm.getPhone()).one();
        if(user == null) {
            //创建一个用户
            String phone = loginForm.getPhone();
            user = createUserWithPhone(phone);
        }
        UserDTO userDTO = new UserDTO(); BeanUtils.copyProperties(user,userDTO);
        session.setAttribute("user",userDTO);

        return Result.ok();
    }
    private User createUserWithPhone(String phone){
        User user = new User();
        user.setPhone(phone);
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(12));

        save(user);

        return user;
    }
}
