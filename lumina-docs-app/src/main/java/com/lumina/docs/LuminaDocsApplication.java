package com.lumina.docs;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Lumina 知识库应用启动类
 * 注意：scanBasePackages = {"com.lumina"} 会自动把咱们轮子里的 Core 组件扫进来！
 */
@SpringBootApplication(scanBasePackages = {"com.lumina"})
public class LuminaDocsApplication {
    public static void main(String[] args) {
        SpringApplication.run(LuminaDocsApplication.class, args);
    }
}