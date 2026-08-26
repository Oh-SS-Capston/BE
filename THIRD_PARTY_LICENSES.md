# Third-Party Licenses

Oh! SS Backend uses third-party open-source software, libraries, runtimes, and AI models.

Each third-party component remains subject to its original copyright and license terms.

## Major Direct Dependencies

| Component | Version / Model | Purpose | License |
|---|---:|---|---|
| Spring Boot | 4.0.2 | Backend framework | Apache-2.0 |
| JavaParser Symbol Solver | 3.28.0 | Java source and symbol analysis | Apache-2.0 / LGPL dual license |
| ASM | 9.9.1 | Java bytecode analysis | BSD-style license |
| CWTS Network Analysis | 1.3.0 | Leiden graph analysis | MIT |
| QueryDSL | 5.0.0 | Type-safe database queries | Apache-2.0 |
| JJWT | 0.12.7 | JWT handling | Apache-2.0 |
| AWS SDK for Java v2 | 2.x | AWS S3 integration | Apache-2.0 |
| PostgreSQL JDBC Driver | Managed dependency | PostgreSQL connectivity | BSD-2-Clause |
| Ollama | Deployment version | Local LLM runtime | MIT |
| Qwen3.5 9B | `qwen3.5:9b` | AI explanation and documentation generation | Apache-2.0 |

---

## AI / LLM Components

### Ollama

Oh! SS uses Ollama as the default local LLM runtime.

Ollama is distributed under the **MIT License**.

Official project:

`ollama/ollama`

### Qwen3.5 9B

Oh! SS uses **Qwen3.5 9B** as the default local language model.

Model:

`Qwen/Qwen3.5-9B`

Ollama model identifier:

`qwen3.5:9b`

Purpose:

- AI-based explanation of structured analysis results
- Scenario narrative generation
- Developer-oriented documentation generation

License:

**Apache License 2.0**

Oh! SS does not claim ownership of the Qwen3.5 model or its weights.

---

## Notes

- Oh! SS does not claim ownership of third-party software, models, or model weights.
- Third-party components remain subject to their respective original licenses.
- The complete Java dependency graph is defined by `build.gradle` and Gradle dependency resolution.
- The final dependency versions should be reviewed before each public release.
