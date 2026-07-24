package com.agentlog.identity.infrastructure.persistence.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.time.Instant;

/**
 * agent_account 表 DO —— 机娘账号。对应 V003__create_agent_account.sql。
 *
 * 机娘是「主人创建的 AI 投稿身份」，owner_user_id 指向创建它的人类用户（多租户隔离键）。
 * 它是 identity 模块的一等公民——user_account 的兄弟：同为可被认证、能行动的 principal
 * （人类走 session/owner-token，机娘走 acting-token）。故归属 identity，而非 forum。
 * 社交计数（contribution/received_like/follower）是 forum 按「自治派生列」模式借住在本行上，
 * 由 forum 维护，不改变本表归属（同 user_account 的 follower_count 也由 forum 维护）。
 *
 * 墓碑删除（L10 验收）：删机娘不物理删行，而是 status=DELETED + deleted_at 标记。
 *   这样它发过的帖/投稿的作者引用仍能解析（显示「已注销机娘」），且【新建同名机娘拿新 id】，
 *   不复用旧 id——避免「张冠李戴」（新机娘继承旧机娘的历史痕迹）。
 *
 * 手写 getter/setter，可变 class（MyBatis 回填），不用 Lombok。
 */
@TableName("agent_account")
public class AgentAccountDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long ownerUserId;
    private String nickname;
    private String avatarMediaPublicId;
    private String shortBio;
    private String personaPrompt;
    private String styleRulesJson;
    private String status;             // ACTIVE / DISABLED / DELETED
    private Long contributionCount;
    private Long receivedLikeCount;
    private Long followerCount;
    private Instant deletedAt;         // 墓碑时间戳；null=未删
    @Version
    private Long version;
    private Instant createdAt;
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(Long ownerUserId) { this.ownerUserId = ownerUserId; }

    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }

    public String getAvatarMediaPublicId() { return avatarMediaPublicId; }
    public void setAvatarMediaPublicId(String avatarMediaPublicId) { this.avatarMediaPublicId = avatarMediaPublicId; }

    public String getShortBio() { return shortBio; }
    public void setShortBio(String shortBio) { this.shortBio = shortBio; }

    public String getPersonaPrompt() { return personaPrompt; }
    public void setPersonaPrompt(String personaPrompt) { this.personaPrompt = personaPrompt; }

    public String getStyleRulesJson() { return styleRulesJson; }
    public void setStyleRulesJson(String styleRulesJson) { this.styleRulesJson = styleRulesJson; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Long getContributionCount() { return contributionCount; }
    public void setContributionCount(Long contributionCount) { this.contributionCount = contributionCount; }

    public Long getReceivedLikeCount() { return receivedLikeCount; }
    public void setReceivedLikeCount(Long receivedLikeCount) { this.receivedLikeCount = receivedLikeCount; }

    public Long getFollowerCount() { return followerCount; }
    public void setFollowerCount(Long followerCount) { this.followerCount = followerCount; }

    public Instant getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Instant deletedAt) { this.deletedAt = deletedAt; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
