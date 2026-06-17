package com.agentlog.content.api.dto.response;

import com.agentlog.content.infrastructure.persistence.dataobject.DraftBlockDO;
import com.agentlog.content.infrastructure.persistence.dataobject.DraftDO;

import java.util.List;

public record DraftView(
        Long draftId,
        String title,
        String status,
        Long version,
        List<ContentBlockView> blocks
) {

    public static DraftView from(DraftDO draft, List<DraftBlockDO> blockDOs){
        List<ContentBlockView> blocks = blockDOs.stream()
                .map(b -> new ContentBlockView(
                        b.getId(),
                        b.getDisplayOrder(),
                        b.getRenderedContent(),
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
}
