package com.agentlog.identity.application;

import com.agentlog.identity.api.dto.RegisterRequest;
import com.agentlog.identity.api.dto.UserView;
import com.agentlog.identity.infrastructure.persistence.dataobject.UserAccount;
import com.agentlog.identity.infrastructure.persistence.mapper.UserAccountMapper;
import com.agentlog.shared.error.ApiException;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Clock;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * identity 应用服务：编排注册事务。
 * 分层：Controller 只接 HTTP，这里负责业务编排 + 事务，Mapper 只持久化。
 */
@Service
public class IdentityService {

    private static final int MIN_PASSWORD_LENGTH = 8;

    private final UserAccountMapper userAccountMapper;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public IdentityService(
            UserAccountMapper userAccountMapper,
            PasswordEncoder passwordEncoder,
            Clock clock) {
        this.userAccountMapper = userAccountMapper;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    @Transactional
    public UserView register(RegisterRequest request) {
        validate(request);

        // 查重：用户名唯一。库层还有 uk_user_username 唯一键兜底（双重保险防并发）。
        Long existing = userAccountMapper.selectCount(
                Wrappers.<UserAccount>lambdaQuery().eq(UserAccount::getUsername, request.username()));
        if (existing != null && existing > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "USER_USERNAME_TAKEN", "用户名已被占用");
        }

        Instant now = Instant.now(clock);
        UserAccount account = new UserAccount();
        account.setUsername(request.username());
        // bcrypt 加密：永不存明文。bcrypt 自带盐，同样的密码每次哈希结果都不同。
        account.setPasswordHash(passwordEncoder.encode(request.password()));
        account.setDisplayName(request.displayName());
        account.setStatus("ACTIVE");
        account.setFollowerCount(0L);
        account.setFollowingCount(0L);
        account.setReceivedLikeCount(0L);
        account.setVersion(0L);
        account.setCreatedAt(now);
        account.setUpdatedAt(now);
        userAccountMapper.insert(account);

        return UserView.from(account);
    }

    public UserView currentUserView(long userId) {
        UserAccount account = userAccountMapper.selectById(userId);
        if (account == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_SESSION_REQUIRED", "会话无效");
        }
        return UserView.from(account);
    }

    private void validate(RegisterRequest request) {
        if (request == null
                || isBlank(request.username())
                || isBlank(request.password())
                || isBlank(request.displayName())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "注册字段不完整");
        }
        if (request.password().length() < MIN_PASSWORD_LENGTH) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "密码至少 8 位");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
