package com.personalprojections.locallife.server.common.result;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 通用分页响应包装器，与 {@link Result} 配合使用。
 *
 * <h2>使用方式</h2>
 * <pre>{@code
 *   // Controller 中返回分页数据
 *   PageResult<ShopSearchVO> page = shopSearchService.searchShops(req);
 *   return Result.ok(page);
 *
 *   // 最终 JSON 响应：
 *   // {
 *   //   "code": "OK",
 *   //   "message": "操作成功",
 *   //   "data": {
 *   //     "total": 1000,
 *   //     "pageNumber": 1,
 *   //     "pageSize": 20,
 *   //     "items": [...]
 *   //   },
 *   //   "timestamp": "2026-05-26T20:00:00+08:00"
 *   // }
 * }</pre>
 *
 * <h2>字段命名说明</h2>
 * <ul>
 *   <li>{@code total}：总记录数（ES totalHits 或 MyBatis-Plus page.getTotal()）</li>
 *   <li>{@code pageNumber}：当前页码，从 1 开始（接口规范文档 §5.1）</li>
 *   <li>{@code pageSize}：每页条数</li>
 *   <li>{@code items}：当前页数据列表（用 items 而不是 list/records，语义更清晰）</li>
 * </ul>
 *
 * @param <T> 列表元素类型
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> {

    /**
     * 符合条件的总记录数（用于前端显示「共 X 条」和计算总页数）。
     * 类型用 long，ES totalHits 就是 long 类型，避免强转。
     */
    private long total;

    /**
     * 当前页码，从 1 开始（与请求参数 pageNumber 对应）。
     */
    private int pageNumber;

    /**
     * 每页条数（与请求参数 pageSize 对应）。
     */
    private int pageSize;

    /**
     * 当前页的数据列表。
     * 如果当前页超出范围（pageNumber > 总页数），返回空列表而不是 null。
     */
    private List<T> items;

    /**
     * 静态工厂方法：快速构建分页结果（不用 Builder）。
     *
     * @param total      总记录数
     * @param pageNumber 当前页码（从 1 开始）
     * @param pageSize   每页条数
     * @param items      当前页数据
     * @param <T>        元素类型
     * @return 分页结果对象
     */
    public static <T> PageResult<T> of(long total, int pageNumber, int pageSize, List<T> items) {
        return PageResult.<T>builder()
                .total(total)
                .pageNumber(pageNumber)
                .pageSize(pageSize)
                .items(items)
                .build();
    }
}
