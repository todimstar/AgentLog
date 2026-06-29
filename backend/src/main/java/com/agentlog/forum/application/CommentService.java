package com.agentlog.forum.application;

import com.agentlog.forum.api.dto.request.CreateCommentRequest;
import com.agentlog.forum.api.dto.response.CommentPage;
import com.agentlog.forum.api.dto.response.CommentView;
import com.agentlog.forum.api.dto.response.PageMeta;
import com.agentlog.forum.infrastructure.persistence.dataobject.CommentDO;
import com.agentlog.forum.infrastructure.persistence.dataobject.CommentRow;
import com.agentlog.forum.infrastructure.persistence.mapper.CommentMapper;
import com.agentlog.shared.error.ApiException;
import com.agentlog.shared.error.ApiStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 评论服务 —— L08 二级回复。归 forum 模块（社区互动）。
 *
 * 三个方法对应三个接口：create（发评论）/ listTree（读评论树）/ softDelete（软删）。
 * 本类承载本课三个面试点：禁三级三重防线、软删保楼层、评论树零 N+1。
 */
@Service
public class CommentService {

    private static final String STATUS_VISIBLE = "VISIBLE";
    private static final String STATUS_DELETED = "DELETED";

    private final CommentMapper commentMapper;
    private final Clock clock;

    public CommentService(CommentMapper commentMapper, Clock clock) {
        this.commentMapper = commentMapper;
        this.clock = clock;
    }

    /**
     * 发评论（一级 / 二级二合一）。B站式"逻辑无限回复，物理永远两层"。
     *
     * 禁三级不靠"拒绝"，靠【扁平化】——回复任何评论，新评论永远挂到"目标所属那层楼"的楼主下，
     * depth 永远=2。三级状态根本无法被表达，所以这里没有任何"层数超限"的报错分支。
     *
     *  - parentCommentId == null  → 一级评论：depth=1，root=自己（插入拿到 id 后回填）。
     *  - parentCommentId != null  → 二级回复：depth=2，root=目标所属楼(目标是一级则=目标id，
     *                               目标是二级则=目标的root)，reply_to=被回复的那条（展示"回复@谁"）。
     *
     * depth=2 是后端推导的常量，客户端无法自报——配合 DB 的 CHECK(depth IN (1,2)) 双保险。
     * comment_count 维护：成功插入后 post.comment_count +1（DB 端原子自增，跨模块走自己投影直写物理表）。
     */
    @Transactional
    public CommentView create(long currentUserId, long postId, CreateCommentRequest request) {
        Instant now = Instant.now(clock);

        // 先记账 comment_count +1：0 行说明帖子不存在 → 整笔事务回滚（不会留孤儿评论）。
        if (commentMapper.bumpCommentCount(postId, 1) == 0) {
            throw new ApiException(ApiStatus.POST_NOT_FOUND);
        }

        CommentDO comment = new CommentDO();
        comment.setPostId(postId);
        comment.setAuthorUserId(currentUserId);
        comment.setContent(request.content());
        comment.setStatus(STATUS_VISIBLE);
        comment.setLikeCount(0L);
        comment.setVersion(0L);
        comment.setCreatedAt(now);
        comment.setUpdatedAt(now);

        if (request.parentCommentId() == null) {
            // —— 一级评论 —— depth=1，先插入拿 id，再回填 root=自己。
            comment.setDepth(1);
            comment.setParentCommentId(null);
            comment.setReplyToCommentId(null);
            commentMapper.insert(comment);
            comment.setRootCommentId(comment.getId());
            commentMapper.updateById(comment);
        } else {
            // —— 二级回复 —— 目标必须存在且属于同帖。
            CommentDO target = commentMapper.selectById(request.parentCommentId());
            if (target == null || !target.getPostId().equals(postId)) {
                throw new ApiException(ApiStatus.COMMENT_NOT_FOUND);
            }
            // 扁平化关键：root = 目标所属楼（目标是一级→它自己；目标是二级→它的 root）。
            // 这一步把"回复二级评论"自动收敛成"挂到同一层楼下的另一条二级"，永远不会产生三级。
            Long floor = target.getDepth() == 1 ? target.getId() : target.getRootCommentId();
            comment.setDepth(2);
            comment.setRootCommentId(floor);
            comment.setParentCommentId(target.getId());
            // reply_to：默认回复 target；显式带了 replyToCommentId 就用它（展示"回复@谁"）。
            comment.setReplyToCommentId(
                    request.replyToCommentId() != null ? request.replyToCommentId() : target.getId());
            commentMapper.insert(comment);
        }

        // 回吐刚建的视图：走统一 selectCommentTree 取作者名 + 脱敏逻辑。
        return commentMapper.selectCommentTree(postId).stream()
                .filter(r -> r.getId().equals(comment.getId()))
                .map(CommentView::from)
                .findFirst()
                .orElseThrow(() -> new ApiException(ApiStatus.COMMENT_NOT_FOUND));
    }

    /**
     * 读评论树（匿名可读）。契约要【扁平 items】，组树留前端。
     *
     * 零 N+1：一条 JOIN SQL 把整帖评论 + 作者名拉全（含软删占位行），不逐条查作者、不递归查子回复。
     * 排序在 XML 里按 root_comment_id 聚楼 → 同楼评论天然挨在一起，前端顺序分组成两层即可。
     *
     * V1 不真分页（评论量级小，一次全量）。PageMeta 用 total=列表长度占位，契约结构对齐即可。
     */
    public CommentPage listTree(long postId, int page, int size) {
        List<CommentRow> rows = commentMapper.selectCommentTree(postId);
        List<CommentView> items = rows.stream().map(CommentView::from).toList();
        return new CommentPage(items, new PageMeta(page, size, items.size()));
    }

    /**
     * 软删评论。只改 status=DELETED，不物理删——保住楼层，子回复不变孤儿。
     *
     * 行级授权：只能删自己的评论（authorUserId == 当前登录用户）。
     * comment_count -1：删了的评论不计入帖子评论数（虽留占位行，但不算"有效评论"）。
     * 幂等：已 DELETED 再删，直接返回不重复扣计数。
     */
    @Transactional
    public void softDelete(long currentUserId, long commentId) {
        CommentDO comment = commentMapper.selectById(commentId);
        if (comment == null) {
            throw new ApiException(ApiStatus.COMMENT_NOT_FOUND);
        }
        if (!comment.getAuthorUserId().equals(currentUserId)) {
            throw new ApiException(ApiStatus.COMMENT_FORBIDDEN);
        }
        if (STATUS_DELETED.equals(comment.getStatus())) {
            return; // 幂等：已删则空操作，不重复扣 comment_count
        }

        comment.setStatus(STATUS_DELETED);
        comment.setUpdatedAt(Instant.now(clock));
        commentMapper.updateById(comment); // @Version 乐观锁兜底并发软删

        commentMapper.bumpCommentCount(comment.getPostId(), -1);
    }
}
