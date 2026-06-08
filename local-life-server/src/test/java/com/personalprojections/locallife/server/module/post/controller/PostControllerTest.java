package com.personalprojections.locallife.server.module.post.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personalprojections.locallife.server.common.exception.BizException;
import com.personalprojections.locallife.server.common.interceptor.AuthInterceptor;
import com.personalprojections.locallife.server.common.ratelimit.RateLimitInterceptor;
import com.personalprojections.locallife.server.common.result.ErrorCode;
import com.personalprojections.locallife.server.module.post.dto.CommentVO;
import com.personalprojections.locallife.server.module.post.dto.CreateCommentRequest;
import com.personalprojections.locallife.server.module.post.dto.CreatePostRequest;
import com.personalprojections.locallife.server.module.post.dto.PostVO;
import com.personalprojections.locallife.server.module.post.service.PostService;
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

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link PostController} 切片测试。
 *
 * <h2>为什么选这个 Controller</h2>
 * <p>{@code PostController} 是 Bug #3 两个真实触发点中的另一个：
 * {@code DELETE /api/v1/posts/{postId}} 和 {@code POST /api/v1/posts/{postId}/comments}
 * 曾经被旧版「按路径排除」的白名单规则一起放行——
 * 这两条写操作路径分别和「公开」的 {@code GET /api/v1/posts/{postId}}、
 * {@code GET /api/v1/posts/{postId}/comments} 共享同一段路径前缀，
 * 但语义截然不同（前者改资源，后者读资源），纯路径匹配无法区分两者。
 *
 * <p>本测试类专门覆盖这两个端点「合法登录用户访问时」的行为：
 * 参数绑定是否正确、Service 抛出的业务异常是否被正确映射成 HTTP 状态码——
 * 这是 Bug#3 在已登录用户身上的另一种表现（UserContext 未写入 → Service 层 NPE → 500）
 * 所在的层。鉴权决策本身（未登录是否被拦）由 {@code AuthInterceptorTest} 覆盖。
 */
