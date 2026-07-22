# cloud-itonami-isco-0310

Open Occupation Blueprint for **ISCO-08 0310**: Armed Forces Occupations, Other Ranks (Enlisted Personnel).

This repository designs a forkable OSS business for enlisted personnel administrative support: a document-handling and coordination robot manages training schedules, readiness reporting, administrative correspondence, and leave request intake under a governor-gated actor, so an enlisted member's administrative operations maintain a transparent, auditable record instead of relying on closed administrative systems.

## IMPORTANT: SCOPE BOUNDARIES

**This actor is EXPLICITLY NOT a command-authority tool, operational system, weapons system, or tactical decision-maker.**

### What this actor DOES

- Administrative and logistical operations for enlisted personnel:
  - Training schedule coordination and enrollment
  - Administrative readiness status logging and reporting
  - Administrative correspondence drafting and filing
  - Leave and administrative request intake and routing
  - Personnel record verification and administrative data management
  - Audit trail and compliance documentation

### What this actor DOES NOT (hard boundaries, permanently out of scope)

These operations are **permanently forbidden** — they are not gated by risk level or approval hierarchy, they cannot be escalated for human override, and the actor's proposal vocabulary has no path to construct them. A closed allowlist enforces this at the governance layer:

- **Personnel deployment, assignment, or operational command** — the actor has no notion of unit assignments, tactical decisions, personnel movement orders, or command authority
- **Weapons systems, munitions, targeting, or combat operations** — no procurement of weapons, explosives, or systems designed for combat; no engagement logic; no targeting parameters; no coordination of combat operations
- **Operational, classified, or real-time command data** — no access to classified intelligence, operational plans, real-time combat data, or command coordination channels
- **Lethal autonomous decisions** — no authority to make or propose any decision whose effect is kinetic, destructive, or results in loss of life
- **Personnel policy decisions or disciplinary authority** — no rank assignment, assignment of duties with command implications, or disciplinary actions

These are not "high-risk operations requiring escalation" — they are entirely outside the actor's design vocabulary. The governor will **permanently :hold** any proposal that touches these categories (it is not a matter of confidence, approval chain, or command clearance).

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot performs
the physical domain work**. Here a document-handling and coordination robot manages training schedules, readiness reporting, and administrative correspondence under an actor that proposes actions and an independent **Enlisted Admin Governor** that gates them. The governor never dispatches a robot action itself; `:high`/`:safety-critical` actions (such as readiness reports flagging below-threshold status, or leave requests during active-status periods) require human sign-off.

## Core Contract

```text
administrative request + personnel/unit identity + status data
        |
        v
Enlisted Admin Advisor -> Enlisted Admin Governor -> schedule training, log readiness, draft correspondence, or human escalation
        |
        v
robot actions (gated) + administrative record + audit ledger
```

No automated advice can dispatch an administrative action the governor refuses, schedule training outside registered curricula, or publish a readiness report without governor approval and audit evidence.

## Capability layer

Resolves via [`kotoba-lang/occupation`](https://github.com/kotoba-lang/occupation)
(ISCO-08 `0310`). Required capabilities:

- :robotics
- :identity
- :forms
- :dmn
- :bpmn
- :audit-ledger

## Reference implementation (`:maturity :implemented`)

Full itonami Actor pattern (per ADR-2607011000 / CLAUDE.md's Actors
section): a REAL, compiled
[`kotoba-lang/langgraph`](https://github.com/kotoba-lang/langgraph)
`StateGraph`, with the Advisor and Governor as distinct graph nodes and
GENUINE human-in-the-loop interrupt/resume via checkpointing (not a
`:phase` flag on an already-finished run). An earlier version of this
repo called a `graph/state-graph-builder` function that never existed
anywhere in `kotoba-lang/langgraph`'s history, and `run-request!` was a
literal stub (`{:stub true :reason "full langgraph.graph requires
runtime binding"}`) that never touched a compiled graph at all — both
went uncaught because no test exercised `enlisted-admin.actor`. That
gap is now closed (`test/enlisted_admin/actor_test.cljc`).

```text
:intake -> :advise -> :govern -> :decide -+-> :commit                        (:hard? false, :escalate? false)
                                           +-> :request-approval -> :commit    (:escalate? true, interrupt-before)
                                           +-> :hold                          (:hard? true)
```

- `src/enlisted_admin/store.cljc` — `Store` protocol + `MemStore` +
  `DatomicStore` (via [`kotoba-lang/langchain-store`](https://github.com/kotoba-lang/langchain-store),
  no hand-rolled EDN-blob codec): registered enlisted personnel/units,
  and the append-only audit ledger (`add-record!`/`records`). Both
  backends pass the same contract
  (`test/enlisted_admin/store_contract_test.cljc`).
- `src/enlisted_admin/advisor.cljc` — `Advisor` protocol; `mock-advisor`
  (deterministic, default) proposes an administrative operation from a
  request; `llm-advisor` wraps a `langchain.model/ChatModel` — either
  way the advisor only ever produces a `:propose`-effect proposal,
  never a committed record, and LLM parse failures always yield
  `confidence 0.0` (forces escalation, never fabricated confidence).
- `src/enlisted_admin/governor.cljc` — `EnlistedAdminGovernor/check`: a
  pure function, wired as its own `:govern` node. Hard invariants
  (unregistered enlisted member, a proposal whose `:effect` isn't `:propose`, any
  proposal touching deployment/command/weapons/classified operations) always
  route to `:hold`. Escalation invariants (readiness reports below threshold,
  leave requests during active-status periods, or low advisor confidence)
  always route to `:request-approval` — a genuine `interrupt-before` node
  the compiled graph pauses at (checkpointed) and only resumes past on
  explicit human approval (`actor/approve!`, which re-enters the SAME
  compiled graph via its own `:request-approval -> :commit` edge).
- `src/enlisted_admin/actor.cljc` — `build-graph`, `run-request!`,
  `approve!`: the REAL `langgraph.graph/state-graph` wiring
  (`state-graph`/`add-node`/`add-edge`/`add-conditional-edges`/
  `compile-graph`). BOTH `:commit` and `:hold` durably append to the
  real audit ledger (`store/add-record!`) — previously `add-record!`
  was dead code from this actor's point of view, only ever called from
  tests.

```bash
clojure -M:lint       # clj-kondo, 0 errors
clojure -M:dev:test    # 18 tests / 69 assertions, green
```

This is what backs this repo's `:maturity :implemented` entry in
[`kotoba-lang/occupation`](https://github.com/kotoba-lang/occupation).

## License

AGPL-3.0-or-later.
