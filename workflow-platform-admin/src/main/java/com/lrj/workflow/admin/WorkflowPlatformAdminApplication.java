package com.lrj.workflow.admin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 流程管理服务 :8301。负责流程定义部署/版本管理/审计/租户配置;async executor 关闭(不跑运行时作业)。
 * 扫描 {@code com.lrj.workflow} 以装配 core 的 application services。
 */
@SpringBootApplication(scanBasePackages = "com.lrj.workflow")
public class WorkflowPlatformAdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(WorkflowPlatformAdminApplication.class, args);
    }
}
