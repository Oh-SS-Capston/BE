# Oh! SS Backend

> Repository Static Analysis Engine + Local Open-weight LLM Pipeline

Oh! SS Backend는 GitHub Repository의 Source Code와 구조를 분석하여 **Directory, Symbol, Relationship, Class Map, Public API, Rule, Evidence, License 등의 분석 산출물**을 생성하는 Backend Analysis Engine입니다.

정적 분석 결과를 기반으로 **Ollama에서 실행되는 Qwen3.5 9B**가 개발자용 설명 및 문서 산출물을 생성합니다.

프로젝트 전체 소개: https://github.com/Oh-SS-Capston
Oh! SS Frontend: https://github.com/Oh-SS-Capston/FE

---

# Analysis Pipeline

```text
GitHub Repository
        │
        ▼
Repository Collection
        │
        ▼
Build Analysis
        │
        ▼
Source / Bytecode Extraction
        │
        ▼
Static Analysis
(JavaParser / ASM)
        │
        ▼
Symbol & Relationship Extraction
        │
        ▼
GraphStore
        │
        ▼
Cluster / Structure Analysis
        │
        ▼
Public API
        │
        ▼
Rule / Evidence
        │
        ▼
LLM
(Ollama + Qwen3.5 9B)
        │
        ▼
Structured Artifacts
        │
        ▼
REST API
```

---

# Core Analysis

## Static Analysis

Java Repository 분석에 다음 기술을 활용합니다.

* JavaParser Symbol Solver
* ASM

Source 및 Bytecode에서 Symbol과 관계 정보를 추출하여 Repository 구조 분석의 기반 데이터를 생성합니다.

---

## Graph Analysis

추출된 코드 관계를 GraphStore에 구조화하고 Graph 기반 분석에 활용합니다.

* Class Relationship
* Class Map
* Cluster
* Subsystem Structure

Graph Clustering에는 Leiden Network Analysis를 활용합니다.

---

## Public API

Repository의 Public API와 관련 Class / Method 정보를 분석하고 구조화된 Artifact로 제공합니다.

---

## Rule & Evidence

프로젝트에서 확인되는 Rule 정보를 추출하고 관련 Source / Document Evidence와 연결합니다.

---

# LLM Pipeline

## Default Provider

기본 LLM Provider는:

```text
ollama
```

기본 모델은:

```text
qwen3.5:9b
```

입니다.

설정:

```yaml
ossdoc:
  llm:
    enabled: true
    provider: ollama
```

---

## Local Open-weight Model

Oh! SS는 기본 AI 분석 단계에서 상용 API 대신 로컬 Ollama Model Server를 사용합니다.

```text
Structured Analysis Result
          ↓
       Ollama
          ↓
     Qwen3.5 9B
          ↓
LLM Analysis Artifacts
```

Qwen3.5 9B의 공식 라이선스는 Apache License 2.0입니다.

---

## Provider Selection

Repository 분석 Run 생성 시 `llmProvider`를 선택할 수 있습니다.

지원 Provider:

```text
ollama
claude
```

요청에서 Provider를 지정하지 않으면 Backend의 기본 설정인 `ollama`를 사용합니다.

기본 출품 및 서비스 구성은 **Ollama + Qwen3.5 9B**입니다.

---

## Scenario Generation

Scenario 생성 과정에서는 LLM이 전체 구조를 임의로 생성하지 않습니다.

Backend가 정적 분석 결과를 기반으로 Scenario Seed를 먼저 생성한 뒤, LLM은 해당 골격의 설명 필드를 생성합니다.

```text
Static Analysis
      ↓
Core Method Analysis
      ↓
Scenario Seed
      ↓
Qwen3.5 9B
      ↓
Narrative Fields
      ↓
Quality Gate
```

이를 통해 근거 없는 Scenario 구조 생성을 줄이고 분석 결과와 모델 출력 사이의 연결을 유지합니다.

