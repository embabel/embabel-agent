# Research Summary: Logic Programming for Grounding Large Language Models

*Compiled 2026-04-10 via OpenAlex academic graph API*

## Overview

This document summarises recent academic papers (2022–2026) on the use of logic
programming paradigms — Answer Set Programming (ASP), Prolog, Datalog, and
probabilistic logic programming — to ground the outputs of large language models
(LLMs), reducing hallucination and enabling verifiable, compositional reasoning.

---

## Tier 1 — Directly On-Topic

### Logic-LM: Empowering Large Language Models with Symbolic Solvers for Faithful Logical Reasoning
- **Authors:** Liangming Pan, Alon Albalak, Xinyi Wang (2023)
- **Citations:** 88
- **DOI:** https://doi.org/10.18653/v1/2023.findings-emnlp.248
- **Summary:** Translates natural language problems into symbolic logic (FOL/Prolog-style),
  feeds them to external solvers, and returns solver outputs back to the LLM. Directly
  addresses hallucination by grounding LLM output in formal reasoning.

---

### Reliable Natural Language Understanding with Large Language Models and Answer Set Programming
- **Authors:** Abhiramon Rajasekharan, Yankai Zeng, Parth Padalkar (2023)
- **Citations:** 17
- **Summary:** Combines GPT-3/ChatGPT with ASP — the LLM extracts facts and commonsense
  knowledge from text, an ASP solver performs sound logical inference, grounding LLM
  conclusions in stable-model semantics.

---

### Leveraging Large Language Models to Generate Answer Set Programs
- **Authors:** Adam Ishay, Zhun Yang, Joohyung Lee (2023)
- **Citations:** 14
- **Summary:** Uses GPT-3/GPT-4 to generate ASP programs from natural language problem
  specifications. Programs are solved by Clingo; the LLM acts as a front-end
  parser/translator and ASP provides grounded, verifiable reasoning.

---

### Integrating Answer Set Programming and Large Language Models for Enhanced Structured Representation of Complex Knowledge in Natural Language
- **Authors:** Mario Alviano, Lorenzo Grillo, Fabrizio Lo Scudo (2024)
- **Citations:** 2
- **Summary:** Combines ASP with LLMs for structured knowledge representation. Each
  paradigm compensates for the other's weaknesses: LLM for language understanding,
  ASP for consistent, grounded inference.

---

### LLASP: Fine-tuning Large Language Models for Answer Set Programming
- **Authors:** Erica Coppolillo, Francesco Calimeri, Giuseppe Manco (2024)
- **Citations:** 1
- **Summary:** Fine-tunes LLMs specifically to produce syntactically and semantically
  correct ASP encodings, closing the gap between language model generation and
  executable logic programs.

---

### Language Models and Logic Programs for Trustworthy Tax Reasoning
- **Authors:** William Jurayj, Nils Holzenberger, Benjamin Van Durme (2026)
- **Citations:** 0 *(very recent — AAAI 2026)*
- **DOI:** https://doi.org/10.1609/aaai.v40i45.41212
- **Summary:** Applies logic programs alongside LLMs for complex tax reasoning, using
  formal rules to ground and verify LLM outputs in a high-stakes domain where
  correctness is critical.

---

### NELLIE: A Neuro-Symbolic Inference Engine for Grounded, Compositional, and Explainable Reasoning
- **Authors:** Nathaniel Weir, Peter Clark, Benjamin Van Durme (2022)
- **Citations:** 7
- **DOI:** https://doi.org/10.48550/arxiv.2209.07662
- **Summary:** Inference engine that grounds LM answers in proof trees backed by NL corpora
  of authoritative facts, addressing interpretability and hallucination through
  compositional, logic-based grounding.

---

## Tier 2 — Neurosymbolic Frameworks Combining Logic and Neural Nets

### Scallop: A Language for Neurosymbolic Programming
- **Authors:** Ziyang Li, Jiani Huang, Mayur Naik (2023); extended in 2024
- **Citations:** 27 (2023), 2 (2024)
- **DOI:** https://doi.org/10.1145/3591280
- **Summary:** A Datalog-based probabilistic logic programming language that integrates
  cleanly with neural modules including LLMs. Used in IRIS (2024, 14 cites) to combine
  LLM reasoning with Datalog static analysis for security vulnerability detection.

---

