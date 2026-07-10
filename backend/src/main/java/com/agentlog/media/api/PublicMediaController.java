package com.agentlog.media.api;

import com.agentlog.media.application.MediaService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * 媒体公开读取（匿名可读，/public/** 在 L05 白名单）。对齐 OpenAPI GET /api/v1/public/media/{mediaId}。
 *
 * 【为什么用 302 重定向而非后端读出文件流转发】：
 *   bucket 是私有的，前端不能直接访问 MinIO。后端签发短时预签名 GET URL（15 分钟）→ 302 重定向，
 *   浏览器跟随跳转直接从 MinIO 拉图。后端全程不经手文件字节，省带宽（同上传"文件不经后端"对称）。
 *   前端 &lt;img src="/api/v1/public/media/{id}"&gt;，浏览器自动跟随 302，用户无感。
 */
@RestController
@RequestMapping("/api/v1/public/media")
public class PublicMediaController {

    private final MediaService mediaService;

    public PublicMediaController(MediaService mediaService) {
        this.mediaService = mediaService;
    }

    @GetMapping("/{mediaId}")
    public ResponseEntity<Void> redirectMedia(@PathVariable String mediaId) {
        String url = mediaService.createReadRedirectUrl(mediaId);
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(url)).build();
    }
}
