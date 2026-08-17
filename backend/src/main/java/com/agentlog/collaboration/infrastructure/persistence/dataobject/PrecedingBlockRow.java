package com.agentlog.collaboration.infrastructure.persistence.dataobject;

/**
 * 前文块的只读投影（L18 补）：来自 <b>content 模块</b>的 {@code draft_block} + 作者展示名。
 *
 * <h3>★ 为什么读 {@code draft_block.rendered_content} 而不是 {@code contribution.raw_content}</h3>
 * {@code APPROVAL_RECORD} 冻结了两层结构：
 * <pre>
 *   contribution.raw_content   = 机娘交上来的原文，【永不覆盖】——不可变原始层
 *   draft_block.rendered_content = 文章【现在】的样子，主人润色只改这里——可编辑渲染层
 * </pre>
 * <b>续写要基于「文章现在是什么样」，不是「当初交了什么」。</b>
 * 主人润色过第 1 棒之后，第 2 棒必须看到润色后的版本——否则它会基于一段
 * <b>已经不存在的文字</b>往下写，越写越偏。
 *
 * <p>连带两条：{@code is_hidden=true} 的块要<b>排除</b>（主人隐藏它就是不要它出现在文章里，
 * 机娘也不该基于它续写）；排序按 <b>{@code display_order}</b> 而非棒次——
 * L19 之后主人可以调序，那时"第几棒写的"与"在文章里排第几"就不再一致了。
 */
public class PrecedingBlockRow {

    private Integer displayOrder;
    private String authorType;
    private String authorName;
    private String sourceTool;
    private String content;

    public Integer getDisplayOrder() {
        return displayOrder;
    }

    public void setDisplayOrder(Integer displayOrder) {
        this.displayOrder = displayOrder;
    }

    public String getAuthorType() {
        return authorType;
    }

    public void setAuthorType(String authorType) {
        this.authorType = authorType;
    }

    public String getAuthorName() {
        return authorName;
    }

    public void setAuthorName(String authorName) {
        this.authorName = authorName;
    }

    public String getSourceTool() {
        return sourceTool;
    }

    public void setSourceTool(String sourceTool) {
        this.sourceTool = sourceTool;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}
