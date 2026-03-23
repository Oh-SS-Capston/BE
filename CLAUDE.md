# CLAUDE.md

This file provides guidance to Claude Code when working with this repository.

## 프로젝트 성격

GitHub OSS 저장소를 commit SHA로 고정하고, run 단위 분석 후 **Semantic Graph** 기반 산출물을 생성하는 재현 가능한 분석 파이프라인 백엔드. 모든 결과물(UML/설명/규칙/시나리오/API Map)은 Graph + Evidence + Confidence 기반에서만 생성.

---

## 절대 규칙

- 동작 중인 파이프라인 흐름을 사용자 요청 없이 변경 금지.
- 도메인 중심 패키지 구조 유지. 리팩토링/패키지 이동/계층 재설계 금지.
- 도메인 enum을 global 패키지로 이동 금지.
- 코드 근거(evidence) 없으면 추정으로 명시하거나 생성하지 않음.
- 설명은 **한국어** 기본. 구현 제안 시 왜/역할/연결관계를 함께 설명.
- 단계별 구현 → 검증 → 연결 선호. 한 번에 큰 수정 지양.

## Entity / DB 저장 규칙

- ERD 설계 완료 상태 → entity 임의 수정·추가 금지.
- Entity 추가 필요 시: ① 말로 이유 설명 → ② 사용자 동의 후 코드 작성.
- persistence 필요 시 DTO/JSON artifact/service 조합으로 우선 해결.
- **DB 저장 코드(save/persist/insert/update)와 기존 저장 흐름 수정은 사용자 명시 요청 시에만 진행.
  필요하다고 판단되면 먼저 이유와 영향 범위를 설명한다**
- 저장 필요 판단 시: 왜/어느 entity/대안을 먼저 말로 추천.

---

## 기술 스택

Web/API: Spring Boot, springdoc | DB: PostgreSQL, Spring Data JPA, QueryDSL | Queue: Redis | Build 분석: Maven/Gradle | AST: JDT | Bytecode: ASM | Diagram: PlantUML(MVP)/Mermaid | Storage: AWS S3

---

## 패키지 구조

```
com.example.ossdoc
├── domain
│   ├── auth / user
│   ├── run / build / extraction
│   ├── graphstore / contract / ranking
│   └── apimap / rule / validation / render / artifact
└── global
    └── apiPayload / config / security / llm / s3
```

미존재 도메인은 사용자 요청 없이 생성하지 않음. 각 도메인은 필요 시 controller, service, facade, dto, exception, enums, repository, entity를 둘 수 있다.
단, 현재 존재하지 않는 도메인/계층/패키지는 사용자 요청 없이 새로 만들지 않는다.

---

## 파이프라인 단계 및 산출물

| 단계 | 주요 산출물 |
|------|------------|
| 1. Job Init — clone, SHA 고정, workspace 생성 | `job_manifest.json` |
| 2. Build/Resolve — Maven/Gradle 감지, classpath, fallback | `build_manifest.json` |
| 3. Fact Extraction — AST+ASM, 미해결 타입 명시 | `facts.json` |
| 4. Graph Build — node/edge 정규화, evidence 연결 | `graph_stats.json` |
| 5. Contract — SPI/DI/Event/Reflection → edge+confidence | `contract_edges.json` |
| 6. Ranking — 중심성·API가중치·2단 클러스터링 | `rankings.json`, `subsystems.json` |
| 7. API Map — entry/extension point, README 보조 점수 | `api_map.json`, `entry_points.json` |
| 8. Rule Mining — 조건문·상태변경·불변조건 패턴 | `rule_candidates.json` |
| 9. LLM Refinement — 구조 산출물 기반 의미 정제만 | `refined_rules.json` 등 |
| 10. Validation Gate — evidence 검사, display policy 산출 | `quality_report.json`, `display_policy.json` |
| 11. Render — display policy 반영, diagram, S3 저장 | `rendered_artifacts/*` |

---

## Core Data Model

**Node**: Module · Package · Type(Class/Interface/Enum/Record) · Method · Field · AnnotationUsage · ResourceFile · ConfigKey · EventType

**Edge**: CONTAINS · EXTENDS · IMPLEMENTS · HAS_FIELD · CALLS · OVERRIDES · BINDS · PROVIDES_SPI · SUBSCRIBES · PUBLISHES · USES_REFLECTION

**Evidence**: file path + startLine/endLine + symbol(`com.foo.Bar#baz`) + snippet + type(AST/bytecode/resource/test/README)

**Confidence**: edge·규칙·시나리오·문장별 근거 강도 → 표시 여부·경고 배너·"추정" 라벨 결정.

---

## 코딩 컨벤션

### Controller (모든 도메인 필수)

```java
@RestController @RequiredArgsConstructor
@RequestMapping("/api/v1/{domain}")
public class XxxController {
    private final XxxFacade xxxFacade;

    @PostMapping("/action")
    public ApiResponse<XxxResponse> action(@Valid @RequestBody XxxRequest req) {
        return ApiResponse.onSuccess(xxxFacade.action(req));
    }
}
```

경로: `/api/v1/` + runs · build · extraction · graphstore · contracts · rankings · apimap · rules · validation · render · artifacts

### 응답 / 예외

```java
return ApiResponse.onSuccess(data);
// 예외: DomainErrorCode implements BaseCode → DomainException extends GeneralException → ExceptionAdvice
```

### Entity 공통

`BaseCreatedEntity` or `BaseAuditedEntity` 상속 · `LocalDateTime` + JPA Auditing · Lombok(`@Getter @NoArgsConstructor @AllArgsConstructor @Builder`) · JSONB: `@JdbcTypeCode(SqlTypes.JSON)`

### Repository

기본: Spring Data JPA | 복잡 조회: QueryDSL | 저장 코드는 사용자 명시 요청 시에만 작성

---

## 빌드 명령어

```bash
./gradlew build | bootRun | test | compileJava | clean build
./gradlew test --tests "com.example.ossdoc.OssdocApplicationTests"
```

---

## Git 컨벤션

브랜치: `{type}/#{issue}` · PR 기준: `develop` · 커밋: `{type}: {desc} (#{issue})` · type: feat/fix/refactor/docs/test/chore
