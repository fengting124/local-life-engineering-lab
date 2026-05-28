package com.personalprojections.locallife.copilot.tool;

import com.personalprojections.locallife.copilot.mcp.dto.ToolDefinition;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * MCP 工具注册表。
 *
 * <p>Spring 启动时自动收集所有 {@link McpTool} 实现类（通过构造注入），
 * 建立 name → tool 的映射，供 McpController 使用。
 *
 * <h2>注册原理</h2>
 * <p>所有工具类标注 {@code @Component}，Spring 自动发现并注入到 {@code List<McpTool>}。
 * ToolRegistry 遍历列表建立索引，后续 O(1) 按名称查找。
 *
 * <h2>新增工具的步骤</h2>
 * <ol>
 *   <li>在 {@code tool/impl/} 下创建新类实现 {@link McpTool}</li>
 *   <li>标注 {@code @Component}</li>
 *   <li>实现 {@code getName()} / {@code getDefinition()} / {@code execute()}</li>
 *   <li>工具自动注册，无需修改其他代码</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolRegistry {

    private final List<McpTool> tools;

    /** name → tool 映射，构造后不变（线程安全读） */
    private final Map<String, McpTool> toolMap = new LinkedHashMap<>();

    @PostConstruct
    public void init() {
        for (McpTool tool : tools) {
            String name = tool.getName();
            if (toolMap.containsKey(name)) {
                throw new IllegalStateException("工具名称重复: " + name);
            }
            toolMap.put(name, tool);
            log.info("[ToolRegistry] 注册工具: {}", name);
        }
        log.info("[ToolRegistry] 共注册 {} 个工具: {}", toolMap.size(), toolMap.keySet());
    }

    /**
     * 按名称查找工具。
     *
     * @param name 工具名称
     * @return Optional，调用方决定不存在时如何处理（返回 method_not_found 错误）
     */
    public Optional<McpTool> find(String name) {
        return Optional.ofNullable(toolMap.get(name));
    }

    /**
     * 获取所有工具定义（用于 tools/list 响应）。
     *
     * @return 所有已注册工具的 ToolDefinition 列表
     */
    public List<ToolDefinition> listDefinitions() {
        return tools.stream()
                .map(McpTool::getDefinition)
                .toList();
    }

    /** 所有已注册工具（调试用） */
    public Collection<McpTool> all() {
        return toolMap.values();
    }
}
