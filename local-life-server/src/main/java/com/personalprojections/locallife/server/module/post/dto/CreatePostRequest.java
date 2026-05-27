package com.personalprojections.locallife.server.module.post.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 发布探店笔记的请求体。
 *
 * <h2>接口约定</h2>
 * <pre>
 *   POST /api/v1/posts
 *   Authorization: Bearer {token}
 *   Content-Type: application/json
 * </pre>
 *
 * <h2>字段说明</h2>
 * <ul>
 *   <li>{@code shopId}  必传，笔记必须关联一个门店（Service 层会校验门店是否 ONLINE）</li>
 *   <li>{@code title}   可选，允许空字符串，后续可补全</li>
 *   <li>{@code content} 必传，正文不能为空</li>
 *   <li>{@code images}  可选，最多 9 张，URL 列表</li>
 * </ul>
 *
 * <h2>图片上传流程（当前阶段简化）</h2>
 * <p>客户端先调用文件上传接口（OSS 预签名 URL），拿到图片 CDN 地址后，
 * 再把地址放到 images 列表中调用此接口。这样主接口不处理二进制文件，
 * 减小 HTTP 请求体大小，职责清晰。
 */
@Data
public class CreatePostRequest {

    /**
     * 关联门店 ID，必传。
     * Service 层会校验该门店是否存在且为 ONLINE 状态，
     * 不存在或未上线的门店不允许发笔记（防止关联到用户看不见的门店）。
     */
    @NotNull(message = "门店 ID 不能为空")
    @Positive(message = "门店 ID 不合法")
    private Long shopId;

    /**
     * 笔记标题，可选，最多 128 字符。
     * 允许传空字符串，不传默认为 ""（Service 层做 null 转空字符串处理）。
     */
    @Size(max = 128, message = "标题最多 128 字符")
    private String title;

    /**
     * 笔记正文，必传，不能为空白。
     * 当前阶段存纯文本，最多 5000 字符（防止超长内容攻击）。
     */
    @NotBlank(message = "笔记内容不能为空")
    @Size(max = 5000, message = "笔记内容最多 5000 字符")
    private String content;

    /**
     * 图片 URL 列表，可选，最多 9 张。
     * 校验逻辑：列表非 null 时，长度不超过 9，单个 URL 最长 512 字符。
     * 具体格式校验（URL 合法性）在 Service 层做，此处只做基础长度约束。
     */
    @Size(max = 9, message = "最多上传 9 张图片")
    private List<String> images;
}
