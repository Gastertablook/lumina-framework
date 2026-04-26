CREATE TABLE `kb_document` (
                               `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
                               `doc_name` varchar(255) NOT NULL COMMENT '文档名称',
                               `es_index_name` varchar(100) NOT NULL COMMENT '所属知识库/工作空间名',
                               `es_parent_id` varchar(100) NOT NULL COMMENT '关联的 RAG 核心父文档ID (极其重要)',
                               `file_path` varchar(500) DEFAULT NULL COMMENT '物理文件存储路径/OSS URL',
                               `status` tinyint DEFAULT '1' COMMENT '状态: 1-正常, 0-已删除',
                               `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                               PRIMARY KEY (`id`),
                               UNIQUE KEY `uk_parent_id` (`es_parent_id`) COMMENT '底层文档ID必须唯一'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Lumina 知识库文档管理表';