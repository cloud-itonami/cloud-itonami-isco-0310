(ns enlisted-admin.actor
  "Enlisted Admin Actor — the ISCO-08 0310 Enlisted Administrative
  Support actor as a real `langgraph.graph/state-graph` (per
  ADR-2607011000 / CLAUDE.md Actors section). One graph run = one
  administrative request (intake → advise → govern → decide →
  commit/hold), with a GENUINE `interrupt-before` human-approval gate
  for escalated proposals — the graph pauses (checkpointed) at
  `:request-approval` and only continues on to `:commit` when a human
  operator explicitly resumes it via `approve!`.

  ```text
  :intake -> :advise -> :govern -> :decide -+-> :commit                        (:hard? false, :escalate? false)
                                             +-> :request-approval -> :commit    (:escalate? true, interrupt-before)
                                             +-> :hold                          (:hard? true)
  ```

  This replaces the previous `build-graph`, which called
  `graph/state-graph-builder` — a function that does not exist
  anywhere in `kotoba-lang/langgraph`'s history (confirmed directly
  against its source, `langgraph/graph.cljc`; the real builder API is
  `state-graph`/`add-node`/`add-edge`/`add-conditional-edges`/
  `set-entry-point`/`set-finish-point`/`compile-graph`) — and the
  previous `run-request!`, which was a literal stub returning
  `{:stub true :reason \"full langgraph.graph requires runtime
  binding\"}` and never touched a compiled graph at all. Mirrors
  `nco-admin.actor` (cloud-itonami-isco-0210, same domain family,
  fixed prior to this pass) module-for-module, with a genuine
  checkpointed interrupt/resume instead of an out-of-band manual
  commit call for `approve!`.

  The unconditional invariant: the Enlisted Admin Advisor can never
  directly write an administrative record the Enlisted Admin Governor
  refuses — every `store/add-record!` call happens in `:commit`/
  `:hold`, reached only through `:decide`."
  (:require [langgraph.graph :as graph]
            [langgraph.checkpoint :as cp]
            [enlisted-admin.store :as store]
            [enlisted-admin.advisor :as advisor]
            [enlisted-admin.governor :as governor]))

(defn- intake-node
  "Intake node: pass the request through untouched."
  [state]
  state)

(defn- advise-node
  "Advise node: Advisor proposes an operation. Returns only the
  channel delta (langgraph folds partial updates through the
  channel reducers, see `build-graph`'s `:channels`)."
  [advisor-instance {:keys [request context]}]
  {:proposal (advisor/propose advisor-instance request context)})

(defn- govern-node
  "Govern node: Governor evaluates the proposal against the store,
  independently of the advisor."
  [store-instance {:keys [request context proposal]}]
  {:decision (governor/check request context proposal store-instance)})

(defn- decide-node
  "Decide node: route based on governor verdict. Sets :disposition
  only — the conditional edge below reads it, no store write happens
  here."
  [{:keys [decision]}]
  {:disposition (cond
                  (:hard? decision)     :hold
                  (:escalate? decision) :request-approval
                  :else                 :commit)})

(defn- commit-node
  "Commit node: durably append the committed administrative record to
  the REAL audit ledger via `enlisted-admin.store/add-record!` — the
  core missing behavior this actor previously had (`add-record!`
  existed but was only ever called from tests, never from this
  graph)."
  [store-instance {:keys [request proposal]}]
  (let [op (:op proposal)
        store' (store/add-record! store-instance op
                                   {:enlisted-id (:enlisted-id request)
                                    :proposal proposal})]
    {:store store'
     :records [{:recorded true :op op}]}))

(defn- request-approval-node
  "Request-approval node: the `interrupt-before` gate. When the graph
  actually reaches (executes) this node, it's because a human operator
  resumed the thread via `approve!` — interrupt-before pauses BEFORE
  this node runs on the first pass, so reaching its body at all means
  human sign-off already happened. Falls straight through to :commit
  via the graph's own `:request-approval -> :commit` edge."
  [_state]
  {})

(defn- hold-node
  "Hold node: a HARD governance violation. Never writes the proposed
  record, but DOES durably append a `:held` audit fact to the same
  ledger `:commit` uses — so an enlisted member's full administrative
  history (including refused proposals) is always a query over the
  immutable ledger, not just the commits."
  [store-instance {:keys [request decision]}]
  (let [store' (store/add-record! store-instance :held
                                   {:enlisted-id (:enlisted-id request)
                                    :violations (:violations decision)})]
    {:store store'
     :records [{:held true :violations (:violations decision)}]}))

(defn build-graph
  "Build and compile the REAL `langgraph.graph` StateGraph for the
  enlisted admin actor, against `state-graph`/`add-node`/`add-edge`/
  `add-conditional-edges`/`set-entry-point`/`set-finish-point`/
  `compile-graph` — the actual exported API of `langgraph.graph`, not
  the nonexistent `state-graph-builder`. `checkpointer` defaults to an
  in-memory one (`langgraph.checkpoint/mem-checkpointer`) so
  `:request-approval` interrupts are genuinely resumable via
  `approve!`."
  [advisor-instance store-instance & [{:keys [checkpointer]
                                        :or {checkpointer (cp/mem-checkpointer)}}]]
  (-> (graph/state-graph
       {:channels
        {:request     {:default nil}
         :context     {:default nil}
         :proposal    {:default nil}
         :decision    {:default nil}
         :disposition {:default nil}
         :store       {:default nil}
         :records     {:reducer into :default []}}})

      (graph/add-node :intake intake-node)
      (graph/add-node :advise (partial advise-node advisor-instance))
      (graph/add-node :govern (partial govern-node store-instance))
      (graph/add-node :decide decide-node)
      (graph/add-node :commit (partial commit-node store-instance))
      (graph/add-node :request-approval request-approval-node)
      (graph/add-node :hold (partial hold-node store-instance))

      (graph/set-entry-point :intake)
      (graph/add-edge :intake :advise)
      (graph/add-edge :advise :govern)
      (graph/add-edge :govern :decide)

      (graph/add-conditional-edges :decide
        (fn [{:keys [disposition]}]
          (case disposition
            :commit           :commit
            :request-approval :request-approval
            :hold)))

      (graph/add-edge :request-approval :commit)

      (graph/set-finish-point :commit)
      (graph/set-finish-point :hold)

      (graph/compile-graph
       {:checkpointer     checkpointer
        :interrupt-before #{:request-approval}})))

(defn run-request!
  "Run one enlisted administrative request through the REAL compiled
  actor graph (`compiled-graph` from `build-graph`) via
  `langgraph.graph/run*`. `thread-id` scopes checkpointing so an
  escalated (interrupted) run can be resumed by `approve!`. Returns
  the full run result: `{:state .. :events .. :status :done|:interrupted
  :frontier ..}` — `:status :interrupted` with `:frontier
  [:request-approval]` means the request is genuinely paused awaiting
  human sign-off, not merely a `:phase` flag on an already-finished
  run."
  [compiled-graph request context thread-id]
  (graph/run* compiled-graph {:request request :context context}
              {:thread-id thread-id}))

(defn approve!
  "Human-in-the-loop resume: a human operator's approval of a request
  parked at `:request-approval` genuinely resumes the compiled graph
  (via `langgraph.graph/run*` with `:resume? true`), which runs the
  `:request-approval -> :commit` edge and so durably commits the
  record through the SAME `commit-node` a clean, non-escalated run
  uses — not a hand-rolled parallel commit path."
  [compiled-graph thread-id]
  (graph/run* compiled-graph nil {:thread-id thread-id :resume? true}))
