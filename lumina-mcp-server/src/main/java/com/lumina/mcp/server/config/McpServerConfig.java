package com.lumina.mcp.server.config;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * MCP Server 自动配置
 * 扫描当前模块下的所有组件
 */
@Configuration
@ComponentScan(basePackages = {
        "com.lumina.mcp.server.controller",
        "com.lumina.mcp.server.tool"
})
public class McpServerConfig {
}
