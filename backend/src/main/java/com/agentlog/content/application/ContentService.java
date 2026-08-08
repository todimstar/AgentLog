package com.agentlog.content.application;

import com.agentlog.content.ContentFacade;
import com.agentlog.content.api.dto.request.CreateAgentDraftRequest;
import com.agentlog.content.api.dto.request.CreateOwnerDraftRequest;
import com.agentlog.content.api.dto.response.AuthorView;
import com.agentlog.content.api.dto.response.DraftView;
import com.agentlog.content.api.dto.request.PublishDraftRequest;
import com.agentlog.content.api.dto.response.PublishDraftResponse;
import com.agentlog.content.domain.AuthorType;
import com.agentlog.shared.security.AgentIdentity;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ContentService implements ContentFacade {

    /**
     * user_account.status / agent_account.status 的 ACTIVE 值。
     * 跨模块只读投影直查约定：不 import identity 模块的枚举（模块间零 Java 依赖），
     * 此字面量须与 identity 的 UserStatus.ACTIVE / AgentAccountStatus.ACTIVE 对齐
     * （同 forum 模块 FeedService 的同名常量）。
     */
    private static final String AUTHOR_STATUS_ACTIVE = "ACTIVE";

    private final ForumChannelMapper forumChannelMapper;
    private final PostMapper postMapper;
    private final ContributionMapper contributionMapper;
    private final DraftMapper draftMapper;
    private final DraftBlockMapper draftBlockMapper;
    private final Clock clock;
    private final PostVersionMapper postVersionMapper;
    private final PostVersionBlockMapper postVersionBlockMapper;
    private final DraftAuthorMapper draftAuthorMapper;

    public ContentService(
            ForumChannelMapper forumChannelMapper,
            PostMapper postMapper,
            ContributionMapper contributionMapper,
            DraftMapper draftMapper,
            DraftBlockMapper draftBlockMapper,
            Clock clock,
            PostVersionMapper postVersionMapper,
            PostVersionBlockMapper postVersionBlockMapper,
            DraftAuthorMapper draftAuthorMapper) {
        this.forumChannelMapper = forumChannelMapper;
        this.postMapper = postMapper;
        this.contributionMapper = contributionMapper;
        this.draftMapper = draftMapper;
        this.draftBlockMapper = draftBlockMapper;
        this.clock = clock;
        this.postVersionMapper = postVersionMapper;
        this.postVersionBlockMapper = postVersionBlockMapper;
        this.draftAuthorMapper = draftAuthorMapper;
    }

    /**
     * 主人建草稿（web Session，author_type=OWNER）。薄封装：拼一个 OWNER 作者维度，调公共内核。
     */
    @Transactional
    public DraftView createOwnerDraft(long currentUserId, CreateOwnerDraftRequest request) {
        DraftAuthor author = DraftAuthor.owner(currentUserId);
        return createDraftInternal(author, request.title(), request.channelId(),
                request.content(), request.summary(), request.declaredExternalAiContent());
    }

    /**
     * 机娘投稿建草稿（L14·Chain 3，author_type=AGENT）。薄封装：从 agent 令牌身份拼 AGENT 作者维度，调公共内核。
     *
     * 关键：草稿归属键 owner_user_id 填【机娘背后的主人】（principal.ownerUserId），
     * 于是这篇稿天然落进该主人的草稿箱——只有他能在 /owner/drafts 里看、审、发（机娘无 publish）。
     * declaredExternalAiContent 固定 false：机娘投稿的 AI 参与标识留 L20 主人审稿时推导（本课不碰）。
     */
    @Transactional
    public DraftView createAgentDraft(AgentIdentity principal, CreateAgentDraftRequest request) {
        DraftAuthor author = DraftAuthor.agent(
                principal.agentAccountId(), principal.ownerUserId(),
                principal.sourceTool(), principal.clientRunId());
        return createDraftInternal(author, request.title(), request.channelId(),
                request.content(), request.summary(), false);
    }

    /**
     * 建草稿公共内核：建 Post 指针 + Contribution(不可变原始) + Draft 头 + DraftBlock。
     * owner/agent 两条投稿路共用，差别只在【作者维度】(DraftAuthor)——谁写的、用什么工具、哪次运行、归属谁。
     */
    @Transactional
    protected DraftView createDraftInternal(DraftAuthor author, String title, Long channelId,
                                            String content, String summary,
                                            boolean declaredExternalAiContent) {
        CreatedDraft created = createDraftCore(author, title, channelId, content, summary,
                declaredExternalAiContent, null, null);
        return DraftView.from(created.draft(), List.of(created.block()),
                lookupBlockAuthors(List.of(created.block())));
    }

    /**
     * 建草稿的真正内核（L16 从 createDraftInternal 里抽出来，逻辑一字未改）。
     *
     * 为什么要抽：ACPP 的首棒 submit 也要走这套「建 post + contribution + draft + block」，
     * 但它需要拿到四张表各自的 <b>id</b> 去回填协作关联，而 DraftView 是展示模型、拿不到。
     * 于是内核返回 {@link CreatedDraft}（四个 DO），两个投稿入口各自 {@code DraftView.from(...)}。
     *
     * @param acppSessionId 协作会话 id，非协作投稿传 null
     * @param acppTicketId  协作席位 id，非协作投稿传 null
     */
    private CreatedDraft createDraftCore(DraftAuthor author, String title, Long channelId,
                                         String content, String summary,
                                         boolean declaredExternalAiContent,
                                         Long acppSessionId, Long acppTicketId) {
        Instant now = Instant.now(clock);

        ForumChannelDO channel = forumChannelMapper.selectById(channelId);
        if(channel == null){
            throw new ApiException(ApiStatus.CHANNEL_NOT_FOUND);
        }

        //先帖子指针库占位
        PostDO post = new PostDO();
        post.setOwnerUserId(author.ownerUserId());
        post.setChannelId(channelId);
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
        // ACPP 关联（L16 起）：非协作投稿传 null；协作 submit 传 session/ticket。
        // 这两列 V005 建表时就预留了（注释写「ACPP 用」），V012 建了外键，到这里才第一次真正写值。
        // 写上之后 contribution 既是内容的不可变原始层，也是「这段字是哪次协作的第几棒写的」的溯源锚点。
        contribution.setSessionId(acppSessionId);
        contribution.setTicketId(acppTicketId);
        contribution.setAuthorType(author.authorType().getCode());
        contribution.setAuthorUserId(author.authorUserId());
        contribution.setAuthorAgentId(author.authorAgentId());
        contribution.setSourceTool(author.sourceTool());
        contribution.setClientRunId(author.clientRunId());
        contribution.setRawContent(content);  //content原始备份
        contribution.setCreatedAt(now);
        contributionMapper.insert(contribution);

        //草稿头
        DraftDO draft = new DraftDO();
        draft.setPostId(post.getId());
        draft.setOwnerUserId(author.ownerUserId());
        draft.setTitle(title);
        draft.setSummary(summary);    //可选字段
        draft.setChannelId(channelId);
        draft.setStatus(DraftStatus.EDITABLE.getCode());
        draft.setDeclaredExternalAiContent(declaredExternalAiContent);
        draft.setVersion(0L);
        draft.setCreatedAt(now);
        draft.setUpdatedAt(now);
        draftMapper.insert(draft);

        //draft_block草稿块，指回draft和contribution，顺序创建
        DraftBlockDO block = new DraftBlockDO();
        block.setDraftId(draft.getId());
        block.setContributionId(contribution.getId());
        block.setAuthorType(contribution.getAuthorType());//这才是沉淀于contribution
        block.setAuthorUserId(author.authorUserId());
        block.setAuthorAgentId(author.authorAgentId());
        block.setSourceTool(author.sourceTool());  //供 ContentBlockView 展示工具来源
        block.setDisplayOrder(0);   //特定字段主人写法
        block.setRenderedContent(content);
        block.setIsHidden(false);
        block.setVersion(0L);
        block.setCreatedAt(now);
        block.setUpdatedAt(now);
        draftBlockMapper.insert(block);


        return new CreatedDraft(post, contribution, draft, block);
    }

    /** 建草稿内核的产出：四张表各自那一行。返回 DO 而非 DraftView —— 调用方要的是 id，视图各自拼。 */
    private record CreatedDraft(PostDO post, ContributionDO contribution, DraftDO draft, DraftBlockDO block) {
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  ContentFacade 实现 —— 跨模块【写】的唯一入口（L16 · ADR-0006 · DRIFT D-16）
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * {@inheritDoc}
     *
     * <p>实现要点：
     * <ol>
     *   <li><b>不自开事务边界</b>（用 MANDATORY 而非 REQUIRED）—— 必须跑在调用方
     *       collaboration 的 submit 事务里。若谁忘了开事务就调它，宁可当场抛异常，
     *       也不要出现「贡献写进去了、席位没落 DONE」这种半截状态。
     *       这是把「必须同事务」这条约束<b>交给容器强制</b>，而不是靠注释提醒。</li>
     *   <li>首棒复用建草稿内核；后续棒只追加 contribution + block。</li>
     * </ol>
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public AppendResult appendAgentContribution(AppendCommand command) {
        DraftAuthor author = DraftAuthor.agent(
                command.agentAccountId(), command.ownerUserId(),
                command.sourceTool(), command.clientRunId());

        // —— 首棒：整套建出来（post 指针 + 不可变贡献 + 草稿头 + 第一块正文）——
        if (command.draftId() == null) {
            CreatedDraft created = createDraftCore(author, command.title(), command.channelId(),
                    command.content(), command.summary(), false,
                    command.sessionId(), command.ticketId());
            return new AppendResult(created.post().getId(), created.draft().getId(),
                    created.contribution().getId(), created.block().getId(), 0);
        }

        // —— 后续棒：草稿已经在了，只往后面接一段 ——
        Instant now = Instant.now(clock);
        DraftDO draft = draftMapper.selectById(command.draftId());
        if (draft == null) {
            throw new ApiException(ApiStatus.DRAFT_NOT_FOUND);
        }
        // 行级授权：这篇草稿必须属于机娘背后的那个主人。
        // 理论上 collaboration 侧已经校验过（session.owner_user_id），这里是【纵深防御】——
        // Facade 是公开入口，不能假设每个调用方都做对了。
        if (!Objects.equals(draft.getOwnerUserId(), command.ownerUserId())) {
            throw new ApiException(ApiStatus.DRAFT_FORBIDDEN);
        }

        ContributionDO contribution = new ContributionDO();
        contribution.setSessionId(command.sessionId());
        contribution.setTicketId(command.ticketId());
        contribution.setAuthorType(AuthorType.AGENT.getCode());
        contribution.setAuthorAgentId(command.agentAccountId());
        contribution.setSourceTool(command.sourceTool());
        contribution.setClientRunId(command.clientRunId());
        contribution.setRawContent(command.content());   // 不可变原始层，永不覆盖
        contribution.setCreatedAt(now);
        contributionMapper.insert(contribution);

        // 新块排在最后。这里「查最大值再 +1」是安全的：调用点已通过 submit 的 attempt 闸门，
        // 而因果链是串行的（后序票必须等前序 DONE 才能领租约），同一篇草稿不可能有两棒同时提交。
        DraftBlockDO last = draftBlockMapper.selectOne(
                Wrappers.<DraftBlockDO>lambdaQuery()
                        .eq(DraftBlockDO::getDraftId, draft.getId())
                        .orderByDesc(DraftBlockDO::getDisplayOrder)
                        .last("LIMIT 1"));
        int nextOrder = (last == null) ? 0 : last.getDisplayOrder() + 1;

        DraftBlockDO block = new DraftBlockDO();
        block.setDraftId(draft.getId());
        block.setContributionId(contribution.getId());
        block.setAuthorType(AuthorType.AGENT.getCode());
        block.setAuthorAgentId(command.agentAccountId());
        block.setSourceTool(command.sourceTool());
        block.setDisplayOrder(nextOrder);
        block.setRenderedContent(command.content());     // 可编辑渲染层（L19 主人润色只改这一层）
        block.setIsHidden(false);
        block.setVersion(0L);
        block.setCreatedAt(now);
        block.setUpdatedAt(now);
        draftBlockMapper.insert(block);

        // 草稿头的 updated_at 要跟着动，否则主人的草稿箱按更新时间排序会看不出「刚被续写过」。
        draft.setUpdatedAt(now);
        draftMapper.updateById(draft);

        return new AppendResult(draft.getPostId(), draft.getId(),
                contribution.getId(), block.getId(), nextOrder);
    }

    /**
     * 建草稿的【作者维度】参数对象。把"谁写的"从投稿主流程里收敛出来，owner/agent 各拼一个。
     *   owner：author_type=OWNER，authorUserId=当前登录用户，agent 维度全 null，归属自己。
     *   agent：author_type=AGENT，authorAgentId=代入的机娘，带 source_tool/client_run_id，归属机娘背后的主人。
     */
    private record DraftAuthor(
            AuthorType authorType,
            Long authorUserId,
            Long authorAgentId,
            String sourceTool,
            String clientRunId,
            Long ownerUserId) {

        static DraftAuthor owner(long currentUserId) {
            return new DraftAuthor(AuthorType.OWNER, currentUserId, null, null, null, currentUserId);
        }

        static DraftAuthor agent(Long agentAccountId, Long ownerUserId, String sourceTool, String clientRunId) {
            return new DraftAuthor(AuthorType.AGENT, null, agentAccountId, sourceTool, clientRunId, ownerUserId);
        }
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

        return DraftView.from(draft,blocks,lookupBlockAuthors(blocks));
    }

    /**
     * 批量把草稿块上的作者 id 翻译成 AuthorView（防 N+1：两条 IN 查询封顶，不逐块查）。
     *
     * 两类作者分开查再并进同一张表——键带类型前缀（见 {@link AuthorView#keyOf}），
     * 因为 user_account.id 与 agent_account.id 各自独立编号，不加前缀会撞（userId=1 vs agentId=1）。
     */
    private Map<String, AuthorView> lookupBlockAuthors(List<DraftBlockDO> blocks) {
        Set<Long> userIds = blocks.stream().map(DraftBlockDO::getAuthorUserId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> agentIds = blocks.stream().map(DraftBlockDO::getAuthorAgentId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (userIds.isEmpty() && agentIds.isEmpty()) {
            return Map.of();
        }

        Map<String, AuthorView> index = new HashMap<>();
        if (!userIds.isEmpty()) {
            for (DraftAuthorRow row : draftAuthorMapper.selectOwnerAuthorsByUserIds(userIds)) {
                index.put("U:" + row.getUserId(),
                        AUTHOR_STATUS_ACTIVE.equals(row.getStatus())
                                ? AuthorView.owner(row.getUserId(), row.getUsername(), row.getAvatarMediaId())
                                : AuthorView.deletedOwner(row.getUserId()));
            }
        }
        if (!agentIds.isEmpty()) {
            for (DraftAuthorRow row : draftAuthorMapper.selectAgentAuthorsByAgentIds(agentIds)) {
                index.put("A:" + row.getAgentId(),
                        AUTHOR_STATUS_ACTIVE.equals(row.getStatus())
                                ? AuthorView.agent(row.getAgentId(), row.getUsername(), row.getAvatarMediaId())
                                : AuthorView.deletedAgent(row.getAgentId()));
            }
        }
        return index;
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
            // 作者维度四件套整体搬运：OWNER 稿是 (OWNER, userId, null, null)，
            // AGENT 稿是 (AGENT, null, agentId, sourceTool)。少搬一个就会产出自相矛盾的快照
            // （L14 端到端实测抓到：本循环原写于 L06 只有 OWNER 时，漏了 agent 两项）。
            vb.setAuthorType(b.getAuthorType());
            vb.setAuthorUserId(b.getAuthorUserId());
            vb.setAuthorAgentId(b.getAuthorAgentId());
            vb.setSourceTool(b.getSourceTool());
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
}
