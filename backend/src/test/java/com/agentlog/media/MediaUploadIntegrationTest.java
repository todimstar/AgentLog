package com.agentlog.media;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import com.agentlog.support.AuthTestSupport;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * L11 媒体上传验收：申请槽 → 直传 MinIO → finalize（PENDING→ACTIVE）。
 *
 * 用真 MinIO（Testcontainers GenericContainer）+ 真 MySQL，端到端验证预签名上传全链路：
 *   后端签发的预签名 PUT URL 能被【独立 HttpClient】直传成功（证明 URL 真有效、MinIO 真收到，
 *   且这个 HttpClient 不带任何 Cookie/Session——印证"文件不经后端、直传对象存储"），
 *   finalize 后 HEAD 到对象、状态转 ACTIVE。
 *
 * 验收映射：
 *   uploadSlotThenDirectPutThenFinalize → 直传 + PENDING→ACTIVE（两个验收项一次走通）
 *   finalizeWithoutUploadFails          → 没传就 finalize → MEDIA_NOT_UPLOADED（HEAD 兜底）
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class MediaUploadIntegrationTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withUrlParam("serverTimezone", "UTC");

    @Container
    static GenericContainer<?> minio = new GenericContainer<>(
            DockerImageName.parse("minio/minio:RELEASE.2025-04-22T22-12-26Z"))
            .withExposedPorts(9000)
            .withEnv("MINIO_ROOT_USER", "agentlog")
            .withEnv("MINIO_ROOT_PASSWORD", "agentlog-secret")
            .withCommand("server /data");

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void mediaProps(DynamicPropertyRegistry reg) {
        reg.add("agentlog.media.endpoint", () -> "http://" + minio.getHost() + ":" + minio.getMappedPort(9000));
        reg.add("agentlog.media.access-key", () -> "agentlog");
        reg.add("agentlog.media.secret-key", () -> "agentlog-secret");
        reg.add("agentlog.media.bucket", () -> "test-bucket");
        reg.add("spring.data.redis.host", redis::getHost);
        reg.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PasswordEncoder passwordEncoder;

    /** 测试前在 MinIO 建 bucket（容器内 exec mc 建）。 */
    private void ensureBucket() throws Exception {
        minio.execInContainer("sh", "-c",
                "mc alias set local http://localhost:9000 agentlog agentlog-secret && mc mb --ignore-existing local/test-bucket");
    }

    private MockHttpSession registerAndLogin(String username) throws Exception {
        return AuthTestSupport.insertUserAndLogin(mockMvc, jdbcTemplate, passwordEncoder,
                username, username + "@test.local");
    }

    @Test
    void uploadSlotThenDirectPutThenFinalize() throws Exception {
        ensureBucket();
        MockHttpSession session = registerAndLogin("mediauser1");

        // 1) 申请上传槽 → 拿 mediaId + 预签名 PUT URL。
        MvcResult slotResult = mockMvc.perform(post("/api/v1/owner/media/upload-slots").session(session)
                        .with(SecurityMockMvcRequestPostProcessors.csrf()).contentType("application/json")
                        .content("{\"originalFilename\":\"a.png\",\"contentType\":\"image/png\",\"declaredSizeBytes\":12}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.method").value("PUT"))
                .andExpect(jsonPath("$.mediaId").exists())
                .andExpect(jsonPath("$.uploadUrl").exists())
                .andReturn();
        JsonNode slot = objectMapper.readTree(slotResult.getResponse().getContentAsString());
        String mediaId = slot.get("mediaId").asText();
        String uploadUrl = slot.get("uploadUrl").asText();

        // 2) 用独立 HttpClient 按预签名 URL 直传字节到 MinIO（模拟前端直传，不经后端、不带 Cookie）。
        byte[] body = "hello-image!".getBytes();   // 12 字节
        HttpResponse<String> putResp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(uploadUrl))
                        .header("Content-Type", "image/png")
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        org.assertj.core.api.Assertions.assertThat(putResp.statusCode()).isEqualTo(200);

        // 3) finalize → HEAD 确认 + PENDING 转 ACTIVE，真实大小回填 12。
        mockMvc.perform(post("/api/v1/owner/media/" + mediaId + "/finalize").session(session)
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mediaId").value(mediaId))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.sizeBytes").value(12));
    }

    /** 走完整"申请槽→直传→finalize"，返回已 ACTIVE 的 mediaId（供设头像用例复用）。 */
    private String uploadAndFinalize(MockHttpSession session) throws Exception {
        MvcResult slotResult = mockMvc.perform(post("/api/v1/owner/media/upload-slots").session(session)
                        .with(SecurityMockMvcRequestPostProcessors.csrf()).contentType("application/json")
                        .content("{\"originalFilename\":\"av.png\",\"contentType\":\"image/png\",\"declaredSizeBytes\":12}"))
                .andExpect(status().isCreated()).andReturn();
        JsonNode slot = objectMapper.readTree(slotResult.getResponse().getContentAsString());
        String mediaId = slot.get("mediaId").asText();
        HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(slot.get("uploadUrl").asText()))
                        .header("Content-Type", "image/png")
                        .PUT(HttpRequest.BodyPublishers.ofByteArray("hello-image!".getBytes()))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        mockMvc.perform(post("/api/v1/owner/media/" + mediaId + "/finalize").session(session)
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isOk());
        return mediaId;
    }

    @Test
    void setAvatarBindsFinalizedMediaToUser() throws Exception {
        // L10-L11 修复的断点：把已 finalize 的媒体绑定到 user_account.avatar_media_public_id。
        // 断言"设头像 → /me 立刻反映"，证明 上传→绑定→展示 这条链彻底接通。
        ensureBucket();
        MockHttpSession session = registerAndLogin("avataruser");
        String mediaId = uploadAndFinalize(session);

        mockMvc.perform(put("/api/v1/web/me/avatar").session(session)
                        .with(SecurityMockMvcRequestPostProcessors.csrf()).contentType("application/json")
                        .content("{\"mediaId\":\"" + mediaId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatarMediaId").value(mediaId));

        // /me 复查：绑定持久化了（刷新仍在）。
        mockMvc.perform(get("/api/v1/web/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatarMediaId").value(mediaId));
    }

    @Test
    void setAvatarRejectsForeignMedia() throws Exception {
        // 只能把"自己上传"的媒体设为头像：拿别人上传的 mediaId → MEDIA_FORBIDDEN（行级授权）。
        ensureBucket();
        MockHttpSession owner = registerAndLogin("mediaowner");
        String mediaId = uploadAndFinalize(owner);

        MockHttpSession attacker = registerAndLogin("mediaattacker");
        mockMvc.perform(put("/api/v1/web/me/avatar").session(attacker)
                        .with(SecurityMockMvcRequestPostProcessors.csrf()).contentType("application/json")
                        .content("{\"mediaId\":\"" + mediaId + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MEDIA_FORBIDDEN"));
    }

    @Test
    void finalizeWithoutUploadFails() throws Exception {
        ensureBucket();
        MockHttpSession session = registerAndLogin("mediauser2");

        MvcResult slotResult = mockMvc.perform(post("/api/v1/owner/media/upload-slots").session(session)
                        .with(SecurityMockMvcRequestPostProcessors.csrf()).contentType("application/json")
                        .content("{\"originalFilename\":\"b.jpg\",\"contentType\":\"image/jpeg\",\"declaredSizeBytes\":100}"))
                .andExpect(status().isCreated()).andReturn();
        String mediaId = objectMapper.readTree(slotResult.getResponse().getContentAsString()).get("mediaId").asText();

        // 没传文件就 finalize → HEAD 找不到对象 → MEDIA_NOT_UPLOADED。
        mockMvc.perform(post("/api/v1/owner/media/" + mediaId + "/finalize").session(session)
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MEDIA_NOT_UPLOADED"));
    }

    @Test
    void rejectsNonImageType() throws Exception {
        MockHttpSession session = registerAndLogin("mediauser3");
        // 非图片类型 → MEDIA_TYPE_NOT_ALLOWED（Service 兜底，不建槽）。
        mockMvc.perform(post("/api/v1/owner/media/upload-slots").session(session)
                        .with(SecurityMockMvcRequestPostProcessors.csrf()).contentType("application/json")
                        .content("{\"originalFilename\":\"x.pdf\",\"contentType\":\"application/pdf\",\"declaredSizeBytes\":100}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MEDIA_TYPE_NOT_ALLOWED"));
    }
}
