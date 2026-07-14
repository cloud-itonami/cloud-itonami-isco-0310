(ns enlisted-admin.actor
  "Enlisted Admin Actor — the langgraph StateGraph wiring and runtime
  for the ISCO-08 0310 Enlisted Administrative Support actor.
  Implements the itonami actor pattern with Advisor/Governor separation,
  human-in-the-loop interrupts, and append-only audit trail."
  (:require [langgraph.graph :as graph]
            [enlisted-admin.store :as store]
            [enlisted-admin.advisor :as advisor]
            [enlisted-admin.governor :as governor]))

(def default-state
  {:phase :intake
   :request nil
   :context {}
   :proposal nil
   :decision nil
   :error nil})

(defn- intake-node
  "Intake node: accept and validate incoming request."
  [state]
  (assoc state :phase :advise))

(defn- advise-node
  "Advise node: Advisor proposes an operation."
  [state advisor-instance]
  (let [request (:request state)
        context (:context state)
        proposal (advisor/propose advisor-instance request context)]
    (assoc state :proposal proposal :phase :govern)))

(defn- govern-node
  "Govern node: Governor evaluates the proposal."
  [state store-instance]
  (let [request (:request state)
        context (:context state)
        proposal (:proposal state)
        verdict (governor/check request context proposal store-instance)]
    (assoc state :decision verdict :phase :decide)))

(defn- decide-node
  "Decide node: Route based on governor verdict."
  [state]
  (let [decision (:decision state)]
    (cond
      (:hard? decision)      (assoc state :phase :hold)
      (:escalate? decision)  (assoc state :phase :request-approval)
      true                   (assoc state :phase :commit))))

(defn- commit-node
  "Commit node: Store the proposal as a record."
  [state store-instance]
  (let [proposal (:proposal state)
        op (:op proposal)]
    (-> state
        (assoc :phase :complete)
        (update :records (fn [r] (conj (or r []) {:recorded true :op op}))))))

(defn- request-approval-node
  "Request approval node: interrupt-before checkpoint for human review."
  [state]
  (assoc state :phase :awaiting-approval))

(defn- hold-node
  "Hold node: Reject the proposal (hard violation)."
  [state]
  (assoc state
         :phase :rejected
         :error (str "Hard governance violation: " (-> state :decision :violations))))

(defn build-graph
  "Build and compile the StateGraph for the enlisted admin actor."
  [advisor-instance store-instance]
  (let [graph-def (graph/state-graph-builder
                    {:initial :intake
                     :nodes {:intake (fn [s] (intake-node s))
                             :advise (fn [s] (advise-node s advisor-instance))
                             :govern (fn [s] (govern-node s store-instance))
                             :decide (fn [s] (decide-node s))
                             :commit (fn [s] (commit-node s store-instance))
                             :request-approval (fn [s] (request-approval-node s))
                             :hold (fn [s] (hold-node s))}
                     :edges [{:from :intake :to :advise}
                             {:from :advise :to :govern}
                             {:from :govern :to :decide}
                             {:from :decide :to :commit}
                             {:from :decide :to :request-approval}
                             {:from :decide :to :hold}]
                     :end-nodes [:complete :awaiting-approval :rejected]})]
    graph-def))

(defn run-request!
  "Run an administrative request through the actor graph.
   Returns the final state."
  [graph initial-request context store]
  (let [state (assoc default-state :request initial-request :context context)]
    ; In a real implementation, this would invoke the compiled graph
    ; with checkpointing support for human-in-the-loop escalations.
    ; For now, return the state with a note that full graph is needed.
    (assoc state :stub true :reason "full langgraph.graph requires runtime binding")))

(defn approve!
  "Approve a request that was held in :request-approval phase.
   Human sign-off for escalation invariants."
  [state approval-context store]
  (assoc state :phase :commit :approval approval-context))
