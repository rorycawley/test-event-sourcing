# Claude Code and the REPL: AI-Assisted Clojure Development

How Claude Code integrates with the Clojure REPL to create a development workflow where an AI agent can write code, evaluate it, observe results, and iterate — just like a human developer at the REPL.

## Table of Contents

- [Why This Matters](#why-this-matters)
- [The Problem: AI Without a REPL](#the-problem-ai-without-a-repl)
- [The Solution: Bridging Claude Code to nREPL](#the-solution-bridging-claude-code-to-nrepl)
- [Architecture Overview](#architecture-overview)
- [Component 1: nREPL Server](#component-1-nrepl-server)
- [Component 2: clj-nrepl-eval](#component-2-clj-nrepl-eval)
- [Component 3: clj-paren-repair](#component-3-clj-paren-repair)
- [Component 4: Claude Code Hooks](#component-4-claude-code-hooks)
- [Component 5: Skills and Commands](#component-5-skills-and-commands)
- [Component 6: CLAUDE.md Project Instructions](#component-6-claudemd-project-instructions)
- [Component 7: The dev/user.clj Walkthrough](#component-7-the-devuserclj-walkthrough)
- [The Full Workflow in Practice](#the-full-workflow-in-practice)
- [Trade-offs and Design Decisions](#trade-offs-and-design-decisions)
- [What This Enables](#what-this-enables)

---

## Why This Matters

Clojure developers have a superpower that developers in most other languages don't: the REPL. It's not a toy console for trying snippets — it's the primary development tool. You grow a program interactively, evaluating forms one at a time, inspecting results, and building up state incrementally.

The question is: **can an AI agent use the REPL the same way a human does?**

The answer is yes — but it requires infrastructure. Claude Code is a terminal-based AI agent that can read files, edit code, and run shell commands. By itself, it can write Clojure code and run tests via `bb test`. But it can't connect to a running REPL, evaluate expressions, or observe the results. Without the REPL, Claude Code is writing Clojure like it would write Java — edit, compile, run, hope for the best.

This project bridges that gap. Claude Code connects to an nREPL server, evaluates code, sees the output, and iterates. It gets the same fast feedback loop that makes Clojure development productive for humans.

---

## The Problem: AI Without a REPL

Without REPL integration, an AI coding agent working on a Clojure project faces several problems:

**1. No feedback until tests run.**
The agent writes a function, but doesn't know if it works until it runs the full test suite. A test run might take 30 seconds (Testcontainers startup, database migrations). That's a slow feedback loop.

**2. No way to explore the system.**
A human developer can `(require '[es.store :as store])` and call `(store/load-stream ds "account-42")` to see what events are in a stream. Without a REPL, the agent can only read source code — it can't interact with a running system.

**3. No incremental verification.**
Suppose the agent needs to write a new projection handler. A human would build it up form by form: define the handler map, test it with a sample event, wire it into the projection, process events, check the read model. Each step gives immediate feedback. Without a REPL, the agent must write the entire thing and hope it works.

**4. Parenthesis errors from LLM generation.**
LLMs generate text token by token. They don't have a parser tracking bracket depth. When generating nested Clojure forms, they occasionally drop a closing paren or add an extra one. In Python, this causes a syntax error on one line. In Clojure, a missing paren can swallow the next three `defn` forms before the reader notices. The error message points to the wrong place, and the agent can spiral into increasingly wrong "fixes."

---

## The Solution: Bridging Claude Code to nREPL

The integration is built from six components that work together:

```
┌──────────────────────────────────────────────────────────┐
│  Claude Code (AI Agent in Terminal)                      │
│                                                          │
│  ┌────────────┐  ┌──────────────┐  ┌──────────────────┐ │
│  │ CLAUDE.md  │  │ Skills       │  │ Hooks            │ │
│  │ (project   │  │ (clojure-eval│  │ (paren repair    │ │
│  │  context)  │  │  workflow)   │  │  on Write/Edit)  │ │
│  └────────────┘  └──────┬───────┘  └────────┬─────────┘ │
│                         │                    │           │
│                  ┌──────▼────────────────────▼─────────┐ │
│                  │  Bash tool                          │ │
│                  │  clj-nrepl-eval / clj-paren-repair  │ │
│                  └──────────────┬──────────────────────┘ │
└─────────────────────────────────┼────────────────────────┘
                                  │ nREPL protocol
                                  │ (TCP, persistent session)
                        ┌─────────▼──────────┐
                        │  nREPL Server      │
                        │  (JVM process)     │
                        │                    │
                        │  Clojure runtime   │
                        │  + project deps    │
                        │  + test deps       │
                        └────────────────────┘
```

Each component solves a specific problem. Let's examine them one by one.

---

## Component 1: nREPL Server

### What

An nREPL server is a network-accessible Clojure REPL. It runs inside the JVM process with the project's classpath, listening on a TCP port. Clients connect and send Clojure forms for evaluation; the server evaluates them and returns results.

### How It's Configured

In `deps.edn`, the `:nrepl` alias adds the nREPL dependency and sets it as the main entry point:

```clojure
;; deps.edn
:nrepl {:extra-paths ["test"]
        :extra-deps {nrepl/nrepl {:mvn/version "1.3.1"}}
        :jvm-opts ["-Djdk.attach.allowAttachSelf"]
        :main-opts ["-m" "nrepl.cmdline"]}
```

Key details:

- **`:extra-paths ["test"]`** — Test namespaces are on the classpath. This lets you `require` test namespaces and run tests interactively.
- **`:jvm-opts`** — `allowAttachSelf` permits JVM tooling (profilers, debuggers) to attach to the running process.
- **`-m nrepl.cmdline`** — Starts the nREPL command-line server, which auto-assigns a port and writes it to `.nrepl-port`.

Start it with:

```bash
clojure -M:nrepl
```

Or use Babashka's task runner:

```bash
bb clj-repl    # same thing, defined in bb.edn
```

The server writes its port to `.nrepl-port` in the project root. Any client that knows to look there can auto-discover it.

### Why This Design

- **Persistent state.** Unlike `clj -e "(some-code)"`, the nREPL server keeps loaded namespaces, `def`'d vars, and running state between evaluations. This is essential for the incremental workflow.
- **Network protocol.** The nREPL wire protocol (bencode over TCP) means any client — editor, CLI tool, AI agent — can connect. The server doesn't care who's evaluating code.
- **Test classpath included.** Including test paths means the REPL can run individual tests without a separate test process.

---

## Component 2: clj-nrepl-eval

### What

`clj-nrepl-eval` is a command-line tool (installed via [bbin](https://github.com/babashka/bbin)) that evaluates Clojure code against a running nREPL server. It's the bridge between Claude Code's Bash tool and the nREPL server.

### Why It Exists

Claude Code can run shell commands via its Bash tool. It cannot speak the nREPL wire protocol directly. `clj-nrepl-eval` translates between these worlds:

```
Claude Code  →  Bash("clj-nrepl-eval -p 62430 '(+ 1 2)'")
                    ↓
clj-nrepl-eval  →  nREPL protocol  →  JVM evaluates (+ 1 2)
                    ↓
                 stdout: "3"  →  Claude Code reads the result
```

### How It Works

**Server discovery:**

```bash
clj-nrepl-eval --discover-ports
# Discovered nREPL servers:
#
# In current directory (/path/to/project):
#   localhost:62430 (clj)
#
# Total: 1 server
```

The tool scans for `.nrepl-port` files and similar markers to find running servers.

**Evaluating code:**

```bash
# Simple expression
clj-nrepl-eval -p 62430 "(+ 1 2 3)"

# Multiline via heredoc (preferred — avoids shell escaping issues)
clj-nrepl-eval -p 62430 <<'EOF'
(require '[bank.account :as account] :reload)
(account/decide {:command-type :deposit :data {:amount 50}}
                {:status :open :balance 100})
EOF
```

**Persistent sessions:**

Each `host:port` pair gets a persistent session. Vars defined in one evaluation are available in the next:

```bash
clj-nrepl-eval -p 62430 "(def x 42)"
# later...
clj-nrepl-eval -p 62430 "(+ x 8)"
# => 50
```

This is critical. Without persistent sessions, each evaluation would start fresh — no loaded namespaces, no state. The agent would have to `require` everything on every call.

**Automatic delimiter repair:**

If the code has mismatched parentheses, `clj-nrepl-eval` attempts to repair them before sending to the server. This catches many LLM-generated bracket errors before they cause confusing runtime errors.

### Key Options

| Option | Purpose |
|---|---|
| `-p PORT` | nREPL port (required for evaluation) |
| `--timeout MS` | Kill evaluation after N milliseconds (default: 2 minutes) |
| `--reset-session` | Clear the persistent session |
| `--discover-ports` | Find nREPL servers in the current project |
| `--connected-ports` | Show previously connected sessions |

### Why a CLI Tool (Not an MCP Server)

The tool is a simple CLI binary, not a more complex protocol. This is intentional:

- **Claude Code already has a Bash tool.** No additional protocol or integration needed.
- **Composable.** Other scripts and editors can use the same tool.
- **Debuggable.** You can run `clj-nrepl-eval` manually in a terminal to test.

---

## Component 3: clj-paren-repair

### What

`clj-paren-repair` is a standalone tool that fixes mismatched delimiters (parentheses, brackets, braces) in Clojure files. It supports `.clj`, `.cljs`, `.cljc`, `.bb`, and `.edn` files.

### Why It Exists

This is the single most important tool for reliable AI-generated Clojure code.

LLMs generate tokens sequentially. They're remarkably good at Clojure, but they don't have a parser tracking bracket depth. When generating deeply nested forms, they occasionally:

- Drop a closing paren at the end of a `let` binding
- Add an extra `)` that closes a `defn` too early
- Lose track of bracket types (`]` vs `)`)

In most languages, the damage is local — the error is on the line where the bracket is wrong. In Clojure (and all Lisps), a missing paren can cause the reader to consume the next several top-level forms as part of the current expression. The error message says "unexpected EOF" or points to a line far from the actual mistake. Even experienced humans find these errors disorienting.

For an AI agent, this is worse. Without a parser, the agent tries to "fix" the error by adding or removing parens based on the error message — which points to the wrong place. This can spiral: each "fix" introduces a new imbalance.

`clj-paren-repair` solves this by using [parinfer](https://shaunlebron.github.io/parinfer/) — an algorithm that infers correct parenthesisation from indentation. Since LLMs are very good at maintaining correct indentation (even when they drop a paren), parinfer can reconstruct the intended bracket structure from the indentation alone.

### How It Works

```bash
# Repair a file in-place
clj-paren-repair src/bank/account.clj
```

The tool:

1. Reads the file
2. Detects if any delimiters are mismatched
3. If yes, runs parinfer's "paren mode" to infer correct brackets from indentation
4. Writes the repaired file back

If the file is already correct, it's a no-op. The tool only modifies files with actual delimiter errors.

---

## Component 4: Claude Code Hooks

### What

Claude Code hooks are shell commands that run automatically before or after specific tool calls. They're configured in `~/.claude/settings.json` and execute without any action from the AI agent.

### How They're Configured

```json
{
  "hooks": {
    "PreToolUse": [
      {
        "matcher": "Write|Edit",
        "hooks": [
          {
            "type": "command",
            "command": "clj-paren-repair-claude-hook --cljfmt"
          }
        ]
      }
    ],
    "PostToolUse": [
      {
        "matcher": "Edit|Write",
        "hooks": [
          {
            "type": "command",
            "command": "clj-paren-repair-claude-hook --cljfmt"
          }
        ]
      }
    ],
    "SessionEnd": [
      {
        "hooks": [
          {
            "type": "command",
            "command": "clj-paren-repair-claude-hook --cljfmt"
          }
        ]
      }
    ]
  }
}
```

### What Each Hook Does

**PreToolUse (before Write/Edit):**
The hook receives the file content that Claude is about to write. It checks for delimiter errors and repairs them *before the file is written to disk*. This means the file on disk never has broken parens — even if the LLM generated them.

**PostToolUse (after Write/Edit):**
Belt and suspenders. After the file is written, check again and repair if needed. This catches edge cases where the repair and the edit interact.

**SessionEnd:**
Final cleanup. When the Claude Code session ends, repair any Clojure files that might have been left with delimiter issues.

### The `--cljfmt` Flag

The `--cljfmt` flag tells the hook to also run `cljfmt` (Clojure formatting) after repair. This means every file Claude writes is:

1. Delimiter-repaired (correct parens)
2. Consistently formatted (standard indentation, spacing)

### Why This is Automatic, Not Manual

The hooks fire without the AI agent doing anything. It doesn't need to remember to repair parens — it just writes code, and the hooks silently ensure correctness. This is critical because:

- The agent doesn't know its parens are wrong (it generated them, it thinks they're right)
- Asking the agent to "check your parens" doesn't work — it would re-generate the same mistake
- Automatic repair means the problem is fixed before it can cascade

---

## Component 5: Skills and Commands

### What

Claude Code **skills** and **commands** are markdown files that teach the AI agent specific workflows. They're loaded into the agent's context when invoked.

### The clojure-eval Skill

Located at `~/.claude/skills/clojure-eval/SKILL.md`, this skill teaches Claude Code the REPL evaluation workflow:

**When to trigger:** When the agent needs to test code, verify compilation, check function behaviour, or interact with a running system.

**The workflow it teaches:**

1. **Discover servers:** Run `clj-nrepl-eval --discover-ports` to find running nREPL servers
2. **Ask the user:** If multiple servers exist, ask which one to use (via `AskUserQuestion` tool)
3. **Evaluate:** Use heredoc syntax to avoid shell escaping issues
4. **Iterate:** Edit code → reload namespace (`:reload`) → re-test

**Why heredoc syntax matters:**

```bash
# BAD — Bash escapes `!` to `\!`, breaking Clojure functions like swap!
clj-nrepl-eval -p 62430 "(swap! my-atom inc)"

# GOOD — single-quoted heredoc passes everything literally
clj-nrepl-eval -p 62430 <<'EOF'
(swap! my-atom inc)
EOF
```

This is a subtle but important detail. Many Clojure functions end with `!` (mutation marker). Bash's history expansion treats `!` specially. The skill teaches the agent to always use heredoc to avoid this class of errors.

### The start-nrepl Command

Located at `~/.claude/commands/start-nrepl.md`, this command teaches the agent how to start an nREPL server when none is running:

1. Check `CLAUDE.md` for project-specific REPL instructions
2. Look for an `:nrepl` alias in `deps.edn` or `project.clj` (Leiningen)
3. Check for existing servers before starting a new one
4. Start the server in the background (Bash tool with `run_in_background: true`)
5. Parse the port from the output
6. Verify the connection with a test evaluation

### The clojure-nrepl Command

Located at `~/.claude/commands/clojure-nrepl.md`, this provides reference documentation for `clj-nrepl-eval` options, discovery workflow, and session management.

### Why Skills Instead of Just Documentation

Skills are **active instruction** — they don't just describe what the tools do, they prescribe a workflow with decision points. The `clojure-eval` skill says "when you need to verify code, do these specific steps in this order." This is more effective than documentation because:

- The agent follows a proven workflow instead of inventing one
- Edge cases (heredoc syntax, `:reload` flag) are baked in
- The skill includes when-to-use heuristics, so the agent knows when REPL evaluation is appropriate vs. just running tests

---

## Component 6: CLAUDE.md Project Instructions

### What

`CLAUDE.md` is a project-level instruction file that Claude Code reads at the start of every session. It provides project-specific context that shapes the agent's behaviour.

### What It Contains (for This Project)

```markdown
## REPL Workflow

### nREPL Evaluation
The command `clj-nrepl-eval` is installed on your path.

**Discover nREPL servers:**
`clj-nrepl-eval --discover-ports`

**Evaluate code:**
`clj-nrepl-eval -p <port> "<clojure-code>"`

The REPL session persists between evaluations — namespaces and
state are maintained.
Always use `:reload` when requiring namespaces to pick up file changes.

### Parenthesis Repair
The command `clj-paren-repair` is installed on your path.
**IMPORTANT:** Do NOT try to manually repair parenthesis errors.
Run `clj-paren-repair <file>` instead.
```

### Why This Matters

CLAUDE.md is the first thing the agent reads. By putting REPL instructions here, every conversation starts with the agent knowing:

- How to connect to the REPL
- That sessions persist (so it won't redundantly re-require namespaces)
- To always use `:reload` (so it gets fresh code after edits)
- To never manually fix parens (use the tool instead)

That last point — "Do NOT try to manually repair parenthesis errors" — is a hard-won instruction. Without it, the agent will try to fix parens by reading the error message and editing the file. This rarely works because the error points to the wrong location. The instruction redirects the agent to use the tool, which uses parinfer and actually works.

---

## Component 7: The dev/user.clj Walkthrough

### What

`dev/user.clj` is a REPL walkthrough file that demonstrates every major feature of the system through evaluable forms. It's not application code — it's a guided exploration.

### Why It's in the `dev` Directory

The `dev` path is on the classpath (see `deps.edn`: `:paths ["src" "dev" "resources"]`), which means the `user` namespace is automatically available when the REPL starts. By Clojure convention, the `user` namespace is where REPL exploration happens.

### What the Walkthrough Covers

The walkthrough is structured as a series of numbered steps, each wrapped in `(comment ...)` blocks:

1. **Start a throwaway Postgres** — Testcontainers gives you a clean database
2. **Create schema** — Run migrations against the fresh database
3. **Send commands** — Open accounts, deposit, withdraw (with idempotency keys)
4. **Inspect event streams** — See the raw events in the store
5. **Build read models** — Run projections, query the read model
6. **Optimistic concurrency** — See what happens when two writers conflict
7. **Idempotency** — Re-send the same command, observe it's a no-op
8. **Retry on conflict** — Automatic retry with exponential backoff
9. **Fund transfer saga** — Cross-account transfer with compensation
10. **Full async system** — Start the Component system with RabbitMQ

### How Claude Code Uses It

When Claude Code needs to understand how the system works, it can:

1. Read `dev/user.clj` to see working examples
2. Evaluate forms from the walkthrough to see actual results
3. Use the patterns as templates for new code

The walkthrough serves as both documentation and a test — if the forms evaluate successfully, the system works correctly.

---

## The Full Workflow in Practice

Here's what a typical Claude Code + REPL session looks like when modifying this project:

### Example: Adding a New Event Handler to the Account Projection

**Step 1: Claude Code reads the relevant source files.**

The agent reads `src/bank/account_projection.clj` and `src/bank/account.clj` to understand the existing handlers and events.

**Step 2: Claude Code writes the new handler.**

Using the Edit tool, it adds a new handler function. The `clj-paren-repair-claude-hook` fires automatically before and after the edit, ensuring correct delimiters and formatting.

**Step 3: Claude Code verifies compilation via REPL.**

```bash
clj-nrepl-eval -p 62430 <<'EOF'
(require '[bank.account-projection :as ap] :reload)
EOF
```

If the namespace loads without error, the code compiles. If there's an error, the agent sees it immediately and fixes it — no waiting for a test suite.

**Step 4: Claude Code tests the handler interactively.**

```bash
clj-nrepl-eval -p 62430 <<'EOF'
(def test-handler (ap/make-handler))
(test-handler {} {:event-type "account-opened"
                  :event-version 1
                  :payload {:owner "Alice"}
                  :stream-id "test-1"})
EOF
```

The agent sees the result immediately and can verify it's correct.

**Step 5: Claude Code runs the full test suite.**

```bash
bb test
```

This confirms the change doesn't break anything else.

The key insight: steps 3 and 4 give the agent fast feedback (sub-second) before committing to the slow feedback of a full test run (30+ seconds). This is the same workflow a human Clojure developer uses.

---

## Trade-offs and Design Decisions

### CLI Tool vs. MCP Server

**Choice:** `clj-nrepl-eval` is a CLI tool invoked via Bash, not an MCP (Model Context Protocol) server.

**Why:** Claude Code already has a Bash tool. A CLI tool requires zero additional protocol support. MCP servers add complexity (connection management, protocol negotiation) for no benefit here — the nREPL protocol already handles the complexity of talking to the JVM.

**Trade-off:** Each invocation of `clj-nrepl-eval` starts a new Babashka process (~50ms overhead). An MCP server would maintain a persistent connection with no startup cost. In practice, 50ms is negligible compared to JVM evaluation time.

### Automatic Hooks vs. Agent Discipline

**Choice:** Paren repair runs automatically via hooks, not by asking the agent to repair its own code.

**Why:** An agent cannot reliably detect its own bracket errors. It generated the code — it "thinks" it's correct. Asking it to verify leads to "looks fine to me" responses. Automatic hooks bypass this entirely.

**Trade-off:** Every Write/Edit of a Clojure file runs the repair tool, even when the file is already correct. The overhead is small (parinfer is fast), and the reliability gain is significant.

### Persistent Sessions vs. Fresh State

**Choice:** nREPL sessions persist across `clj-nrepl-eval` invocations.

**Why:** Without persistence, every evaluation would need to re-require all namespaces. A typical evaluation chain might be: require → define test data → call function → inspect result. With persistence, this works across four separate CLI invocations. Without persistence, each call would need to include every prior step.

**Trade-off:** Stale state. If the agent `def`'s a var and later the source changes, the old var definition remains in the session. The `:reload` convention mitigates this for namespaces, but ad-hoc `def`s can cause confusion. The `--reset-session` flag provides an escape hatch.

### Heredoc Syntax vs. Inline Arguments

**Choice:** The skill teaches heredoc syntax (`<<'EOF'`) as the preferred evaluation method.

**Why:** Bash's `!` expansion breaks Clojure functions like `swap!`, `reset!`, `assoc!`. The heredoc with single-quoted delimiter (`'EOF'`) disables all shell interpolation and expansion.

**Trade-off:** Heredoc syntax is more verbose than inline arguments for simple expressions. But it's always safe, and consistency (always use heredoc) is better than the agent having to decide when inline is safe.

### dev/user.clj vs. Separate Scripts

**Choice:** The REPL walkthrough lives in `dev/user.clj`, not in separate script files.

**Why:** The `user` namespace is automatically available in every REPL session. No explicit require needed. The `comment` blocks make it clear these are evaluation forms, not application code. This is the standard Clojure convention for REPL exploration.

**Trade-off:** The file is long (388 lines). But it's a teaching tool, not production code — length serves clarity.

---

## What This Enables

With all six components working together, Claude Code can:

**1. Explore a running system.**
Connect to the REPL, require namespaces, call functions, inspect state. The agent can understand not just what the code says, but what it *does*.

**2. Verify changes incrementally.**
After editing a file, immediately verify it compiles and behaves correctly — without running the full test suite. Fast feedback means fewer wrong turns.

**3. Write code that works on the first test run.**
By testing interactively at the REPL before running `bb test`, the agent catches most errors before the slow test cycle. This is the same advantage human Clojure developers have over developers in languages without a REPL.

**4. Recover from errors automatically.**
Bracket errors — the most common LLM failure mode in Clojure — are silently repaired by hooks before they can cause problems. The agent doesn't even know it made an error.

**5. Follow the same workflow as a human developer.**
Edit → reload → evaluate → inspect → iterate. The AI agent uses Clojure the way Clojure is meant to be used. This isn't just aesthetically satisfying — it produces better code because the agent gets the same feedback signals that guide human developers toward correct implementations.

**6. Start from zero.**
If no REPL is running, the agent can start one. If it doesn't know which port to use, it asks. If the REPL session is stale, it resets. The entire workflow is self-bootstrapping.

The combination of these capabilities means Claude Code working on a Clojure project with REPL integration is qualitatively different from Claude Code working on a Clojure project without it. It's the difference between writing code blind and writing code with your eyes open.
