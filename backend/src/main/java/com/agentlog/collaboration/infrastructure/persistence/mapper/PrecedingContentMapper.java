package com.agentlog.collaboration.infrastructure.persistence.mapper;

import com.agentlog.collaboration.infrastructure.persistence.dataobject.PrecedingBlockRow;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 「我这一棒之前，文章已经长成什么样」——写作前文的只读查询（L18 补）。
 *
 * <h3>★ 它补的是 ACPP 一个从 L15 就存在、直到 L18 验收时才被发现的缺口</h3>
 * 在这之前，机娘侧一共 7 个端点，<b>没有一个能读到前面已完成棒次的正文</b>。
 * {@code claim lease} 返回的 {@code writingContext} 只有
 * {@code plannedTitle / plannedSummary / sequenceNo / lastCompletedSequence}——
 * 最后那个只告诉它「前面写了 N 棒」，<b>不告诉它写了什么</b>。
 * <p>{@code ClaimLeaseService} 的注释写着「草稿 URL 由主人给，机娘无读权」，
 * 也就是设计上<b>靠主人把前文复制粘贴给下一个 AI</b>。
 *
 * <p>这与「主人是唯一的传递媒介」一脉相承，但<b>代价没人算过</b>：
 * 接力棒是 51 个字符，复制一次不痛；<b>正文是几百上千字，而且每接一棒都要复制一次</b>。
 * 这不是零信任的必要代价，是 L15 定这条时协作还没真正跑起来过。
 *
 * <p>★ 外部佐证：多智能体协作平台 Raft（raft.build）把这件事列为核心卖点——
 * 「新人能从之前工作过的 agent 那里拿到全部上下文」。<b>上下文传递是这类产品的基本盘。</b>
 *
 * <h3>★ 为什么是【只读 SQL 投影】而不是 Facade</h3>
 * {@code draft_block} 在 content 模块。项目的分工按「读/写/派生」分——
 * Facade 的价值是「写要保证事务边界与不变量」，<b>读不改变任何东西</b>（D-05，同本课时间线）。
 */
@Mapper
public interface PrecedingContentMapper {

    /**
     * 取这个协作草稿的全部可见内容块（按文章顺序）。
     *
     * <p>⚠️ 三个约束都写进 SQL，不放到 Java 里：
     * <ul>
     *   <li>{@code is_hidden = FALSE} —— 主人隐藏的块不该被续写参考；</li>
     *   <li>{@code ORDER BY display_order} —— 按<b>文章顺序</b>，不是棒次顺序
     *       （L19 主人可调序，届时两者会分叉）；</li>
     *   <li>{@code LIMIT} —— 防御性上限。不做分页：一篇协作文章的块数是个位数，
     *       分页是过度设计；但没有上限就是没有防线。</li>
     * </ul>
     *
     * @param limit 最多取几块。调用方多取一块用于判断是否被截断
     */
    List<PrecedingBlockRow> selectVisibleBlocks(@Param("draftId") Long draftId,
                                                @Param("limit") int limit);
}
