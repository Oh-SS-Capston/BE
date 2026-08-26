// domain/run/support/GithubUrlParser.java
package com.example.ossdoc.domain.run.support;

import com.example.ossdoc.domain.run.exception.code.RunErrorCode;
import com.example.ossdoc.domain.run.exception.RunException;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GithubUrlParser {

    // https://github.com/{owner}/{repo}.git or without .git
    private static final Pattern P =
            Pattern.compile("^https?://github\\.com/([^/]+)/([^/]+?)(?:\\.git)?/?$");

    public static GithubRepoRef parse(String repoUrl, String refOrNull) {
        // URL 검증
        Matcher m = P.matcher(repoUrl.trim());
        if (!m.matches()) {
            throw new RunException(RunErrorCode.INVALID_REPO_URL);
        }

        // owner / repo 추출
        String owner = m.group(1);
        String repo = m.group(2);

        // 내부 모델로 변환
        return GithubRepoRef.builder()
                .owner(owner)
                .repo(repo)
                .ref(refOrNull)
                .build();
    }
}