package com.personalprojections.locallife.server.module.shop.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personalprojections.locallife.server.common.exception.BizException;
import com.personalprojections.locallife.server.common.interceptor.AuthInterceptor;
import com.personalprojections.locallife.server.common.ratelimit.RateLimitInterceptor;
import com.personalprojections.locallife.server.common.result.ErrorCode;
import com.personalprojections.locallife.server.module.shop.dto.CreateShopRequest;
import com.personalprojections.locallife.server.module.shop.dto.ShopVO;
import com.personalprojections.locallife.server.module.shop.dto.UpdateShopRequest;
import com.personalprojections.locallife.server.module.shop.service.ShopService;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link ShopController} 切片测试。
 *
 * <h2>为什么选这个 Controller</h2>
 * <p>{@code ShopController} 正是 Bug #3（鉴权白名单按路径排除导致连坐放行）两个真实
 * 触发点之一：{@code POST /api/v1/shops} 和 {@code PUT /api/v1/shops/{shopId}}
 * 曾经被旧版 {@code excludePathPatterns("/api/v1/shops/*")} 一起放行。
 * 鉴权链路本身由 {@code AuthInterceptorTest} 覆盖；这里覆盖的是另一半——
 * 「一旦合法登录用户走到 Controller，参数绑定 / 校验 / 异常映射是否正确」，
 * 这正是 Bug#3 的另一种表现形式（带着合法 Token 访问，因 UserContext 未写入而 500）
 * 所在的层：如果 Service 抛出业务异常，这一层必须把它转换成正确的 HTTP 状态码，
 * 而不是被吞掉变成笼统的 500。
 */
