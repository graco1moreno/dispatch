package com.example.dispatch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot启动类
 */
@SpringBootApplication
@EnableScheduling  // 启用定时任务
public class DispatchApplication {
    
    public static void main(String[] args) {
        System.out.println("正在启动电动车换电调度系统...");
        SpringApplication.run(DispatchApplication.class, args);
        System.out.println("电动车换电调度系统启动完成，定时任务已开启（每10分钟执行一次）");
    }
} 