### From Word Models to World Models: Translating from Natural Language to the Probabilistic Language of Thought
- **Authors:** Lionel Wong, Gabriel Grand, Alexander K. Lew (2023)
- **Citations:** 36
- **DOI:** https://doi.org/10.48550/arxiv.2306.12672
- **Summary:** Proposes "rational meaning construction" — LLMs translate language into
  probabilistic programs (a logic-structured representation), grounding semantic content
  in a symbolic world model that supports inference.

---

### The Role of Foundation Models in Neuro-Symbolic Learning and Reasoning
- **Authors:** Daniel Cunnington, Mark Law, Jorge M. Lobo (2024)
- **Citations:** 10
- **DOI:** https://doi.org/10.1007/978-3-031-71167-1_5
- **Summary:** Examines how foundation models (LLMs) fit into the neuro-symbolic pipeline,
  identifying where logic-based grounding is most valuable.

---

### Answer Set Networks: Casting Answer Set Programming into Deep Learning
- **Authors:** Arseny Skryagin, Daniel Ochs, Phillip Deibert (2024)
- **Citations:** 0
- **DOI:** https://doi.org/10.48550/arxiv.2412.14814
- **Summary:** GNN-based ASP solver designed to make ASP differentiable and scalable,
  enabling end-to-end training of neuro-symbolic systems that include LLM components.

---

### Scalable Neural-Probabilistic Answer Set Programming
- **Authors:** Arseny Skryagin, Daniel Ochs, Devendra Singh Dhami (2023)
- **Citations:** 9
- **DOI:** https://doi.org/10.1613/jair.1.15027
- **Summary:** DPPL combining neural perception with ASP reasoning via probability
  estimates from DNNs — a foundation for grounding LLM outputs in probabilistic logic.

---

### Full Automation of Goal-driven LLM Dialog Threads with And-Or Recursors and Refiner Oracles
- **Authors:** Paul Tarau (2023)
- **Citations:** 1
- **DOI:** https://doi.org/10.48550/arxiv.2306.14077
- **Summary:** Prolog-style AND-OR tree search used to structure and automate LLM dialog
  reasoning, applying logic programming concepts (recursion, backtracking) to ground
  multi-step LLM chains.

---

## Tier 3 — Surveys & Background

| Year | Title | Authors | Citations |
|------|-------|---------|-----------|
| 2024 | From Statistical Relational to Neurosymbolic AI: A Survey | Marra, Dumančić, Manhaeve | 44 |
| 2025 | AI Reasoning in Deep Learning Era: From Symbolic AI to Neural–Symbolic AI | Liang, Wang, Tong | 25 |
| 2023 | Neurosymbolic AI: the 3rd wave | d'Avila Garcez, Lamb | 267 |
| 2022 | Is Neuro-Symbolic AI Meeting its Promises in NLP? A Structured Review | Hamilton, Nayak, Božić | 69 |

---

## Key Themes

1. **LLM-as-translator:** LLMs parse natural language into logic programs (ASP, Prolog,
   Datalog); the solver provides grounded, verifiable answers. *(Logic-LM, LLASP, Ishay et al.)*

2. **LLM-as-perceiver:** LLMs extract perceptual features; logic programs handle
   reasoning. *(Scallop, IRIS, dPASP)*

3. **Grounding via stable-model semantics:** ASP's closed-world assumption and
   non-monotonic reasoning addresses LLM hallucination and inconsistency. *(Rajasekharan et al.)*

4. **Probabilistic logic grounding:** Marrying LLM uncertainty with probabilistic logic
   programming. *(Scallop, Scalable NP-ASP, dPASP)*

5. **Domain-specific trust:** High-stakes domains (tax law, security analysis) benefit
   most from formal logic grounding. *(Jurayj et al., IRIS)*

---

## Notes on Data Collection

- Primary source: [OpenAlex](https://openalex.org) open academic graph API.
- The Semantic Scholar Graph API and arXiv API both returned HTTP 429 (rate-limited /
  WAF-blocked) from this environment's IP at the time of collection.
- Queries used: `answer set programming large language model`, `Prolog Datalog neuro-symbolic LLM`,
  `logic programming LLM grounding`, `Scallop neurosymbolic Datalog`, `LLM Clingo ASP solver`,
  `LLM generate Prolog rules commonsense reasoning`, `neuro-symbolic reasoning grounding language model`.
