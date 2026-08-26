## AI / LLM Components

| Component | Version / Model                  | Purpose                                                  | License                                                           |
| --------- | -------------------------------- | -------------------------------------------------------- | ----------------------------------------------------------------- |
| Ollama    | Deployment version               | Local LLM runtime                                        | Refer to the license distributed with the deployed Ollama version |
| Qwen3.5   | Qwen3.5-9B / Ollama `qwen3.5:9b` | Analysis result explanation and documentation generation | Apache License 2.0                                                |

### Qwen3.5 9B

Oh! SS uses **Qwen3.5 9B** as the default local language model through Ollama.

Official model:

`Qwen/Qwen3.5-9B`

Model license:

`Apache License 2.0`

Qwen3.5 remains the property of its respective authors and is distributed under its original license. Oh! SS does not claim ownership of the model or its weights.

### Provider Compatibility

The Backend also contains an optional Claude provider implementation for development and provider comparison.

The default configuration and competition submission path use:

```text
Ollama + Qwen3.5 9B
```

Third-party services remain subject to the terms and licenses of their respective providers.
