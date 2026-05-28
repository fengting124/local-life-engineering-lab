package com.personalprojections.locallife.server.module.search.controller;

import com.personalprojections.locallife.server.common.ratelimit.RateLimit;
import com.personalprojections.locallife.server.common.result.PageResult;
import com.personalprojections.locallife.server.common.result.Result;
import com.personalprojections.locallife.server.module.search.dto.*;
import com.personalprojections.locallife.server.module.search.service.PostSearchService;
import com.personalprojections.locallife.server.module.search.service.ShopSearchService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 搜索模块 Controller，提供门店和笔记的全文搜索接口。
 *
 * <h2>接口路径设计说明</h2>
 * <p>搜索接口放在独立的 {@code /api/v1/search} 路径下，而不是分散到 /shops 和 /posts，
 * 原因：
 * <ul>
 *   <li>搜索功能的复杂度（ES 查询、分页、多维度过滤）远高于普通 CRUD</li>
 *   <li>未来可能新增跨实体联合搜索（「小明饺子馆」同时返回门店 + 相关笔记）</li>
 *   <li>独立路径便于在网关层做 URL 级别的限流（搜索比读详情更耗资源）</li>
 * </ul>
 *
 * <h2>鉴权</h2>
 * <p>搜索接口设计为公开接口（无需登录），游客可以搜索门店和笔记。
 * 在 {@code WebMvcConfig.addWhiteList()} 中配置了白名单，拦截器不校验 Token。
 *
 * <h2>@ModelAttribute 说明</h2>
 * <p>GET 请求的查询参数（Query String）使用 {@code @ModelAttribute} 绑定到对象，
 * 等价于 Spring MVC 的 Query String → JavaBean 自动映射。
 * 示例：{@code GET /api/v1/search/shops?keyword=饺子&lat=30.27&lon=120.15&distanceKm=3}
 * → 自动填充到 {@link ShopSearchRequest} 对象的各字段。
 */
@RestController
@RequestMapping("/api/v1/search")
@RequiredArgsConstructor
public class SearchController {

    private final ShopSearchService shopSearchService;
    private final PostSearchService postSearchService;

    /**
     * 门店搜索接口。
     *
     * <p><b>接口路径：</b> {@code GET /api/v1/search/shops}
     *
     * <p><b>请求参数（Query String）：</b>
     * <pre>
     *   keyword     - 搜索关键词（可选），如 "西湖区好吃的"
     *   lat         - 用户纬度（可选），如 30.273820
     *   lon         - 用户经度（可选），如 120.153559
     *   distanceKm  - 搜索半径（可选，默认 5km），如 3
     *   categoryId  - 分类过滤（可选），如 1
     *   minPrice    - 最低价格（分，可选），如 5000
     *   maxPrice    - 最高价格（分，可选），如 10000
     *   sortBy      - 排序方式（可选，默认 score），可选值：score/distance/price
     *   pageNumber  - 页码（默认 1）
     *   pageSize    - 每页条数（默认 20）
     * </pre>
     *
     * <p><b>响应示例：</b>
     * <pre>{@code
     *   {
     *     "code": "OK",
     *     "data": {
     *       "total": 58,
     *       "pageNumber": 1,
     *       "pageSize": 20,
     *       "items": [
     *         {
     *           "shopId": "1234567890",
     *           "shopName": "小明饺子馆（西湖店）",
     *           "address": "浙江省杭州市西湖区文三路 138 号",
     *           "score": 4.8,
     *           "price": 9900,
     *           "distance": 0.85
     *         }
     *       ]
     *     }
     *   }
     * }</pre>
     *
     * @param req 搜索请求参数（从 Query String 绑定）
     * @return 分页搜索结果
     */
    // 同一 IP 每秒最多搜索 10 次（ES 查询比普通读接口开销大，控制频率）
    @RateLimit(key = "search:shops", limit = 10, window = 1, keyType = RateLimit.KeyType.IP)
    @GetMapping("/shops")
    public Result<PageResult<ShopSearchVO>> searchShops(@Valid @ModelAttribute ShopSearchRequest req) {
        PageResult<ShopSearchVO> result = shopSearchService.searchShops(req);
        return Result.ok(result);
    }

    /**
     * 笔记搜索接口。
     *
     * <p><b>接口路径：</b> {@code GET /api/v1/search/posts}
     *
     * <p><b>请求参数（Query String）：</b>
     * <pre>
     *   keyword    - 搜索关键词（可选），如 "火锅 好吃"
     *   shopId     - 按门店过滤（可选），查看某门店的所有笔记
     *   userId     - 按用户过滤（可选），查看某用户的所有笔记
     *   sortBy     - 排序方式（可选，默认 relevance），可选值：relevance/latest/popular
     *   pageNumber - 页码（默认 1）
     *   pageSize   - 每页条数（默认 20）
     * </pre>
     *
     * <p><b>响应示例：</b>
     * <pre>{@code
     *   {
     *     "code": "OK",
     *     "data": {
     *       "total": 230,
     *       "pageNumber": 1,
     *       "pageSize": 20,
     *       "items": [
     *         {
     *           "postId": "9876543210",
     *           "title": "西湖区最好吃的饺子",
     *           "contentSummary": "昨天去小明饺子馆打了卡，皮薄馅大...",
     *           "shopName": "小明饺子馆（西湖店）",
     *           "likeCount": 128,
     *           "createdAt": "2026-05-26T20:00:00+08:00"
     *         }
     *       ]
     *     }
     *   }
     * }</pre>
     *
     * @param req 搜索请求参数
     * @return 分页搜索结果
     */
    // 同一 IP 每秒最多搜索 10 次（与门店搜索同策略）
    @RateLimit(key = "search:posts", limit = 10, window = 1, keyType = RateLimit.KeyType.IP)
    @GetMapping("/posts")
    public Result<PageResult<PostSearchVO>> searchPosts(@Valid @ModelAttribute PostSearchRequest req) {
        PageResult<PostSearchVO> result = postSearchService.searchPosts(req);
        return Result.ok(result);
    }
}
