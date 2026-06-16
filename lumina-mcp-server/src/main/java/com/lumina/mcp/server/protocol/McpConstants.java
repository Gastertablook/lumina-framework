package com.lumina.mcp.server.protocol;

/**
 * MCP 协议常量定义
 * 参考：https://spec.modelcontextprotocol.io/
 */
public class McpConstants {

    // ==================== MCP 标准方法 ====================

    /** 获取服务器信息 */
    public static final String METHOD_SERVER_INFO = "server/info";

    /** 获取工具列表 */
    public static final String METHOD_TOOLS_LIST = "tools/list";

    /** 调用工具 */
    public static final String METHOD_TOOLS_CALL = "tools/call";

    /** 获取资源列表 */
    public static final String METHOD_RESOURCES_LIST = "resources/list";

    // ==================== 工具名称 ====================

    /** 知识库检索工具 */
    public static final String TOOL_RETRIEVE = "lumina_retrieve";

    // ==================== 错误码 ====================

    public static final int ERROR_PARSE_ERROR = -32700;
    public static final int ERROR_INVALID_REQUEST = -32600;
    public static final int ERROR_METHOD_NOT_FOUND = -32601;
    public static final int ERROR_INVALID_PARAMS = -32602;
    public static final int ERROR_INTERNAL = -32603;
    public static final int ERROR_TOOL_NOT_FOUND = -32001;
    public static final int ERROR_TOOL_EXECUTION = -32002;

    // ==================== SSE 事件类型 ====================

    public static final String SSE_EVENT_MESSAGE = "message";
    public static final String SSE_EVENT_ERROR = "error";
    public static final String SSE_EVENT_ENDPOINT = "endpoint";
}
