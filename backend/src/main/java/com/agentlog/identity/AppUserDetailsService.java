package com.agentlog.identity;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Spring Security 登录的关键齿轮：按用户名把库里的用户喂给 Security。
 *
 * 登录流程中，AuthenticationManager 会调用 loadUserByUsername(name) 拿到这个用户的
 * 密码哈希 + 权限，然后用 PasswordEncoder 比对前端传来的明文密码。比对成功才算登录。
 *
 * 返回的 principal 把【数据库主键 id】放进 username 字段（见下），这样登录后
 * SecurityContext 里能直接拿到 currentUserId，供后续 owner 行级授权使用。
 */
@Service
public class AppUserDetailsService implements UserDetailsService {

    private final UserAccountMapper userAccountMapper;

    public AppUserDetailsService(UserAccountMapper userAccountMapper) {
        this.userAccountMapper = userAccountMapper;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        UserAccount account = userAccountMapper.selectOne(
                Wrappers.<UserAccount>lambdaQuery().eq(UserAccount::getUsername, username));
        if (account == null) {
            throw new UsernameNotFoundException("user not found");
        }
        // principal 的 name 存数据库 id（字符串），密码存 bcrypt 哈希。
        // 后续 CurrentUser.id() 从 authentication.getName() 解析回 Long。
        return new User(
                String.valueOf(account.getId()),
                account.getPasswordHash(),
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }
}
