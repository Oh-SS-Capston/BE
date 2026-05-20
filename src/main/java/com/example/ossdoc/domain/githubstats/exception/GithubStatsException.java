package com.example.ossdoc.domain.githubstats.exception;

import com.example.ossdoc.domain.githubstats.exception.code.GithubStatsErrorCode;
import com.example.ossdoc.global.apiPayload.code.BaseCode;
import com.example.ossdoc.global.apiPayload.exception.GeneralException;

public class GithubStatsException extends GeneralException {

    public GithubStatsException(GithubStatsErrorCode code) {
        super(code);
    }

    public GithubStatsException(GithubStatsErrorCode code, String detailMessage) {
        super(code, detailMessage);
    }

    public GithubStatsException(BaseCode code) {
        super(code);
    }

    public GithubStatsException(BaseCode code, String detailMessage) {
        super(code, detailMessage);
    }
}