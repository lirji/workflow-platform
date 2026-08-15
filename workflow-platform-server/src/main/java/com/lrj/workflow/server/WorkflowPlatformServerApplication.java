package com.lrj.workflow.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 流程运行时服务 :8300。嵌入 Flowable process engine(开启 async executor),
 * 承载发起/待办/办理/实例轨迹热路径。扫描 {@code com.lrj.workflow} 以装配 core 的 application services。
 * {@code @EnableScheduling} 驱动 outbox publisher 与 correlation 重试作业。
 */
@EnableScheduling
@SpringBootApplication(scanBasePackages = "com.lrj.workflow")
public class WorkflowPlatformServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(WorkflowPlatformServerApplication.class, args);
    }
}
