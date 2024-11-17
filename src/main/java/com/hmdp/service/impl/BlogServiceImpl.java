package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.print.attribute.standard.JobName;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    //点赞功能的缺陷，如果用户点击过快，点赞数量可能不准可以根据用户id来设置锁
    //使用zest来实现点赞列表
    @Resource
    private IUserService userService;

    @Resource
    private IFollowService followService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final String blogIsLikedPrefix = "blog:isliked";

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            this.queryBlog(blog);
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result queryBlogById(Long id) {
        Blog blog = getById(id);
        if(blog == null) return Result.fail("博客不存在");
        queryBlog(blog);
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    @Override
    public Result likeBlog(Long id) {
        Long userId = UserHolder.getUser().getId();
        String key = blogIsLikedPrefix + id;

        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if(score == null){
            //表示可以点赞
            boolean success = update().setSql("liked = liked + 1").eq("id", id).update();
            if(success) stringRedisTemplate.opsForZSet().add(key, userId.toString(),System.currentTimeMillis());
        }else{
            boolean success = update().setSql("liked = liked - 1").eq("id", id).update();
            if(success) stringRedisTemplate.opsForZSet().remove(key,userId.toString());
        }
        //表示点过赞了
        return Result.ok();
    }

    @Override
    public Result queryMyBlog(Integer current) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        // 根据用户查询
        Page<Blog> page = query()
                .eq("user_id", user.getId()).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        return Result.ok(records);
    }

    @Override
    public Result queryBlogLikes(Long id) {
        String key = blogIsLikedPrefix + id;
        Set<String> set = stringRedisTemplate.opsForZSet().range(key, 0, 4);

        if(set == null || set.isEmpty()) return Result.ok(Collections.emptyList());

        List<Long> userIds = set.stream().map(Long::valueOf).collect(Collectors.toList());
        String join = StrUtil.join(",", userIds);

        List<UserDTO> userDTOS = userService.query().in("id",userIds)
                .last("ORDER BY FIELD (id," + join + ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        //这里使用id列表查询用户的时候，底层使用in的方式可能导致顺序和预期顺序不一样
        return Result.ok(userDTOS);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean isSuccess = save(blog);
        List<Follow> follows = new ArrayList<>();
        if(isSuccess){
            //将文章id保存到关注用的收件箱中
            follows = followService.query().eq("follow_user_id", user.getId()).list();
        }

        for (Follow follow : follows) {
            Long userId = follow.getUserId();
            String key = "feed:" + userId;

            //给用户按时间进行推送，博主发的博客时间越近，则会被优先推送
            stringRedisTemplate.opsForZSet().add(key,blog.getId().toString(),System.currentTimeMillis());
        }
        // 返回id
        return Result.ok(blog.getId());
    }

    private void queryBlog(Blog blog){
        //解决用户未登录查询首页空指针异常
        UserDTO userDTO = UserHolder.getUser();
        if(userDTO == null) return ;

        Long userId = blog.getUserId();
         User user = userService.getById(userId);
         blog.setName(user.getNickName());
         blog.setIcon(user.getIcon());
     }

     private void isBlogLiked(Blog blog){
         Long userId = UserHolder.getUser().getId();
         String key = blogIsLikedPrefix + blog.getId();

         Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
         blog.setIsLike(score != null);

     }
}