---

## Reproducibility

Ollama 생성 설정에 Seed를 지정할 수 있으며 기본 설정에서는 고정 Seed를 사용합니다.

이를 통해 동일한 입력과 동일한 설정에 대해 모델 변동을 줄이고 Prompt 및 Parameter 변경 효과를 비교할 수 있도록 구성했습니다.

---

# Docker

Docker Compose에는 다음 서비스가 포함됩니다.

```text
redis
ollama
ollama-model-init
app
```

`ollama-model-init`은 최초 실행 시 다음 모델을 준비합니다.

```text
qwen3.5:9b
```

실행:

```bash
docker compose up --build
```

기본적으로 Ollama는 Compose 내부 Network에서 접근하며 Application에서는:

```text
http://ollama:11434
```

를 사용합니다.

모델 데이터는 Docker Volume에 저장되므로 Container를 다시 생성할 때마다 모델을 다시 다운로드하지 않습니다.

---

# Requirements

* Java 21
* PostgreSQL
* Docker / Docker Compose 권장
* Qwen3.5 9B 실행에 필요한 충분한 Memory

검증 환경에서는 Qwen3.5 9B가 `num_ctx=32768` 설정에서 약 7.2GB의 Memory를 사용했습니다.

---

# Local Run

Windows:

```powershell
.\gradlew.bat bootRun
```

macOS / Linux:

```bash
./gradlew bootRun
```

---

# Test

Windows:

```powershell
.\gradlew.bat clean test
```

macOS / Linux:

```bash
./gradlew clean test
```

---

# Build

Windows:

```powershell
.\gradlew.bat clean bootJar
```

macOS / Linux:

```bash
./gradlew clean bootJar
```

---

# Environment

주요 기본 환경 변수:

```env
SPRING_PROFILES_ACTIVE=local

DB_URL=jdbc:postgresql://localhost:5432/ossdoc_db
DB_USER=postgres
DB_PW=

REDIS_HOST=localhost
REDIS_PORT=6379

LLM_ENABLED=true
LLM_PROVIDER=ollama

OLLAMA_BASE_URL=http://localhost:11434
OLLAMA_MODEL=qwen3.5:9b

GOOGLE_CLIENT_ID=
GOOGLE_CLIENT_SECRET=
```

Docker Compose 환경에서는 Application의 Ollama 주소가 자동으로:

```text
http://ollama:11434
```

로 설정됩니다.

---

# Known Limitations

* LLM 단계의 처리 시간이 길 수 있습니다.
* 하나의 LLM Run이 Worker를 장시간 점유하는 동안 후속 Job이 대기할 수 있습니다.
* 일부 Scenario `evidenceLinks`에서 `evidenceId`가 비어 있을 수 있습니다.
* Ollama Model Server가 실행되지 않는 경우 LLM 단계는 실패하고 Run이 `PARTIAL_SUCCESS`가 될 수 있습니다.
* 최초 Docker 실행 시 Qwen3.5 9B 모델을 내려받는 과정이 필요합니다.

---

# Tech Stack

* Java 21
* Spring Boot 4.0.2
* JavaParser Symbol Solver 3.28.0
* ASM 9.9.1
* Leiden Network Analysis 1.3.0
* PostgreSQL
* Spring Data JPA
* QueryDSL
* Redis
* AWS S3
* Ollama
* Qwen3.5 9B
* SpringDoc OpenAPI
* Spring Security
* OAuth2 / JWT
* Docker
* Docker Compose

---

# API Documentation

Backend 실행 후:

```text
http://localhost:8080/swagger-ui/index.html
```

---

# License

Oh! SS Backend is licensed under the **Apache License 2.0**.

See [`LICENSE`](./LICENSE) for details.

Third-party dependencies and AI models remain subject to their respective licenses.
See [`THIRD_PARTY_LICENSES.md`](./THIRD_PARTY_LICENSES.md).
