package com.example.ossdoc.global.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.account")
public class AccountPolicyProperties {

    /**
     * 탈퇴 후 같은 Google 계정으로 재가입할 수 있기까지 기다려야 하는 기간입니다.
     * 기본값은 30일입니다.
     */
    private long rejoinWaitDays = 30;
}