@WebMvcTest(ShopController.class)
class ShopControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ShopService shopService;

    // @WebMvcTest 切片会把 WebMvcConfig（@Configuration + WebMvcConfigurer）和 TraceIdFilter
    // （@Component + Filter）一并装进上下文——前者在 addInterceptors() 里把 AuthInterceptor /
    // RateLimitInterceptor 注册到 "/**"，后者的构造函数依赖 io.micrometer.tracing.Tracer。
    // 也就是说切片测试并不会像直觉认为的那样"只剩 Controller、天然跳过拦截器链"：
    // 这两个拦截器在这里是真实存在、会拦截每一个请求的——这正是写这批测试时踩到的坑：
    // 最初以为它们不会被加载，结果所有受保护端点的用例统统收到 401（公开端点反而全部正常）。
    //
    // AuthInterceptor 的鉴权判断本身已经由 AuthInterceptorTest 用完整端点清单详尽覆盖，
    // 这里没必要也不应该重新搭一套"伪造合法 Redis 会话"的重型基础设施去蒙混过关——
    // 那样会让两层测试在"鉴权该不该通过"这件事上重复判断，且一旦 AuthInterceptor 的
    // Redis key 格式或 LoginUserDTO 形状变化，这边也要跟着改。更干净的隔离方式：
    // 把两个拦截器整体替换成"放行型"替身，让请求畅通无阻地抵达 Controller——
    // 具体见下面 {@link #allowAllRequestsThroughInterceptors()}。
    @MockitoBean
    private Tracer tracer;

    @MockitoBean
    private AuthInterceptor authInterceptor;

    @MockitoBean
    private RateLimitInterceptor rateLimitInterceptor;

    // 第三类"意外住户"更隐蔽：LocalLifeServerApplication 上的 @MapperScan 会注册一个
    // BeanDefinitionRegistryPostProcessor，把 domain.mapper 包下全部 13 个 @Mapper 接口
    // 注册成 MapperFactoryBean——这一步是 @Import 级别的注册，不受 @WebMvcTest 切片
    // 过滤器影响，每个 MapperFactoryBean 在容器刷新时都会被立即实例化并执行
    // checkDaoConfig()，要求存在 SqlSessionFactory/SqlSessionTemplate
    // （而切片上下文不会自动配置 MyBatis，二者都没有）。
    // RETURNS_DEEP_STUBS 让 mock.getConfiguration() 返回另一个深层 stub 而不是 null，
    // 使 checkDaoConfig 内部的 hasMapper()/addMapper() 都能安全地空跑过去，
    // 不触发任何真实 SQL 或数据库连接。
    @MockitoBean(answers = Answers.RETURNS_DEEP_STUBS)
    private SqlSessionTemplate sqlSessionTemplate;

    @BeforeEach
    void allowAllRequestsThroughInterceptors() throws Exception {
        // mock 的 boolean 方法默认返回 false——对 HandlerInterceptor 来说就是"拦截每一个请求"，
        // 必须显式放行两者：AuthInterceptor 先于 RateLimitInterceptor 注册，链上任何一个
        // 返回 false 都会在不写响应体的情况下直接短路整条链，让断言落在空 200 响应上
        // （而不是预期的业务响应），现象会比 401 更难定位。
        when(authInterceptor.preHandle(any(), any(), any())).thenReturn(true);
        when(rateLimitInterceptor.preHandle(any(), any(), any())).thenReturn(true);
    }

    // =====================================================================
    // GET /api/v1/shops —— 搜索门店列表（公开查询）
    // =====================================================================

    @Test
    void searchShops_withCategoryId_passesQueryParamThrough() throws Exception {
        ShopVO shop = ShopVO.builder()
                .shopId("2063848616358965249")
                .shopName("张三的火锅店")
                .categoryId(1)
                .score(new BigDecimal("4.8"))
                .status("ONLINE")
                .build();
        when(shopService.searchShops(eq(1))).thenReturn(List.of(shop));

        mockMvc.perform(get("/api/v1/shops").param("categoryId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data[0].shopId").value("2063848616358965249"))
                .andExpect(jsonPath("$.data[0].shopName").value("张三的火锅店"))
                .andExpect(jsonPath("$.data[0].status").value("ONLINE"));

        verify(shopService).searchShops(1);
    }

    @Test
    void searchShops_withoutCategoryId_passesNullThrough() throws Exception {
        // @RequestParam(required = false) Integer categoryId：不传参数时 Spring 必须绑定为 null，
        // 而不是抛 400 或绑成 0——这正是「方法参数绑定是否正确」的直接验证点
        when(shopService.searchShops(isNull())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/shops"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());

        verify(shopService).searchShops(isNull());
    }

    // =====================================================================
    // GET /api/v1/shops/{shopId} —— 门店详情（公开查询）
    // =====================================================================

    @Test
    void getShopDetail_existingShop_returnsVoFromService() throws Exception {
        ShopVO shop = ShopVO.builder()
                .shopId("2063848616358965249")
                .merchantId("2063844064737886210")
                .shopName("张三的火锅店")
                .address("北京市朝阳区三里屯")
                .status("ONLINE")
                .build();
        when(shopService.getShopDetail(eq(2063848616358965249L))).thenReturn(shop);

        mockMvc.perform(get("/api/v1/shops/2063848616358965249"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shopId").value("2063848616358965249"))
                .andExpect(jsonPath("$.data.merchantId").value("2063844064737886210"))
                .andExpect(jsonPath("$.data.address").value("北京市朝阳区三里屯"));

        verify(shopService).getShopDetail(2063848616358965249L);
    }

    @Test
    void getShopDetail_unknownOrOfflineShop_mapsBizExceptionToShopNotFound400() throws Exception {
        // Service 故意把「不存在」和「未上线」合并成同一个 SHOP_NOT_FOUND（防枚举），
        // 这里验证 Controller/异常处理链路把它正确转换为 400 + 对应 code/message
        when(shopService.getShopDetail(eq(999999L))).thenThrow(new BizException(ErrorCode.SHOP_NOT_FOUND));

        mockMvc.perform(get("/api/v1/shops/999999"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SHOP_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("门店不存在"));
    }

    // =====================================================================
    // POST /api/v1/shops —— 创建门店（Bug#3 曾在此被静默放行）
    // =====================================================================

    private static final String VALID_CREATE_SHOP_BODY = """
            {
              "shopName": "张三的火锅店",
              "categoryId": 1,
              "description": "正宗川味火锅",
              "address": "北京市朝阳区三里屯 123 号",
              "longitude": 116.4551,
              "latitude": 39.9373,
              "phone": "010-12345678",
              "businessHours": "周一至周日 11:00-23:00"
            }
            """;

    private static final String VALID_UPDATE_SHOP_BODY = """
            {
              "shopName": "张三的火锅店",
              "categoryId": 1,
              "address": "北京市朝阳区三里屯 123 号",
              "longitude": 116.4551,
              "latitude": 39.9373
            }
            """;

    @Test
    void createShop_validRequest_bindsAllFieldsAndReturnsCreatedDraftVo() throws Exception {
        ShopVO created = ShopVO.builder()
                .shopId("2063900000000000099")
                .shopName("张三的火锅店")
                .status("DRAFT")
                .build();
        when(shopService.createShop(any(CreateShopRequest.class))).thenReturn(created);

        mockMvc.perform(post("/api/v1/shops")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CREATE_SHOP_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shopId").value("2063900000000000099"))
                .andExpect(jsonPath("$.data.status").value("DRAFT"));

        verify(shopService).createShop(argThat(req ->
                "张三的火锅店".equals(req.getShopName())
                        && req.getCategoryId() == 1
                        && "北京市朝阳区三里屯 123 号".equals(req.getAddress())
                        && new BigDecimal("116.4551").compareTo(req.getLongitude()) == 0
                        && new BigDecimal("39.9373").compareTo(req.getLatitude()) == 0));
    }

    @Test
    void createShop_missingShopName_returns400WithNotBlankMessage() throws Exception {
        String body = """
                {
                  "categoryId": 1,
                  "address": "北京市朝阳区三里屯 123 号",
                  "longitude": 116.4551,
                  "latitude": 39.9373
                }
                """;

        mockMvc.perform(post("/api/v1/shops")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SYS_PARAM_INVALID"))
                .andExpect(jsonPath("$.message").value("门店名称不能为空"));

        verifyShopServiceNeverCreates();
    }

    @Test
    void createShop_longitudeOutOfRange_returns400WithRangeMessage() throws Exception {
        String body = """
                {
                  "shopName": "张三的火锅店",
                  "categoryId": 1,
                  "address": "北京市朝阳区三里屯 123 号",
                  "longitude": 200.0,
                  "latitude": 39.9373
                }
                """;

        mockMvc.perform(post("/api/v1/shops")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SYS_PARAM_INVALID"))
                .andExpect(jsonPath("$.message").value("经度范围 -180.0 ~ 180.0"));

        verifyShopServiceNeverCreates();
    }

    @Test
    void createShop_callerNotApprovedMerchant_mapsBizExceptionTo403Forbidden() throws Exception {
        // 已登录但商家资质未通过：Service 抛 MERCHANT_NOT_APPROVED(403)，
        // 必须原样透传，不能被异常处理器吞成 500（这正是 Bug#3「带 Token 也 500」的镜像场景）
        when(shopService.createShop(any(CreateShopRequest.class)))
                .thenThrow(new BizException(ErrorCode.MERCHANT_NOT_APPROVED));

        mockMvc.perform(post("/api/v1/shops")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CREATE_SHOP_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MERCHANT_NOT_APPROVED"))
                .andExpect(jsonPath("$.message").value("商家资质审核未通过，暂无权限"));
    }

    private void verifyShopServiceNeverCreates() {
        verify(shopService, org.mockito.Mockito.never()).createShop(any());
    }

    // =====================================================================
    // PUT /api/v1/shops/{shopId} —— 更新门店（Bug#3 曾在此被静默放行）
    // =====================================================================

    @Test
    void updateShop_validRequest_bindsPathVariableAndBodyTogether() throws Exception {
        String body = """
                {
                  "shopName": "张三的火锅店（新店名）",
                  "categoryId": 2,
                  "address": "北京市朝阳区三里屯 456 号",
                  "longitude": 116.4551,
                  "latitude": 39.9373
                }
                """;
        ShopVO updated = ShopVO.builder()
                .shopId("2063848616358965249")
                .shopName("张三的火锅店（新店名）")
                .status("ONLINE")
                .build();
        when(shopService.updateShop(eq(2063848616358965249L), any(UpdateShopRequest.class)))
                .thenReturn(updated);

        mockMvc.perform(put("/api/v1/shops/2063848616358965249")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shopName").value("张三的火锅店（新店名）"));

        // 同时校验路径变量 shopId 和请求体字段都被正确解析、一起传给 Service——
        // 这正是 Bug#1（-parameters 缺失导致 @PathVariable 绑定失败）会暴露的断言点
        verify(shopService).updateShop(eq(2063848616358965249L),
                argThat(req -> "张三的火锅店（新店名）".equals(req.getShopName()) && req.getCategoryId() == 2));
    }

    @Test
    void updateShop_notOwnedByCaller_mapsBizExceptionTo403Forbidden() throws Exception {
        when(shopService.updateShop(eq(999999L), any(UpdateShopRequest.class)))
                .thenThrow(new BizException(ErrorCode.SHOP_FORBIDDEN));

        mockMvc.perform(put("/api/v1/shops/999999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_UPDATE_SHOP_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SHOP_FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("无权操作该门店"));
    }

    // =====================================================================
    // PUT /api/v1/shops/{shopId}/status/{online|offline} —— 门店上下线
    // =====================================================================

    @Test
    void onlineShop_delegatesPathVariableAndReturnsUpdatedStatus() throws Exception {
        ShopVO online = ShopVO.builder().shopId("2063848616358965249").status("ONLINE").build();
        when(shopService.onlineShop(eq(2063848616358965249L))).thenReturn(online);

        mockMvc.perform(put("/api/v1/shops/2063848616358965249/status/online"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ONLINE"));

        verify(shopService).onlineShop(2063848616358965249L);
    }

    @Test
    void offlineShop_illegalStatusTransition_mapsBizExceptionTo400() throws Exception {
        when(shopService.offlineShop(eq(2063848616358965249L)))
                .thenThrow(new BizException(ErrorCode.SHOP_STATUS_ILLEGAL));

        mockMvc.perform(put("/api/v1/shops/2063848616358965249/status/offline"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SHOP_STATUS_ILLEGAL"))
                .andExpect(jsonPath("$.message").value("当前门店状态不支持此操作"));
    }
}
