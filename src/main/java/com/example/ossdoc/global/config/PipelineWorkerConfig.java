package com.example.ossdoc.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/*
 * DB 기반 파이프라인 worker를 주기적으로 실행하기 위한 설정입니다.
 */
@Configuration
@EnableScheduling
public class PipelineWorkerConfig {
}