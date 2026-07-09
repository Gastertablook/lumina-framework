package com.lumina.mcp.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * Lumina MCP Server 启动类
 *
 * 作为独立服务启动时，需要同时扫描：
 * 1. com.lumina.rag.core — 核心 RAG 引擎的组件
 * 2. com.lumina.mcp.server — MCP 协议的组件
 *
 * 也可以作为依赖库嵌入到 lumina-docs-app 中，
 * 此时只需确保 McpController 被扫描到即可。
 */
@SpringBootApplication
@ComponentScan(basePackages = {
        "com.lumina.rag.core",
        "com.lumina.mcp.server"
})
public class LuminaMcpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(LuminaMcpServerApplication.class, args);
    }
}