@WebMvcTest(PostController.class)
class PostControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private PostService postService;

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

    // 第三类"意外住户"更隐蔽:LocalLifeServerApplication 上的 @MapperScan 会注册一个
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
    // POST /api/v1/posts —— 发布笔记
    // =====================================================================

    @Test
    void publishPost_validRequest_bindsAllFieldsIncludingImageList() throws Exception {
        String body = """
                {
                  "shopId": 2063848616358965249,
                  "title": "周末探店",
                  "content": "这家火锅店真的绝了，强烈推荐毛肚和鸭血",
                  "images": ["https://cdn.example.com/1.jpg", "https://cdn.example.com/2.jpg"]
                }
                """;
        PostVO created = PostVO.builder()
                .postId("2063900000000000088")
                .shopId("2063848616358965249")
                .title("周末探店")
                .content("这家火锅店真的绝了，强烈推荐毛肚和鸭血")
                .status("PUBLISHED")
                .build();
        when(postService.publishPost(any(CreatePostRequest.class))).thenReturn(created);

        mockMvc.perform(post("/api/v1/posts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.postId").value("2063900000000000088"))
                .andExpect(jsonPath("$.data.title").value("周末探店"));

        verify(postService).publishPost(argThat(req ->
                req.getShopId().equals(2063848616358965249L)
                        && "周末探店".equals(req.getTitle())
                        && req.getImages() != null && req.getImages().size() == 2));
    }

    @Test
    void publishPost_blankContent_returns400WithNotBlankMessage() throws Exception {
        String body = """
                {
                  "shopId": 2063848616358965249,
                  "title": "周末探店"
                }
                """;

        mockMvc.perform(post("/api/v1/posts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SYS_PARAM_INVALID"))
                .andExpect(jsonPath("$.message").value("笔记内容不能为空"));

        verify(postService, org.mockito.Mockito.never()).publishPost(any());
    }

    @Test
    void publishPost_missingShopId_returns400WithNotNullMessage() throws Exception {
        String body = """
                {
                  "content": "这家店不错"
                }
                """;

        mockMvc.perform(post("/api/v1/posts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SYS_PARAM_INVALID"))
                .andExpect(jsonPath("$.message").value("门店 ID 不能为空"));
    }

    // =====================================================================
    // GET /api/v1/posts/{postId} —— 笔记详情（公开）
    // =====================================================================

    @Test
    void getPostDetail_existingPost_returnsVoFromService() throws Exception {
        PostVO post = PostVO.builder()
                .postId("2063848808399368193")
                .nickname("测试用户")
                .title("周末探店")
                .likeCount(3)
                .commentCount(1)
                .status("PUBLISHED")
                .build();
        when(postService.getPostDetail(eq(2063848808399368193L))).thenReturn(post);

        mockMvc.perform(get("/api/v1/posts/2063848808399368193"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.postId").value("2063848808399368193"))
                .andExpect(jsonPath("$.data.likeCount").value(3))
                .andExpect(jsonPath("$.data.commentCount").value(1));
    }

    @Test
    void getPostDetail_unknownPost_mapsBizExceptionToPostNotFound400() throws Exception {
        when(postService.getPostDetail(eq(999999L))).thenThrow(new BizException(ErrorCode.POST_NOT_FOUND));

        mockMvc.perform(get("/api/v1/posts/999999"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("POST_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("笔记不存在"));
    }

    // =====================================================================
    // DELETE /api/v1/posts/{postId} —— 删除笔记（Bug#3 曾在此被静默放行）
    // =====================================================================

    @Test
    void deletePost_ownedByCaller_delegatesPathVariableAndReturnsOk() throws Exception {
        mockMvc.perform(delete("/api/v1/posts/2063848808399368193"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(postService).deletePost(2063848808399368193L);
    }

    @Test
    void deletePost_notOwnedByCaller_mapsBizExceptionToPostForbidden403() throws Exception {
        // Service 用 POST_FORBIDDEN 统一表达「不存在」和「无权限」（防枚举），
        // 这里验证该业务异常被正确映射为 403，而不是因 UserContext 缺失而变成 500
        // ——这正是 Bug#3 对「已登录但无权限」用户的真实影响（见类注释）
        doThrow(new BizException(ErrorCode.POST_FORBIDDEN))
                .when(postService).deletePost(eq(888888L));

        mockMvc.perform(delete("/api/v1/posts/888888"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("POST_FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("无权操作该笔记"));
    }

    // =====================================================================
    // POST|DELETE /api/v1/posts/{postId}/likes —— 点赞 / 取消点赞
    // =====================================================================

    @Test
    void likePost_delegatesPathVariableToService() throws Exception {
        mockMvc.perform(post("/api/v1/posts/2063848808399368193/likes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));

        verify(postService).likePost(2063848808399368193L);
    }

    @Test
    void unlikePost_delegatesPathVariableToService() throws Exception {
        mockMvc.perform(delete("/api/v1/posts/2063848808399368193/likes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));

        verify(postService).unlikePost(2063848808399368193L);
    }

    // =====================================================================
    // GET /api/v1/shops/{shopId}/posts —— 门店笔记列表（公开）
    // =====================================================================

    @Test
    void listPostsByShop_returnsListFromService() throws Exception {
        PostVO post = PostVO.builder().postId("2063848808399368193").shopId("2063848616358965249").build();
        when(postService.listPostsByShop(eq(2063848616358965249L))).thenReturn(List.of(post));

        mockMvc.perform(get("/api/v1/shops/2063848616358965249/posts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].postId").value("2063848808399368193"));

        verify(postService).listPostsByShop(2063848616358965249L);
    }

    // =====================================================================
    // POST /api/v1/posts/{postId}/comments —— 发表评论（Bug#3 曾在此被静默放行）
    // =====================================================================

    @Test
    void addComment_validRequest_bindsPathVariableAndBodyTogether() throws Exception {
        CommentVO created = CommentVO.builder()
                .commentId("2063900000000000077")
                .nickname("测试用户")
                .content("看起来好好吃！")
                .build();
        when(postService.addComment(eq(2063848808399368193L), any(CreateCommentRequest.class)))
                .thenReturn(created);

        mockMvc.perform(post("/api/v1/posts/2063848808399368193/comments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\": \"看起来好好吃！\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.commentId").value("2063900000000000077"))
                .andExpect(jsonPath("$.data.content").value("看起来好好吃！"));

        // 同时验证路径变量 postId 和请求体字段都被正确解析、一起传给 Service——
        // 这正是「绑定是否正确」与「鉴权放行后身份是否被正确传递」交汇的断言点
        verify(postService).addComment(eq(2063848808399368193L),
                argThat(req -> "看起来好好吃！".equals(req.getContent())));
    }

    @Test
    void addComment_blankContent_returns400WithNotBlankMessage() throws Exception {
        mockMvc.perform(post("/api/v1/posts/2063848808399368193/comments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SYS_PARAM_INVALID"))
                .andExpect(jsonPath("$.message").value("评论内容不能为空"));

        verify(postService, org.mockito.Mockito.never()).addComment(any(), any());
    }

    // =====================================================================
    // GET /api/v1/posts/{postId}/comments —— 评论列表（公开）
    // =====================================================================

    @Test
    void listComments_returnsListFromService() throws Exception {
        CommentVO comment = CommentVO.builder().commentId("2063900000000000077").content("看起来好好吃！").build();
        when(postService.listComments(eq(2063848808399368193L))).thenReturn(List.of(comment));

        mockMvc.perform(get("/api/v1/posts/2063848808399368193/comments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].commentId").value("2063900000000000077"));
    }

    // =====================================================================
    // DELETE /api/v1/posts/{postId}/comments/{commentId} —— 删除评论
    // =====================================================================

    @Test
    void deleteComment_delegatesBothPathVariablesToService() throws Exception {
        mockMvc.perform(delete("/api/v1/posts/2063848808399368193/comments/2063900000000000077"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));

        // 两个路径变量必须都绑定正确且不能互相错位——多路径变量绑定正是
        // -parameters 编译参数缺失时最容易出错的场景之一（Bug#1 同类问题）
        verify(postService).deleteComment(2063848808399368193L, 2063900000000000077L);
    }
}
