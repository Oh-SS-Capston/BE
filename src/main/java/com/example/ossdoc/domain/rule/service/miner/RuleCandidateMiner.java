package com.example.ossdoc.domain.rule.service.miner;

import com.example.ossdoc.domain.rule.enums.RuleCandidateKind;
import com.example.ossdoc.domain.run.entity.RepoRun;

public interface RuleCandidateMiner {

    RuleCandidateKind supports();

    int mine(RepoRun run);
}