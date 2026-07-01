package com.agentlog.forum.application;

import com.agentlog.forum.api.dto.response.ToggleStateResponse;
import com.agentlog.forum.infrastructure.persistence.dataobject.CollectionRecordDO;
import com.agentlog.forum.infrastructure.persistence.dataobject.ReactionDO;
import com.agentlog.forum.infrastructure.persistence.mapper.CollectionRecordMapper;
import com.agentlog.forum.infrastructure.persistence.mapper.CommentMapper;
import com.agentlog.forum.infrastructure.persistence.mapper.ReactionMapper;
import com.agentlog.shared.error.ApiException;
import com.agentlog.shared.error.ApiStatus;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * 点赞 / 收藏服务 —— L09。归 forum（社区互动）。
 *
 * 两个方法共用同一个【toggle 模式】：点一下加、再点一下取消，整套靠唯一键 + INSERT IGNORE 的 affectedRows 驱动。
 *
 * toggle 三步（以点赞为例）：
 *  1. INSERT IGNORE 一条记录。
 *  2. 看 affectedRows：1 = 之前没赞→这次加上（计数 +1）；0 = 之前已赞→这次是取消（DELETE 那条 + 计数 -1）。
 *  3. 返回 {active(当前是否赞中), count(目标当前赞数)}。
 *
 * 为什么不"先 SELECT 有没有再插/删"？并发会撞：A/B 同时点，都查无→都插→重复键脏数据。
 * 唯一键 + INSERT IGNORE 把"判断加删"下推给 DB，并发安全，零竞态。这是本课的核心面试点。
 *
 * 计数用 GREATEST(0, count + delta) 防变负（见 XML 注释）。
 */
@Service
public class ReactionService {

    static final String TARGET_POST = "POST";
    static final String TARGET_COMMENT = "COMMENT";

    private final ReactionMapper reactionMapper;
    private final CollectionRecordMapper collectionMapper;
    private final CommentMapper commentMapper;      // 验证 COMMENT 目标存在用
    private final Clock clock;

    public ReactionService(ReactionMapper reactionMapper, CollectionRecordMapper collectionMapper,
                           CommentMapper commentMapper, Clock clock) {
        this.reactionMapper = reactionMapper;
        this.collectionMapper = collectionMapper;
        this.commentMapper = commentMapper;
        this.clock = clock;
    }

    /**
     * 点赞 toggle。targetType=POST 给 post.like_count 增减；COMMENT 给 comment.like_count 增减。
     * 返回点赞后状态 + 目标当前总赞数。
     */
    @Transactional
    public ToggleStateResponse toggleReaction(long currentUserId, String targetType, long targetId) {
        Instant now = Instant.now(clock);

        // —— 校验目标存在 —— 多态不画外键，应用层保证 target_id 有效。
        if (TARGET_POST.equals(targetType)) {
            // bumpPostLikeCount(0) = UPDATE post SET like_count=GREATEST(0,like_count+0) WHERE id=?
            // 影响 0 行说明没这个 id（帖不存在）。这是干净的存在性探针，不用旁证。
            if (reactionMapper.bumpPostLikeCount(targetId, 0) == 0) {
                throw new ApiException(ApiStatus.POST_NOT_FOUND_REACTION);
            }
        } else if (TARGET_COMMENT.equals(targetType)) {
            if (commentMapper.selectById(targetId) == null) {
                throw new ApiException(ApiStatus.COMMENT_NOT_FOUND);
            }
        } else {
            throw new ApiException(ApiStatus.REACTION_TARGET_NOT_FOUND);
        }

        // —— INSERT IGNORE，靠 affectedRows 判加/删 ——
        ReactionDO reaction = new ReactionDO();
        reaction.setUserId(currentUserId);
        reaction.setTargetType(targetType);
        reaction.setTargetId(targetId);
        reaction.setCreatedAt(now);
        int inserted = reactionMapper.insertIgnore(reaction);

        boolean active;
        if (inserted == 1) {
            // 新赞：计数 +1
            active = true;
            if (TARGET_POST.equals(targetType)) reactionMapper.bumpPostLikeCount(targetId, 1);
            else reactionMapper.bumpCommentLikeCount(targetId, 1);
        } else {
            // 已赞过 → 取消：删那条记录 + 计数 -1
            active = false;
            reactionMapper.delete(Wrappers.<ReactionDO>lambdaQuery()
                    .eq(ReactionDO::getUserId, currentUserId)
                    .eq(ReactionDO::getTargetType, targetType)
                    .eq(ReactionDO::getTargetId, targetId));
            if (TARGET_POST.equals(targetType)) reactionMapper.bumpPostLikeCount(targetId, -1);
            else reactionMapper.bumpCommentLikeCount(targetId, -1);
        }

        long count = countReaction(targetType, targetId);
        return new ToggleStateResponse(active, count);
    }

