package com.agentlog.content.api.dto.response;

import com.agentlog.content.infrastructure.persistence.dataobject.DraftBlockDO;
import com.agentlog.content.infrastructure.persistence.dataobject.DraftDO;

import java.util.List;
import java.util.Map;

public record DraftView(
        Long draftId,
        String title,
        String status,
        Long version,
        List<ContentBlockView> blocks
) {

    /**
     * @param authorIndex 作者查找表，键由 {@link AuthorView#keyOf} 生成（"U:12" / "A:1"）。
     *                    由 Service 批量查好传进来，本方法只做内存拼装（防 N+1）。
     */
    public static DraftView from(DraftDO draft, List<DraftBlockDO> blockDOs,
                                 Map<String, AuthorView> authorIndex){
        List<ContentBlockView> blocks = blockDOs.stream()
                .map(b -> new ContentBlockView(
                        b.getId(),
                        b.getDisplayOrder(),
                        b.getRenderedContent(),
                        resolveAuthor(b, authorIndex),
                        b.getSourceTool()
                )).toList();
        return new DraftView(
                draft.getId(),
                draft.getTitle(),
                draft.getStatus(),
                draft.getVersion(),
                blocks
        );
    }

    /**
     * 块 → 作者。查不到（作者已删/历史数据缺字段）时退化成脱敏占位而非 null——
     * 契约里 AuthorView.deleted 就是为这个场景准备的。作者维度整个为空的老数据才如实返回 null。
     */
    private static AuthorView resolveAuthor(DraftBlockDO b, Map<String, AuthorView> authorIndex) {
        String key = AuthorView.keyOf(b.getAuthorUserId(), b.getAuthorAgentId());
        if (key == null) {
            return null;
        }
        AuthorView found = authorIndex.get(key);
        if (found != null) {
            return found;
        }
        return b.getAuthorAgentId() != null
                ? AuthorView.deletedAgent(b.getAuthorAgentId())
                : AuthorView.deletedOwner(b.getAuthorUserId());
    }
}
