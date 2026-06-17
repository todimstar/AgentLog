package com.agentlog.content.application;

import com.agentlog.content.api.dto.request.CreateOwnerDraftRequest;
import com.agentlog.content.api.dto.response.DraftView;
import com.agentlog.content.api.dto.request.PublishDraftRequest;
import com.agentlog.content.api.dto.response.PublicPostView;
import com.agentlog.content.api.dto.response.PublishDraftResponse;
import com.agentlog.content.domain.AuthorType;
import com.agentlog.content.domain.ContentOrigin;
import com.agentlog.content.domain.DraftStatus;
import com.agentlog.content.domain.ModerationStatus;
import com.agentlog.content.domain.PostVisibility;
import com.agentlog.content.infrastructure.persistence.dataobject.*;
import com.agentlog.content.infrastructure.persistence.mapper.*;
import com.agentlog.shared.error.ApiException;
import com.agentlog.shared.error.ApiStatus;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Service
public class ContentService {

    private final ForumChannelMapper forumChannelMapper;
    private final PostMapper postMapper;
    private final ContributionMapper contributionMapper;
    private final DraftMapper draftMapper;
    private final DraftBlockMapper draftBlockMapper;
    private final Clock clock;
    private final PostVersionMapper postVersionMapper;
    private final PostVersionBlockMapper postVersionBlockMapper;

    public ContentService(
            ForumChannelMapper forumChannelMapper,
            PostMapper postMapper,
            ContributionMapper contributionMapper,
            DraftMapper draftMapper,
            DraftBlockMapper draftBlockMapper,
            Clock clock,
            PostVersionMapper postVersionMapper,
            PostVersionBlockMapper postVersionBlockMapper) {
        this.forumChannelMapper = forumChannelMapper;
        this.postMapper = postMapper;
        this.contributionMapper = contributionMapper;
        this.draftMapper = draftMapper;
        this.draftBlockMapper = draftBlockMapper;
        this.clock = clock;
        this.postVersionMapper = postVersionMapper;
        this.postVersionBlockMapper = postVersionBlockMapper;
    }

    @Transactional
    public DraftView createOwnerDraft(long currentUserId, CreateOwnerDraftRequest request) {
        Instant now = Instant.now(clock);

        //
        ForumChannelDO channel = forumChannelMapper.selectById(request.channelId());
        if(channel == null){
            throw new ApiException(ApiStatus.CHANNEL_NOT_FOUND);
        }

        //先帖子指针库占位
        PostDO post = new PostDO();
        post.setOwnerUserId(currentUserId);
        post.setChannelId(request.channelId());
        post.setVisibilityStatus(PostVisibility.DRAFT_ONLY.getCode());
        post.setIterationCount(0);
        post.setViewCount(0L);
        post.setLikeCount(0L);
        post.setCommentCount(0L);
        post.setCollectionCount(0L);
        post.setHotScore(java.math.BigDecimal.ZERO);
        post.setIsPinned(false);
        post.setIsEssence(false);
        post.setVersion(0L);
        post.setCreatedAt(now);
        post.setUpdatedAt(now);
        postMapper.insert(post);

        ContributionDO contribution = new ContributionDO();
        contribution.setAuthorType(AuthorType.OWNER.getCode());
        contribution.setAuthorUserId(currentUserId);
        contribution.setRawContent(request.content());  //content原始备份
        contribution.setCreatedAt(now);
        contributionMapper.insert(contribution);

        //草稿头
        DraftDO draft = new DraftDO();
        draft.setPostId(post.getId());
        draft.setOwnerUserId(currentUserId);
        draft.setTitle(request.title());
        draft.setSummary(request.summary());    //可选字段
        draft.setChannelId(request.channelId());
        draft.setStatus(DraftStatus.EDITABLE.getCode());
        draft.setDeclaredExternalAiContent(request.declaredExternalAiContent());
        draft.setVersion(0L);
        draft.setCreatedAt(now);
        draft.setUpdatedAt(now);
        draftMapper.insert(draft);

        //draft_block草稿块，指回draft和contribution，顺序创建
        DraftBlockDO block = new DraftBlockDO();
        block.setDraftId(draft.getId());
        block.setContributionId(contribution.getId());
        block.setAuthorType(contribution.getAuthorType());//这才是沉淀于contribution
        block.setAuthorUserId(currentUserId);
        block.setDisplayOrder(0);   //特定字段主人写法
        block.setRenderedContent(request.content());
        block.setIsHidden(false);
        block.setVersion(0L);
        block.setCreatedAt(now);
        block.setUpdatedAt(now);
        draftBlockMapper.insert(block);


        return DraftView.from(draft, List.of(block));
    }

    //查草稿，自带userId和draftId
    public DraftView getDraft(long currentUserId,long draftId){
        DraftDO draft = draftMapper.selectById(draftId);
        if(draft == null) throw new ApiException(ApiStatus.DRAFT_NOT_FOUND);

        //行级归属校验
        if(!draft.getOwnerUserId().equals(currentUserId)){
            throw new ApiException(ApiStatus.DRAFT_FORBIDDEN);
        }

        //超级拼装，按display_order升序，0-1-2-3
        List<DraftBlockDO> blocks = draftBlockMapper.selectList(
                Wrappers.<DraftBlockDO>lambdaQuery()
                        .eq(DraftBlockDO::getDraftId, draftId)        // WHERE draft_id = ?
                        .orderByAsc(DraftBlockDO::getDisplayOrder)   // ORDER BY display_order ASC
        );

        return DraftView.from(draft,blocks);
    }

