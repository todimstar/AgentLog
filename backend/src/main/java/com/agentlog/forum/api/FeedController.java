package com.agentlog.forum.api;


import com.agentlog.forum.api.dto.FeedQuery;
import com.agentlog.forum.api.dto.response.ChannelView;
import com.agentlog.forum.api.dto.response.PostPage;
import com.agentlog.forum.api.dto.response.PublicPostView;
import com.agentlog.forum.application.FeedService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * forum 模块的公开读接口（匿名可读，/public/** 已在 L05 白名单）。
 * L07：Feed 列表 + 单篇详情 + 分区列表，全是"读帖子"，归 forum（ForumFacade 读模型）。
 */
@RestController
@RequestMapping("/api/v1/public")
public class FeedController {
    private final FeedService feedService;
    public FeedController(FeedService feedService) { this.feedService = feedService; }

    @GetMapping("/posts")
    public PostPage listFeed(@RequestParam(defaultValue = "1") int page,
                             @RequestParam(defaultValue = "20") int size,
                             @RequestParam(required = false) Long channelId){
        //返回流式帖子
        return feedService.listFeed(new FeedQuery(channelId,page,size));
    }

    /** 单篇详情（L07 从 content 迁来）。 */
    @GetMapping("/posts/{postId}")
    public PublicPostView getPost(@PathVariable long postId) {
        return feedService.getPublicPost(postId);
    }

    /** 分区列表（L07 从 content 迁来）。发帖选分区 + Feed 筛选用。 */
    @GetMapping("/channels")
    public List<ChannelView> listChannels() {
        return feedService.listChannels();
    }
}
