package com.agentlog.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * L15 协作票本地状态单测：落盘往返、"取本地最新尾令牌"的选取规则、损坏文件不拖垮命令。
 *
 * <p>盯的是主人拍板的 P5「两者都支持」里【缺省读本地】那一半：
 * {@code collab join} 不带 {@code --handoff} 时要能自己找到最新的那根悬空令牌。
 * 找错了棒子，接力就接到别的文章上去了——所以选取规则值得单独测。
 *
 * <p>用 {@code @TempDir} 覆盖 {@code user.home} 隔离，绝不碰真实的 {@code ~/.agentlog}。
 */
class TicketStateStoreTest {

    @TempDir
    Path tempHome;

    private String originalUserHome;

    @BeforeEach
    void setUp() {
        originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempHome.toString());
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.home", originalUserHome);
    }

    @Test
    void savesTicketStateInSchemaShape() throws Exception {
        TicketStateStore store = new TicketStateStore();
        Instant now = Instant.parse("2026-07-31T02:00:00Z");
        store.save("PT-aaaaaaaaaaaaaaaa", "CT-bbbbbbbbbbbbbbbb", "READY_TO_WRITE", "handoff_xyz", now);

        Path file = CliPaths.ticketStateFile("CT-bbbbbbbbbbbbbbbb");
        assertThat(file).exists();
        String json = Files.readString(file);
        // 形状对齐 Pack 07-cli/schemas/ticket-state.schema.json 的必填字段。
        assertThat(json)
                .contains("\"postTicket\" : \"PT-aaaaaaaaaaaaaaaa\"")
                .contains("\"ticketCode\" : \"CT-bbbbbbbbbbbbbbbb\"")
                .contains("\"ticketStatus\" : \"READY_TO_WRITE\"")
                .contains("\"nextHandoffToken\" : \"handoff_xyz\"")
                .contains("\"updatedAt\" : \"2026-07-31T02:00:00Z\"")
                // leaseToken 显式为 null（L16 领租约时才填），比省略字段更贴合契约。
                .contains("\"leaseToken\" : null");
    }

    @Test
    void findsLatestHandoffByUpdatedAtNotFileOrder() {
        TicketStateStore store = new TicketStateStore();
        // 故意让【先写入】的那条 updatedAt 更晚——若实现按文件顺序/修改时间选，就会挑错。
        store.save("PT-1", "CT-newer", "WAITING_PREDECESSOR", "handoff_NEW",
                Instant.parse("2026-07-31T10:00:00Z"));
        store.save("PT-1", "CT-older", "DONE", "handoff_OLD",
                Instant.parse("2026-07-30T10:00:00Z"));

        TicketStateStore.LatestHandoff latest = store.findLatestHandoff();
        assertThat(latest).isNotNull();
        assertThat(latest.handoffToken()).isEqualTo("handoff_NEW");
        assertThat(latest.ticketCode()).isEqualTo("CT-newer");
    }

    @Test
    void returnsNullWhenNoLocalHandoffExists() {
        // 全新机器：目录都还没建 → join 应当能给出「要么 --handoff、要么先 start」的指引，而不是崩。
        assertThat(new TicketStateStore().findLatestHandoff()).isNull();
    }

    @Test
    void ignoresTicketsWithoutHandoffToken() {
        TicketStateStore store = new TicketStateStore();
        // 一张没有尾令牌的票（比如将来某种终态）不该被当成可接力的对象。
        store.save("PT-2", "CT-nohandoff", "DONE", null, Instant.now());
        assertThat(store.findLatestHandoff()).isNull();
    }

    @Test
    void survivesCorruptedStateFile() throws Exception {
        TicketStateStore store = new TicketStateStore();
        store.save("PT-3", "CT-good", "READY_TO_WRITE", "handoff_good",
                Instant.now().minus(1, ChronoUnit.MINUTES));
        // 手工塞一个坏文件：单个文件损坏不该让整条命令失败——跳过它，继续找别的。
        Files.writeString(CliPaths.ticketStateDir().resolve("CT-broken.json"), "{ this is not json");

        TicketStateStore.LatestHandoff latest = store.findLatestHandoff();
        assertThat(latest).isNotNull();
        assertThat(latest.handoffToken()).isEqualTo("handoff_good");
    }
}
