package com.personalprojections.locallife.server.module.post.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 发表笔记评论的请求体。
 *
 * <h2>接口约定</h2>
 * <pre>
 *   POST /api/v1/posts/{postId}/comments
 *   Authorization: Bearer {token}
 * </pre>
 *
 * <h2>当前阶段只支持一级评论</h2>
 * <p>parentId 字段预留但当前接口不暴露（Controller 不接收此参数，Service 强制设为 0）。
 * 后续如需支持回复，开放此字段并在 Service 层做父评论存在性校验。
 */
@Data
public class CreateCommentRequest {

    /**
     * 评论内容，必传，不能为空白，最多 512 字符。
     * 512 字符的限制与数据库 VARCHAR(512) 对齐，避免截断。
     */
    @NotBlank(message = "评论内容不能为空")
    @Size(max = 512, message = "评论内容最多 512 字符")
    private String content;
}
