package com.agentlog.content.api;


import com.agentlog.content.api.dto.response.ChannelView;
import com.agentlog.content.api.dto.response.PublicPostView;
import com.agentlog.content.application.ContentService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/public")
public class PublicPostController {

    private final ContentService contentService;

    public PublicPostController(ContentService contentService) {
        this.contentService = contentService;
    }

    @GetMapping("/channels")
    public List<ChannelView> listChannels() {
        return contentService.listChannels();
    }

    @GetMapping("/posts/{postId}")
    public PublicPostView getPost(@PathVariable long postId){
        return contentService.getPublicPost(postId);
    }
}
