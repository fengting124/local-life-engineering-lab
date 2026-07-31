package com.personalprojections.locallife.copilot.domain.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.personalprojections.locallife.copilot.audit.ToolAuditService;
import com.personalprojections.locallife.copilot.domain.dto.CouponTemplateSnapshot;
import com.personalprojections.locallife.copilot.ratelimit.ToolRateLimiter;
import com.personalprojections.locallife.copilot.rbac.RbacContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class CopilotCouponSchemaContractIntegrationTest {

    private static final String SIGNING_SECRET = "local-life-mcp-context-secret";
    private static final long USER_ID = 910000000001L;
    private static final long MERCHANT_ID = 920000000001L;
    private static final long SHOP_ID = 930000000001L;
    private static final long COUPON_TEMPLATE_ID = 940000000001L;
    private static final long SECKILL_SESSION_ID = 950000000001L;
    private static final long USER_COUPON_ID = 960000000001L;
    private static final int REMAIN_STOCK = 37;
    private static final Path REPOSITORY_ROOT = findRepositoryRoot();

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("local_life")
            .withUsername("root")
            .withPassword("123456");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> String.join(",",
                filesystemLocation("local-life-server/src/main/resources/db/migration"),
                filesystemLocation("local-life-copilot/src/main/resources/db/migration")));
    }

    @Autowired
    private CopilotCouponMapper couponMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ToolRateLimiter rateLimiter;

    @MockitoBean
    private ToolAuditService auditService;

    @BeforeEach
    void seedCouponContract() {
        when(rateLimiter.isAllowed(anyString())).thenReturn(true);

        jdbcTemplate.update("DELETE FROM user_coupon WHERE id = ?", USER_COUPON_ID);
        jdbcTemplate.update("DELETE FROM seckill_session WHERE id = ?", SECKILL_SESSION_ID);
        jdbcTemplate.update("DELETE FROM coupon_template WHERE id = ?", COUPON_TEMPLATE_ID);
        jdbcTemplate.update("DELETE FROM shop WHERE id = ?", SHOP_ID);
        jdbcTemplate.update("DELETE FROM merchant WHERE id = ?", MERCHANT_ID);
        jdbcTemplate.update("DELETE FROM user WHERE id = ?", USER_ID);

        jdbcTemplate.update("""
                INSERT INTO user(id, mobile, nickname)
                VALUES (?, '19900000001', 'coupon-contract-user')
                """, USER_ID);
        jdbcTemplate.update("""
                INSERT INTO merchant(id, user_id, merchant_name, status)
                VALUES (?, ?, 'Coupon Contract Merchant', 'APPROVED')
                """, MERCHANT_ID, USER_ID);
        jdbcTemplate.update("""
                INSERT INTO shop(id, merchant_id, shop_name, status)
                VALUES (?, ?, 'Coupon Contract Shop', 'ONLINE')
                """, SHOP_ID, MERCHANT_ID);
        jdbcTemplate.update("""
                INSERT INTO coupon_template(
                    id, shop_id, coupon_name, discount_type, discount_value,
                    min_order_amount, total_stock, remain_stock, status
                ) VALUES (?, ?, 'Contract Coupon', 'CASH', 2000, 5000, 100, ?, 'ACTIVE')
                """, COUPON_TEMPLATE_ID, SHOP_ID, REMAIN_STOCK);
        jdbcTemplate.update("""
                INSERT INTO seckill_session(
                    id, coupon_template_id, seckill_stock, begin_time, end_time, session_status
                ) VALUES (?, ?, 50, NOW() - INTERVAL 1 HOUR, NOW() + INTERVAL 1 HOUR, 'ACTIVE')
                """, SECKILL_SESSION_ID, COUPON_TEMPLATE_ID);
        jdbcTemplate.update("""
                INSERT INTO user_coupon(
                    id, user_id, coupon_template_id, seckill_session_id,
                    coupon_status, received_at, expire_at
                ) VALUES (?, ?, ?, ?, 'UNUSED', NOW(), NOW() + INTERVAL 7 DAY)
                """, USER_COUPON_ID, USER_ID, COUPON_TEMPLATE_ID, SECKILL_SESSION_ID);
    }

    @AfterEach
    void clearRbacContext() {
        RbacContext.clear();
    }

    @Test
    void mapperReadsPhysicalRemainStock() {
        CouponTemplateSnapshot coupon = couponMapper.selectCouponTemplateById(COUPON_TEMPLATE_ID);

        assertThat(coupon).isNotNull();
        assertThat(coupon.getRemainingStock()).isEqualTo(REMAIN_STOCK);
    }

    @Test
    void merchantListMapperPreservesRemainingStockAlias() {
        List<CouponTemplateSnapshot> coupons =
                couponMapper.selectCouponTemplatesByMerchant(MERCHANT_ID, "ACTIVE");

        assertThat(coupons).singleElement().satisfies(coupon -> {
            assertThat(coupon.getCouponTemplateId()).isEqualTo(COUPON_TEMPLATE_ID);
            assertThat(coupon.getRemainingStock()).isEqualTo(REMAIN_STOCK);
        });
    }

    @Test
    void migratedSchemaUsesRemainStockOnly() {
        Integer physicalColumn = columnCount("remain_stock");
        Integer nonexistentAliasColumn = columnCount("remaining_stock");

        assertThat(physicalColumn).isEqualTo(1);
        assertThat(nonexistentAliasColumn).isZero();
    }

    @Test
    void case32SingleCouponMcpPathReturnsRemainingStock() throws Exception {
        String response = mockMvc.perform(signedMcpRequest(
                        "admin", null,
                        """
                        {"jsonrpc":"2.0","id":"case-32","method":"tools/call","params":{"name":"coupon_policy_lookup","arguments":{"coupon_template_id":"%d"}}}
                        """.formatted(COUPON_TEMPLATE_ID)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode payload = toolPayload(response);
        assertThat(payload.path("coupon_template_id").asLong()).isEqualTo(COUPON_TEMPLATE_ID);
        assertThat(payload.path("remaining_stock").asInt()).isEqualTo(REMAIN_STOCK);
    }

    @Test
    void case37MerchantCouponListMcpPathReturnsRemainingStock() throws Exception {
        String response = mockMvc.perform(signedMcpRequest(
                        "merchant", MERCHANT_ID,
                        """
                        {"jsonrpc":"2.0","id":"case-37","method":"tools/call","params":{"name":"coupon_policy_lookup","arguments":{"status":"ACTIVE"}}}
                        """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode payload = toolPayload(response);
        assertThat(payload.path("merchant_id").asText()).isEqualTo(String.valueOf(MERCHANT_ID));
        assertThat(payload.path("count").asInt()).isEqualTo(1);
        assertThat(payload.path("coupons").get(0).path("remaining_stock").asInt())
                .isEqualTo(REMAIN_STOCK);
    }

    private Integer columnCount(String columnName) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'coupon_template'
                  AND column_name = ?
                """, Integer.class, columnName);
    }

    private JsonNode toolPayload(String responseBody) throws Exception {
        JsonNode response = objectMapper.readTree(responseBody);
        assertThat(response.path("error").isMissingNode()).isTrue();
        String text = response.at("/result/content/0/text").asText();
        assertThat(text).isNotBlank();
        return objectMapper.readTree(text);
    }

    private MockHttpServletRequestBuilder signedMcpRequest(
            String role,
            Long merchantId,
            String body
    ) throws Exception {
        String userId = String.valueOf(USER_ID);
        String merchant = merchantId == null ? "" : String.valueOf(merchantId);
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        MockHttpServletRequestBuilder request = post("/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-User-Id", userId)
                .header("X-User-Role", role)
                .header("X-Agent-Timestamp", timestamp)
                .header("X-Agent-Signature", hmac(userId + "\n" + role + "\n" + merchant + "\n" + timestamp))
                .content(body);
        return merchantId == null ? request : request.header("X-Merchant-Id", merchant);
    }

    private static String hmac(String canonical) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SIGNING_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            hex.append(String.format("%02x", value));
        }
        return hex.toString();
    }

    private static String filesystemLocation(String relativePath) {
        return "filesystem:" + REPOSITORY_ROOT.resolve(relativePath).toAbsolutePath().normalize();
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            if (Files.exists(current.resolve("local-life-server/src/main/resources/db/migration"))
                    && Files.exists(current.resolve("local-life-copilot/src/main/resources/db/migration"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("cannot locate repository migration directories");
    }
}
