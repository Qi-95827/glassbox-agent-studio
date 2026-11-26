<div align="center">

# Glass-Box Agent Studio

**An Open-Source Glass-Box AI Framework Visualization, Interactive Debugging & Multi-Agent Engineering Workbench**

**English** | [简体中文](./README.zh-CN.md)

[![Java 21](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/)
[![Spring Boot 3.5](https://img.shields.io/badge/Spring%20Boot-3.5-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![React 19](https://img.shields.io/badge/React-19-61dafb.svg)](https://react.dev/)
[![AgentScope](https://img.shields.io/badge/AgentScope-Java%202.0-blue.svg)](https://github.com/agentscope-ai/agentscope-java)
[![Tests](https://img.shields.io/badge/Tests-778%20Backend%20%7C%20169%20Frontend-success.svg)](#3-run-full-verification-suite)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](./LICENSE)

*Chrome DevTools + Flink Web UI + Visual DAG Studio for AI Agent Frameworks — watch live ReAct streaming & reasoning traces, inspect 4-tier context compaction & Hybrid RAG funnels, compose multi-agent DAGs with conflict detection, fork runs from checkpoints, light up live C4 Architecture X-Rays, and interoperate via OpenTelemetry.*

</div>

---

## Why This Project Exists

Most AI coding agent frameworks run as a **black box**: you type a prompt, text streams by, and you have no clear visibility into:
- How each agent was assembled (role, skills, model, and which tools were **explicitly stripped away** by permission boundaries);
- What exact `SYSTEM / USER / TOOL` messages and tool schemas were sent to the LLM;
- Why a tool call was **allowed (`PASS`)** or **blocked (`DENY`)** by security gates;
- How upstream agents pass evidence to downstream agents without hallucinating or leaking stale retries;
- Why a run failed, slowed down, or spent unexpected tokens.

**Glass-Box Agent Studio** solves this both as a **usable local engineering workbench** (with isolated Git worktrees, human-in-the-loop review, and Bring-Your-Own-Key LLM support) and as a **hands-on learning platform** where every internal mechanism of a production multi-agent system is exposed visually — and even runnable **without an API key** via built-in teaching playbooks.

---

## Visual Tour & What Is Visualized

| Module | What Is Visualized | Key Learning Value |
| :--- | :--- | :--- |
| **1. Workbench Home & Playbooks** | Multi-entry task composer, built-in key-free teaching playbooks, dual-layer status (`TaskStatus` × `RunStatus`) | Understand task routing, planning vs. execution, and human delivery lifecycle |
| **2. Interactive DAG Execution Graph** | Live React Flow DAG, node status & duration, node breakpoints, lightweight inspector + full-screen Glass-Box modal | Learn DAG dependency scheduling, step-by-step breakpoints, and state projection |
| **3. Structured Handoff Envelope** | Upstream conclusion, artifacts, decisions, open risks, **transparent `PASS/SKIP/DENY` validation rules** | Learn how to make multi-agent handoffs verifiable and audit-safe |
| **4. Full LLM I/O Inspector** | Role-colored `SYSTEM / USER / ASSISTANT / TOOL` messages, injected tool JSON schemas, raw responses & `tool_calls` | Master real-world prompt assembly, context management, and tool-calling loops |
| **5. Agent Assembly & ToolGateway Pipeline** | Tool permission funnel (granted vs. stripped tools) + horizontal 6-gate `ToolGateway` pipeline & Gate 5.5 HITL approvals | Learn least-privilege sandboxing, path whitelisting, loop guards, and optimistic locks |
| **6. Live ReAct Swimlane & Context Microscope** | Live streaming tokens + separate reasoning trace persistence, turn-by-turn ReAct swimlane, 6-layer context water-level chart & L1–L3 compaction diff with artifact reader | Inspect thought-action-observation loops, multi-tier context compaction, and artifact offload/readback |
| **7. RAG/Memory X-Ray & Visual Graph Composer** | Hybrid RAG funnel (BM25 + pgvector → RRF + dropped reasons), 3-layer memory & chunk viewer, and interactive `@xyflow/react` plan composer with live conflict highlighting & save-to-run | Master hybrid retrieval ranking, layered memory isolation, write-set disjointness & bounded reflection |
| **8. Execution Timeline, Checkpoint Fork & Live X-Ray** | 7-span Gantt timeline, Checkpoint state inspector + What-If run forking & side-by-side run diff, plus live C4 Architecture X-Ray replay tied to real runs | Learn Flink-style checkpoint debugging, counterfactual branching, and live framework telemetry |
| **9. Monitoring, Diagnostics, Open Trace & Eval Flywheel** | Grafana-style SVG dashboard, 5-step auto-diagnostics, OTel/Glass-Box export, read-only external agent trace ingest, and one-click run distillation into Eval Lab v2 | Learn LLM observability, OpenTelemetry interop, and continuous trace-to-eval regression loops |
| **10. BYOK Model Manager & Whole-Workspace Scope** | Multi-provider OpenAI-compatible presets (AES-256-GCM encrypted), 3-mode whole-workspace (`.`) scope bar, and one-click admin repo auto-grant | Learn secure credential hygiene, SSRF protection, and ergonomic sandboxed workspace onboarding |

---

### 1. Workbench Home & Key-Free Teaching Playbooks

Launch tasks across General, Requirements, Design, Code, Test, and Simulated Bug entries — switch seamlessly between **English / 中文** in the top bar, or click one of the **3 key-free teaching playbooks** to explore multi-agent collaboration, breakpoint debugging, and `ToolGateway` security boundaries immediately.

![Workbench Home (English)](docs/images/readme/en-01-workbench-home.png)

**What you can learn here:**
- **Dual-Layer Status Modeling (`TaskStatus` vs. `RunStatus`)**: Why an immutable agent execution status (`RunStatus`, e.g., `BLOCKED` awaiting review) should be separated from the user-facing delivery lifecycle (`TaskStatus`, e.g., `DELIVERED` once committed in Git).
- **Candidate Plan Review**: How to generate a structured DAG candidate first and let humans adjust nodes, models, file scopes, and budgets before execution.

---

### 2. Multi-Agent DAG Execution Flow & Breakpoint Debugging

Every task executes as a live React Flow DAG. Clicking any node opens a clean right-side overview drawer with 4 direct entry cards into the wide-screen Glass-Box inspector modal, plus one-click **node breakpoints** to pause execution before a node starts.

![DAG Execution Flow (English)](docs/images/readme/en-02-dag-flow.png)

**What you can learn here:**
- **Event-Driven Projection**: How backend execution events (`ExecutionGraphProjector`) are projected into a deterministic frontend DAG without coupling UI state to raw engine internals.
- **Controlled Execution**: How breakpoints, cancellation signals, and bounded waits work safely across concurrent workflow threads.

---

### 3. Structured Handoff Envelope & Transparent "Why PASS?" Rules

Instead of opaque free-text relays between agents, every handoff is inspected as a structured envelope showing the upstream conclusion, involved files, key decisions, open risks, and platform verification checks — with an expandable **"How PASS is determined"** rule guide explaining **why** each check passed or skipped.

![Glass-Box Handoff Envelope (English)](docs/images/readme/en-03-glassbox-handoff.png)

**What you can learn here:**
- **Anti-Hallucination Handoffs**: How the platform enforces `DEPENDENCY_MATCH`, `ATTEMPT_IDENTITY`, `TOOL_EVIDENCE`, and `ACCEPTED_RECEIPT` so downstream agents only receive verified outputs from direct upstream DAG dependencies.
- **Deterministic Extraction**: Inspect `StructuredHandoffExtractor.java` to see how bilingual structured summaries and evidence pointers are extracted from agent outputs.

---

### 4. Full LLM I/O Trace (Messages, Tool Schemas & `tool_calls`)

See the exact masked payload sent to the LLM (`SYSTEM`, `USER`, `ASSISTANT`, `TOOL`), every injected tool JSON schema, token counts, latency, and the model's raw response or `tool_calls` decisions.

![Glass-Box LLM I/O (English)](docs/images/readme/en-04-glassbox-llm.png)

**What you can learn here:**
- **Externalized Bilingual Prompt Engineering**: All agent personas, skills, planning prompts, and tool descriptions live as clean external resources under `backend/src/main/resources/prompts/` (Chinese) and `prompts/en/` (English).
- **Data Masking at Capture Time**: Inspect `ControlledModel.java` and `SensitiveDataMasker.java` to see how 64 KB truncation and secret redaction happen before payloads hit the database.

---

### 5. Agent Assembly Blueprint & Tool Permission Funnel

Understand **how an agent is born** for each node: its role definition, bound skills, model, context sources, and the **Tool Permission Funnel** showing both granted tools and tools explicitly stripped by platform security boundaries (e.g., removing `WORKSPACE_WRITE` from read-only review nodes).

![Glass-Box Assembly Blueprint (English)](docs/images/readme/en-05-glassbox-assembly.png)

**What you can learn here:**
- **Least-Privilege Agent Sandboxing**: Even if a prompt tries to trick a reviewer agent into editing files, the write tool is never even injected into its runtime — and `ToolGateway` enforces 6 runtime security gates (`OWNERSHIP_AND_SCOPE`, `TOOL_REGISTRY_GRANT`, `BUDGET_AND_LOOP`, `PATH_WHITELIST`, `ARGUMENT_SCHEMA`, `CANCEL_STATE`).

---

### 6. Grafana-Style Fine-Grained Monitoring Dashboard

Monitor every run, node, model, and tool across `24h / 7d / 30d / All` time windows with zero heavy charting dependencies (pure React SVG): KPI cards with success-rate gauge, stacked/line/area time-series charts, status/model/tool donut charts, slow-node rankings, and `ToolGateway` interception analytics.

![Monitoring Dashboard (English)](docs/images/readme/en-06-monitoring-dashboard.png)

**What you can learn here:**
- **Practical Agent Observability**: How to aggregate run latency (Avg / P95), token consumption, failure rates, and security gate denials from relational audit tables (`MonitoringDashboardService.java`).

---

### 7. 5-Step Automated Fault Diagnostics & Deep-Link Triage

When a run fails, blocks, triggers security gates, or hits slow LLM calls, click **"One-Click Triage"** to open a guided 5-step diagnostics drawer (`Terminal Status → ToolGateway → LLM I/O → Handoff & Assembly → Workspace Git`). Every finding includes a direct action link that jumps straight to the target node and auto-opens the exact Glass-Box modal tab.

![Run Diagnostics Drawer (English)](docs/images/readme/en-07-diagnostics.png)

**What you can learn here:**
- **Root-Cause Triage Automation**: Inspect `RunDiagnosticsService.java` to learn how to synthesize multi-table execution evidence into actionable, locale-aware engineering diagnostics.

---

### 8. BYOK (Bring Your Own Key) Model & Encrypted Credential Management

Configure your own API keys directly in **Settings → Models & API Keys (BYOK)** with built-in presets for **DeepSeek, OpenAI, Qwen (DashScope), Zhipu GLM, SiliconFlow, and any OpenAI-compatible endpoint**. Test connectivity in one click and optionally set reference pricing for local budget estimation.

![BYOK Settings (English)](docs/images/readme/en-08-byok-settings.png)

**What you can learn here:**
- **Production-Grade Secret Hygiene**: `SecretCipher.java` encrypts API keys with **AES-256-GCM** (random 12-byte IV), never returns plaintext keys to the frontend (masked as `sk-****xxxx`), validates Base URLs against SSRF, and enforces per-user ownership isolation.

---

### 9. V1–V6 Glass-Box AI Framework Visualization Suite (Latest Release)

Built directly on top of the verified backend runtime (zero mock telemetry — every metric, span, and diff comes from persisted records or live SSE frames):

```mermaid
flowchart LR
    V1["V1 · Live ReAct Swimlane\nStreaming Tokens & CoT\nHorizontal 6-Gate Pipeline"]
    V2["V2 · Context & RAG X-Ray\n6-Layer Water-Level Chart\nBM25 + pgvector -> RRF"]
    V3["V3 · Visual Plan Composer\nDrag/Edit DAG & Reflection\nLive Conflict & Save-to-Run"]
    V4["V4 · Timeline & Time-Travel\n7-Span Gantt Flamegraph\nCheckpoint Fork & Diff"]
    V5["V5 · Live Architecture X-Ray\nBind Real Run to C4 Map\nStep Replay & Deep-Link"]
    V6["V6 · Open Trace & Flywheel\nOTel / Glass-Box Export\nExternal Ingest & Eval Case"]
    V1 --> V2 --> V3 --> V4 --> V5 --> V6
```

- **Live Streaming ReAct Swimlane & 6-Gate Pipeline (V1)**: Streams incremental tokens and separated reasoning traces (`reasoning_content`, Flyway H2 `V15` / PG `V5`) over SSE per node, paired with a turn-by-turn ReAct swimlane and horizontal 6-gate `ToolGateway` status lights.
- **Context Compaction Microscope & Hybrid RAG / Memory X-Ray (V2)**: Pure-SVG 6-layer stacked area chart (`SYSTEM / SKILLS / MEMORY / HANDOFF / TOOL_OUTPUTS / HISTORY`) with L1 (60%), L2 (78%), and L3 (92%) threshold lines, before/after compaction details with one-click authenticated `artifactRef` readback, plus a Hybrid RAG retrieval funnel (`BM25` + `pgvector` → `RRF` + dropped reasons), 3-layer memory manager, and document chunk inspector.
- **Interactive Multi-Agent Visual Graph Composer (V3)**: Switch plan confirmation into an interactive `@xyflow/react` composer to edit nodes, dependencies, file read/write scopes, and bounded reflection edges (`maxRevisions`) with live write-conflict/cycle highlighting (`POST /api/plans/validate-draft`) and validated save-to-candidate execution (`PUT /api/plan-candidates/{id}/draft`).
- **Execution Timeline, Checkpoint Inspector & What-If Run Forking (V4)**: 7-span Gantt timeline (`NODE / ATTEMPT / MODEL / TOOL / APPROVAL / COMPACTION / CHECKPOINT`), frozen checkpoint viewer, counterfactual run forking (`POST /api/runs/{id}/forks`, `run_forks` table) without mutating source evidence, and side-by-side run comparison.
- **Live Architecture X-Ray (V5)**: Toggle the C4 Architecture Map into **Live X-Ray** mode, pick any recent run from the dropdown, watch traversed engines light up with real latency/compaction/gate-block badges, scrub step-by-step replay, and deep-link straight back into the exact Glass-Box tab.
- **Open Glass-Box Trace Protocol & Eval Flywheel (V6)**: Export deterministic redacted **OpenTelemetry** or Glass-Box JSON (`GET /api/runs/{id}/trace-export`), ingest spans from external standalone Python/Java agents (`POST /api/external-traces/runs`, read-only inspection), and distill any real run into an Eval Lab v2 custom regression case in one click (`POST /api/runs/{id}/eval-cases`).
- **Ergonomic Workspace Onboarding**: 3-mode persistent authorization bar (`Whole Workspace .` with built-in noise exclusions / `Subdirectories` / `Files`), collapsed low-frequency budgets, and one-click admin local Git repo auto-grant.

---

## Full-Stack Bilingual Design: Why Two Externalized Prompt Suites?

Unlike typical web apps, an AI Agent platform has **three distinct language layers**: UI copy, backend validation/diagnostics, and **LLM prompts**.

Because **Glass-Box Agent Studio** is built for **visual transparency and hands-on learning**, we intentionally maintain **two mirrored externalized prompt suites** (`backend/src/main/resources/prompts/` for Chinese and `prompts/en/` for English) instead of forcing everyone to read a single language in the LLM inspector:
1. **Native Clarity in Glass-Box Learning**: When an English learner opens **Model I/O**, they see idiomatic English system prompts, skill guidelines, and tool JSON schemas; when a Chinese learner switches to `中文`, they see native Chinese prompts — making multi-agent prompt engineering effortless to read and compare.
2. **Task-Frozen Locale (`tasks.locale`)**: Every task persists its creation locale (`zh-CN` / `en-US`) via Flyway `V6`, driving `PromptResourceLoader.load(locale, path)` with automatic fallback so historical runs remain reproducible.
3. **Code-First Backend & Frontend i18n**: Security gates (`ToolGateway`), handoff checks, and diagnostics are modeled with semantic codes + Spring `MessageSource` (`Accept-Language`) and zero-dependency React JSON locales — so even historical runs adapt cleanly when you switch UI languages.

---

## Quick Start (5 Minutes)

### Option A: One-Click Setup with an AI Coding Agent (Recommended)

Using **Claude Code, Cursor, Antigravity, Codex, or Windsurf**? Just copy and paste the prompt below to let your AI agent check prerequisites, build, and launch the workbench automatically:

```text
Please clone and set up the open-source project https://github.com/Qi-95827/glassbox-agent-studio locally:
1. Verify prerequisites: Java 21, Maven 3.9+, Node.js 20+, and Git (Docker Desktop is optional and only needed for Simulated Bug container verification). Tell me clearly if any tool is missing.
2. Clone the repository into a clean local folder.
3. Build the frontend from `frontend/user-workbench/` using `npm ci && npm run build`.
4. Start the Spring Boot backend from the repository root (where `pom.xml` lives, NOT inside `backend/`) using `mvn spring-boot:run`.
5. Wait until Tomcat is listening on `http://127.0.0.1:8088`, confirm it is up, and give me the link to open in my browser. Do NOT create or commit any real API keys.
```

### Option B: Manual Setup

#### Prerequisites
- **Java 21** & **Maven 3.9+**
- **Node.js 20+**
- **Git** (Docker Desktop is *optional*, only needed for Simulated Bug container verification)

#### 1. Clone & Build Frontend
```bash
git clone https://github.com/Qi-95827/glassbox-agent-studio.git
cd glassbox-agent-studio

cd frontend/user-workbench
npm ci
npm run build
cd ../..
```

### 2. Start the Backend Server
```bash
mvn spring-boot:run
```

Open **http://127.0.0.1:8088** in your browser:
1. Follow the on-screen prompt to initialize your local administrator account.
2. Try any of the **3 key-free teaching playbooks** immediately with the built-in `mock` model.
3. To use real LLMs, open **Settings → Models & API Keys (BYOK)**, choose a provider (e.g. DeepSeek / OpenAI / Qwen), paste your own API key, and click **Test Connectivity**.

### 3. Run Full Verification Suite
```bash
# Backend unit, integration, Flyway migration & ArchUnit architecture guard tests (H2 default)
mvn test

# Optional: run with real Docker bug sandbox verification (requires Docker Desktop)
# PowerShell: $env:RUN_DOCKER_TESTS="true"; mvn test

# Optional: start PostgreSQL 16 + pgvector and run PG + HNSW + JPA integration tests
docker compose up -d
# PowerShell: $env:WORKBENCH_PG_TEST="true"; mvn "-Dtest=PostgresPgVectorIntegrationTest" test

# Frontend format, lint, unit tests & production build
cd frontend/user-workbench
npm run format:check
npm run lint
npm test
npm run build
```

---

## Architecture Overview

Strict layered architecture automatically enforced by **ArchUnit** (`ArchitectureGuardTest`): zero top-level package cycles, pure `domain` & `framework` layers, and strict isolation of `io.agentscope` SDK calls to `infrastructure` / `persistence.agentscope` / `app`.

```text
frontend/user-workbench/   React 19 · Vite 6 · MUI (M3) · React Flow · React Query · React Router
backend/src/main/java/
  dev.workbench.domain          Pure domain enums & value objects (zero persistence/web deps)
  dev.workbench.framework       Pure contracts, ToolGateway (6.5 gates + HITL), OutboundUrlGuard, SecretCipher
  dev.workbench.infrastructure  AgentScope Java 2.0.4, BYOK/Embedding adapters, hardened MCP (STDIO/HTTP) & pgvector
  dev.workbench.modelconnection BYOK encrypted model connections, RAG Embedding settings, probing & usage estimation
  dev.workbench.persistence     JPA entities (H2 CLOB / PG TEXT), repositories & Flyway migrations
  dev.workbench.planning        Idea drafting, entry routing, candidate DAG generation, budgets & validation
  dev.workbench.orchestration   Run lifecycle, parallel DAG scheduler, reflection loops & non-blocking breakpoints
  dev.workbench.control         4-tier context compaction (Query-Aware L1 offload, L2 anchors) & L1 artifacts
  dev.workbench.delivery        Execution graph projector, node detail & delivery aggregation
  dev.workbench.workspace       Authorized file sandbox & Git worktree review/commit/merge
  dev.workbench.privateagent    Versioned private Agents, Skills & read-only Tool configs
  dev.workbench.memory          Layered long-term memory (V9) & workspace RAG knowledge base (V13 + pgvector)
  dev.workbench.bug             Simulated Bug triage & isolated Docker Python container regression
  dev.workbench.evaluation      Eval Lab v2 (6-dim capabilities, Benchmark Hub, custom datasets & A/B compare)
  dev.workbench.observability   SSE streaming/cursor events, Grafana-style SVG dashboard, Prometheus metrics & 5-step diagnostics
  dev.workbench.timeline        Execution timeline, Checkpoint/What-If diff, Live Architecture X-Ray, OTel/External Trace & Eval Flywheel
  dev.workbench.auth/ownership  Session & async auth context, Cookie CSRF, ADMIN_ONLY guards & per-user resource ownership
backend/src/main/resources/
  prompts/                      Externalized Chinese prompts & tool contracts (default)
  prompts/en/                   Mirrored English prompts & tool contracts (for EN tasks)
  i18n/                         Spring MessageSource bundles (messages_zh_CN / messages_en_US)
  db/migration/                 Flyway H2 migrations (V1 ~ V17, 97 tables)
  db/migration-pg/              Flyway PostgreSQL 16 + pgvector migrations (V1 ~ V7)
```

---

## Documentation

- [Documentation Index (Chinese)](docs/README.md)
- [Product Requirements & Technical Architecture](docs/项目总览_产品需求与技术架构.md)
- [AI Framework Visualization V0–V6 Final Verification Log](docs/交接/AI框架可视化_V0-V6_最终验收记录.md)
- [External Agent Trace Ingestion & OTel Export Guide](docs/交接/外部Agent_Trace接入指南_2026-11-26.md)
- [Security Audit, Top 10 Fixes & Roadmap](docs/项目漏洞审计与后续演进改进文档.md)
- [Top 10 Security & P0 Fixes Verification Log](docs/交接/Top10安全止血与P0修复_验收记录.md)
- [PostgreSQL 16 + pgvector Deployment Guide](docs/开发记录/PGVector切换与部署指南.md)
- [Engineering & Security Coding Standards](docs/开发规范/代码开发规范.md)

---

## License

This project is licensed under the [MIT License](./LICENSE).