    //发布草稿，涉及更新，用乐观锁加事件保证原子性
    @Transactional
    public PublishDraftResponse publish(long currentUserId, long draftId, @Valid PublishDraftRequest request) {
        Instant now = Instant.now(clock);
        //先查草稿指针表，建立对应Post的post_version留存，再查块，复制块内容到version，最后修改各方状态发布

        //查draft指针表是否存在和归属
        DraftDO draft = draftMapper.selectById(draftId);
        if(draft == null)throw new ApiException(ApiStatus.DRAFT_NOT_FOUND);
        if(!draft.getOwnerUserId().equals(currentUserId))throw new ApiException(ApiStatus.DRAFT_FORBIDDEN);
        //更新前乐观锁检查
        if(!draft.getVersion().equals(request.expectedDraftVersion()))throw new ApiException(ApiStatus.DRAFT_VERSION_CONFLICT);
        //查draf状态是否可发布
        if(DraftStatus.PUBLISHED.getCode().equals(draft.getStatus())){
            throw new ApiException(ApiStatus.DRAFT_ALREADY_PUBLISHED);
        }

        //提前备好version创建的信息，算好新版本号和分区快照
        Long currentVersionNo = postVersionMapper.selectCount(
                Wrappers.<PostVersionDO>lambdaQuery().eq(PostVersionDO::getPostId,draft.getPostId())
        );
        int nextVersionNo = currentVersionNo.intValue() + 1;

        ForumChannelDO channel = forumChannelMapper.selectById(draft.getChannelId());
        String channelName = channel != null ? channel.getName() : "";

        //建立post_version对象
        PostVersionDO version = new PostVersionDO();
        version.setPostId(draft.getPostId());
        version.setVersionNo(nextVersionNo);
        version.setTitleSnapshot(draft.getTitle());
        version.setSummarySnapshot(draft.getSummary());
        version.setChannelIdSnapshot(draft.getChannelId());
        version.setChannelNameSnapshot(channelName);
        version.setContentOrigin(ContentOrigin.HUMAN_ONLY.getCode());
        version.setModerationStatus(ModerationStatus.NOT_REQUIRED.getCode());
        version.setOwnerApprovedByUserId(currentUserId);
        version.setOwnerApprovedAt(now);
        version.setPublishedAt(now);
        version.setCreatedAt(now);
        postVersionMapper.insert(version);

        //边查块边复制 Where draftId =,
        List<DraftBlockDO> blocks = draftBlockMapper.selectList(
                Wrappers.<DraftBlockDO>lambdaQuery()
                        .eq(DraftBlockDO::getDraftId, draftId)
                        .eq(DraftBlockDO::getIsHidden, false)
                        .orderByAsc(DraftBlockDO::getDisplayOrder));
        for(DraftBlockDO b : blocks){
            PostVersionBlockDO vb = new PostVersionBlockDO();
            vb.setPostVersionId(version.getId());
            vb.setSourceDraftBlockId(b.getId());
            vb.setContributionId(b.getContributionId());
            vb.setAuthorType(b.getAuthorType());
            vb.setAuthorUserId(b.getAuthorUserId());
            vb.setDisplayOrder(b.getDisplayOrder());
            vb.setContentSnapshot(b.getRenderedContent());
            vb.setCreatedAt(now);
            postVersionBlockMapper.insert(vb);
        }

        //更新对应post指针，扔到mybatisplus里更新
        PostDO post = postMapper.selectById(draft.getPostId());
        post.setCurrentPublishedVersionId(version.getId());
        post.setVisibilityStatus(PostVisibility.PUBLISHED.getCode());
        post.setTitleCache(draft.getTitle());
        post.setSummaryCache(draft.getSummary());
        post.setContentOriginCache(ContentOrigin.HUMAN_ONLY.getCode());
        post.setPublishedAt(now);
        post.setUpdatedAt(now);
        postMapper.updateById(post);

        //更新状态
        draft.setStatus(DraftStatus.PUBLISHED.getCode());
        draft.setUpdatedAt(now);
        draftMapper.updateById(draft);

        return new PublishDraftResponse(post.getId(), version.getId(), nextVersionNo,
                version.getModerationStatus(), post.getVisibilityStatus());

    }


    public PublicPostView getPublicPost(long postId) {
        //从Post指针表往下找到对应正文块，在Post_version表里用PostId找，然后超级拼装

        //找帖子指针及其是否已发布可被查看
        PostDO post = postMapper.selectById(postId);
        if(post == null
            || post.getCurrentPublishedVersionId() == null  //若未发布版本
                ||!PostVisibility.PUBLISHED.getCode().equals(post.getVisibilityStatus())//状态不是已发布状态
        ){
            throw new ApiException(ApiStatus.POST_NOT_FOUND);
        }

        //找到对应版本，用其id再找对应正文块
        PostVersionDO version = postVersionMapper.selectById(post.getCurrentPublishedVersionId());

        List<PostVersionBlockDO> blocks = postVersionBlockMapper.selectList(
                Wrappers.<PostVersionBlockDO>lambdaQuery()
                .eq(PostVersionBlockDO::getPostVersionId,version.getId())
                .orderByAsc(PostVersionBlockDO::getDisplayOrder)
        );

        //超级拼装
        return PublicPostView.form(postId,version,blocks);
    }
}