    /**
     * 收藏 toggle（只针对帖）。逻辑与点赞同构，复用同一 toggle 模式。
     */
    @Transactional
    public ToggleStateResponse toggleCollection(long currentUserId, long postId) {
        Instant now = Instant.now(clock);

        // 校验帖存在：bumpCollectionCount(0) 影响 0 行 = 无此帖。
        if (collectionMapper.bumpPostCollectionCount(postId, 0) == 0) {
            throw new ApiException(ApiStatus.POST_NOT_FOUND_REACTION);
        }

        CollectionRecordDO record = new CollectionRecordDO();
        record.setUserId(currentUserId);
        record.setPostId(postId);
        record.setCreatedAt(now);
        int inserted = collectionMapper.insertIgnore(record);

        boolean active;
        if (inserted == 1) {
            active = true;
            collectionMapper.bumpPostCollectionCount(postId, 1);
        } else {
            active = false;
            collectionMapper.delete(Wrappers.<CollectionRecordDO>lambdaQuery()
                    .eq(CollectionRecordDO::getUserId, currentUserId)
                    .eq(CollectionRecordDO::getPostId, postId));
            collectionMapper.bumpPostCollectionCount(postId, -1);
        }

        long count = collectionMapper.selectCount(
                Wrappers.<CollectionRecordDO>lambdaQuery().eq(CollectionRecordDO::getPostId, postId));
        return new ToggleStateResponse(active, count);
    }

    /** 当前用户是否已赞某目标 + 该目标总赞数（给前端渲染按钮状态用）。 */
    public ToggleStateResponse getReactionState(long currentUserId, String targetType, long targetId) {
        boolean active = reactionMapper.selectCount(
                Wrappers.<ReactionDO>lambdaQuery()
                        .eq(ReactionDO::getUserId, currentUserId)
                        .eq(ReactionDO::getTargetType, targetType)
                        .eq(ReactionDO::getTargetId, targetId)) > 0;
        return new ToggleStateResponse(active, countReaction(targetType, targetId));
    }

    /** 当前用户是否已收藏某帖 + 该帖总收藏数（给前端刷新后恢复按钮状态用）。 */
    public ToggleStateResponse getCollectionState(long currentUserId, long postId) {
        boolean active = collectionMapper.selectCount(
                Wrappers.<CollectionRecordDO>lambdaQuery()
                        .eq(CollectionRecordDO::getUserId, currentUserId)
                        .eq(CollectionRecordDO::getPostId, postId)) > 0;
        long count = collectionMapper.selectCount(
                Wrappers.<CollectionRecordDO>lambdaQuery()
                        .eq(CollectionRecordDO::getPostId, postId));
        return new ToggleStateResponse(active, count);
    }

    private long countReaction(String targetType, long targetId) {
        return reactionMapper.selectCount(
                Wrappers.<ReactionDO>lambdaQuery()
                        .eq(ReactionDO::getTargetType, targetType)
                        .eq(ReactionDO::getTargetId, targetId));
    }
}
