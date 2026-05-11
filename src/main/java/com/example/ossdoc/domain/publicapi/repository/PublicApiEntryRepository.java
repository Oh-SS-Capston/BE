package com.example.ossdoc.domain.publicapi.repository;

import com.example.ossdoc.domain.publicapi.entity.PublicApiEntry;
import com.example.ossdoc.domain.publicapi.entity.PublicApiEntryId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
/* graphstore의 symbol 중에서, 어떤게 공개 API인지 알려주는 데이터 공급 역할
*  1. ProjectedNode.publicApi 설정,
*  2. subsystem entry symbol 판단,
*  3. ranking의 apiScore 반영
*  4. subsystem 이름/설명 품질 향상
*  즉, 해당 run에서 어떤 symbol이 공개 API인지 조회해서, cluster/ranking 후처리에 활용하게 해주는 repository
*  */
public interface PublicApiEntryRepository extends JpaRepository<PublicApiEntry, PublicApiEntryId> {
    /**
     * 특정 run에 저장된 공개 API 엔트리 전체를 조회한다.
     */
    List<PublicApiEntry> findAllByRun_RunId(String runId);

    /**
     * 특정 run에 저장된 공개 API 엔트리를 전부 삭제한다.
     */
    void deleteByRun_RunId(String runId);
}
