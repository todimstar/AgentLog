package com.agentlog.identity.application;

import com.agentlog.identity.api.dto.RegisterRequest;
import com.agentlog.identity.api.dto.UserView;
import com.agentlog.identity.domain.UserStatus;
import com.agentlog.identity.infrastructure.persistence.dataobject.UserAccount;
import com.agentlog.identity.infrastructure.persistence.mapper.UserAccountMapper;
import com.agentlog.shared.error.ApiException;
import com.agentlog.shared.error.ApiStatus;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * identity 应用服务：编排注册 / 发验证码事务。
 * 分层：Controller 只接 HTTP，这里负责业务编排 + 事务，Mapper 只持久化。
 *
 * L11.5：注册从"用户名 + 展示名"改为"邮箱 + 唯一用户名 + 邮箱验证码"。
 */
@Service
public class IdentityService {

    /**
     * media_object.status 的 ACTIVE 值。跨模块只读投影直查约定：不 import media 模块的
     * MediaStatus 枚举（模块间零 Java 依赖），此字面量须与 media 模块的 MediaStatus.ACTIVE 对齐。
     */
    private static final String MEDIA_STATUS_ACTIVE = "ACTIVE";

    private final UserAccountMapper userAccountMapper;
    private final PasswordEncoder passwordEncoder;
    private final EmailVerificationService emailVerificationService;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public IdentityService(
            UserAccountMapper userAccountMapper,
            PasswordEncoder passwordEncoder,
            EmailVerificationService emailVerificationService,
            JdbcTemplate jdbcTemplate,
            Clock clock) {
        this.userAccountMapper = userAccountMapper;
        this.passwordEncoder = passwordEncoder;
        this.emailVerificationService = emailVerificationService;
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    /** 发注册验证码：邮箱未被占用才发（占用了直接拦，省一封邮件）。 */
    public void sendRegisterCode(String email) {
        if (emailExists(email)) {
            throw new ApiException(ApiStatus.EMAIL_TAKEN);
        }
        emailVerificationService.issueRegisterCode(email);
    }

    @Transactional
    public UserView register(RegisterRequest request) {
        // 参数校验（非空/格式/长度）已由 record 上的 Jakarta 注解 + Controller @Valid 完成，
        // 非法请求进不到这里；本方法只管业务规则（验证码、唯一性）。

        // 1. 验证码校验（Redis 取码比对，通过即删——一次性防重放）。放最前面，挡住没走验证码的注册。
        emailVerificationService.verifyRegisterCode(request.email(), request.verCode());

        // 2. 唯一性查重（库层 uk_user_email / uk_user_username 唯一键兜底，双重保险防并发）。
        if (emailExists(request.email())) {
            throw new ApiException(ApiStatus.EMAIL_TAKEN);
        }
        if (usernameExists(request.username())) {
            throw new ApiException(ApiStatus.USERNAME_TAKEN);
        }

        Instant now = Instant.now(clock);
        UserAccount account = new UserAccount();
        account.setEmail(request.email());
        account.setUsername(request.username());
        // bcrypt 加密：永不存明文。bcrypt 自带盐，同样的密码每次哈希结果都不同。
        account.setPasswordHash(passwordEncoder.encode(request.password()));
        account.setStatus(UserStatus.ACTIVE.getCode());
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

    /**
     * 设置头像（L10-L11 修复：补齐"上传→绑定→展示"里断掉的绑定环）。
     * 把一张已 finalize（ACTIVE）且属于本人的媒体，写进 user_account.avatar_media_public_id。
     *
     * 校验用【跨模块只读投影直查】——直接读 media_object 物理表，不引 media 模块的 Java 类
     * （与 forum 读 user_account 同约定：读走直查、写只碰自己的表）。
     */
    @Transactional
    public UserView setAvatar(long userId, String mediaId) {
        //验证图像是否存在、是否是用户上传的，是否是活跃状态
        if (mediaId == null || mediaId.isBlank()) {
            throw new ApiException(ApiStatus.MEDIA_NOT_FOUND);
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT uploader_user_id, status FROM media_object WHERE public_id = ?", mediaId);
        if (rows.isEmpty()) {
            throw new ApiException(ApiStatus.MEDIA_NOT_FOUND);
        }
        Map<String, Object> media = rows.get(0);
        Number uploader = (Number) media.get("uploader_user_id");
        if (uploader == null || uploader.longValue() != userId) {
            throw new ApiException(ApiStatus.MEDIA_FORBIDDEN);        // 只能设自己上传的图
        }
        if (!MEDIA_STATUS_ACTIVE.equals(media.get("status"))) {
            throw new ApiException(ApiStatus.MEDIA_NOT_UPLOADED);      // 没 finalize 的 PENDING 不能当头像
        }

        //图像合格编交给user表持久化
        UserAccount account = userAccountMapper.selectById(userId);
        if (account == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_SESSION_REQUIRED", "会话无效");
        }
        account.setAvatarMediaPublicId(mediaId);
        account.setUpdatedAt(Instant.now(clock));
        userAccountMapper.updateById(account);                        // @Version 乐观锁自动带 WHERE version
        return UserView.from(account);
    }

    private boolean emailExists(String email) {
        Long count = userAccountMapper.selectCount(
                Wrappers.<UserAccount>lambdaQuery().eq(UserAccount::getEmail, email));
        return count != null && count > 0;
    }

    private boolean usernameExists(String username) {
        Long count = userAccountMapper.selectCount(
                Wrappers.<UserAccount>lambdaQuery().eq(UserAccount::getUsername, username));
        return count != null && count > 0;
    }
}
