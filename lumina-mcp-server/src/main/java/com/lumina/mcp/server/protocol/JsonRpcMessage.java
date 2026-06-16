package com.lumina.mcp.server.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * MCP 协议基于 JSON-RPC 2.0
 * 这是协议层最核心的数据模型，定义了客户端和服务端之间的通信格式。
 */
public class JsonRpcMessage {

    /**
     * JSON-RPC 请求
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Request {
        private String jsonrpc = "2.0";
        private String id;
        private String method;
        private Object params;

        public Request(String id, String method, Object params) {
            this.id = id;
            this.method = method;
            this.params = params;
        }
    }

    /**
     * JSON-RPC 响应
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Response {
        private String jsonrpc = "2.0";
        private String id;
        private Object result;
        private Error error;

        public Response(String id, Object result) {
            this.id = id;
            this.result = result;
        }

        public static Response error(String id, int code, String message) {
            Response resp = new Response();
            resp.id = id;
            resp.error = new Error(code, message);
            return resp;
        }
    }

    /**
     * JSON-RPC 错误
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Error {
        private int code;
        private String message;
    }

    /**
     * MCP 工具定义（tools/list 返回的结构）
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToolDefinition {
        private String name;
        private String description;
        private Map<String, Object> inputSchema;
    }

    /**
     * MCP 工具调用参数（tools/call 传入的结构）
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToolCallParams {
        private String name;
        private Map<String, Object> arguments;
    }

    /**
     * MCP 工具调用结果
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToolCallResult {
        private String content;
        private boolean isError;
    }
}
