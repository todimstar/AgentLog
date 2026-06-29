package com.agentlog.forum.api;

import com.agentlog.forum.api.dto.request.CreateCommentRequest;
import com.agentlog.forum.api.dto.response.CommentPage;
import com.agentlog.forum.api.dto.response.CommentView;
import com.agentlog.forum.application.CommentService;
import com.agentlog.shared.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * 评论接口。归 forum（社区互动）。Controller 只做 HTTP 编排：取身份 → 调 Service → 返回视图。
 *
 * 路径分两类（对齐 OpenAPI，安全语义不同，不能合并前缀）：
 *  - 读评论树 GET  /api/v1/public/...  → 匿名可读（/public/** 在 L05 白名单）。
 *  - 发/删评论 POST/DELETE /api/v1/web/... → 需登录 + CSRF（其余 /web 走 authenticated）。
 */
@RestController
@RequestMapping("/api/v1")
public class CommentController {

    private final CommentService commentService;

    public CommentController(CommentService commentService) {
        this.commentService = commentService;
    }

    /** 读评论树（匿名）。返回扁平 items，前端按 parentCommentId 组两层树。 */
    @GetMapping("/public/posts/{postId}/comments")
    public CommentPage listComments(@PathVariable long postId,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        return commentService.listTree(postId, page, size);
    }

    /** 发评论（登录）。顶层/回复二合一，由 body 的 parentCommentId 区分。 */
    @PostMapping("/web/posts/{postId}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public CommentView createComment(@PathVariable long postId,
                                     @Valid @RequestBody CreateCommentRequest request) {
        long currentUserId = CurrentUser.requireId();
        return commentService.create(currentUserId, postId, request);
    }

    /** 软删评论（登录，仅作者本人）。改 status 留楼层占位，不物理删。 */
    @DeleteMapping("/web/comments/{commentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteComment(@PathVariable long commentId) {
        long currentUserId = CurrentUser.requireId();
        commentService.softDelete(currentUserId, commentId);
    }
}
