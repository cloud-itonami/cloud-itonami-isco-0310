(ns enlisted-admin.store
  "Enlisted Admin Store — the append-only audit ledger and persistent
  state for the ISCO-08 0310 Enlisted Administrative Support actor.
  Implements the Store protocol for enlisted/unit identity verification
  and administrative record management.

  Two backends implement the same `Store` protocol so the backend is a
  swap, not a rewrite (the itonami actor pattern's injection boundary,
  ADR-2607011000; mirrors `nco-admin.store`, cloud-itonami-isco-0210 —
  the same domain family, already fixed):

    - `MemStore`     — a persistent (immutable) record wrapping plain
                       maps. The deterministic default for dev/tests/demo
                       (no deps); mutator methods return a NEW store.
    - `DatomicStore` — backed by `langchain.db`, a Datomic-API-compatible
                       EAV store (swappable to a kotoba-server pod in
                       production). Enlisted/unit records and ledger
                       entries carry free-form fields, so each is stored
                       as an EDN-blob payload via `langchain-store.core`
                       (`ls/enc`/`ls/dec*`), not a hand-rolled codec
                       (ADR-2607141600). Mutator methods return the SAME
                       store (the conn is already mutable), which is
                       still safe to `->`-thread the way the tests do.

  Both pass the same contract (test/enlisted_admin/store_contract_test.cljc).

  `add-record!`/`records` is the append-only audit ledger:
  `enlisted-admin.actor`'s `:commit`/`:hold` graph nodes append every
  committed administrative record AND every hard-governance-violation
  hold fact here, so an enlisted member's administrative history (every
  `:schedule-training` / `:log-readiness-report` /
  `:draft-correspondence` / `:process-leave-request` decision,
  committed or held) is always a query over an immutable log. Prior to
  this, `add-record!` was only ever called from tests — dead code from
  `enlisted-admin.actor`'s point of view."
  (:require [langchain.db :as d]
            [langchain-store.core :as ls]))

(defprotocol Store
  "Store protocol for enlisted admin actor state and audit ledger."
  (enlisted [store enlisted-id]
    "Retrieve an enlisted member record by ID. Returns nil if not found.")
  (unit [store unit-id]
    "Retrieve a unit record by ID. Returns nil if not found.")
  (register-enlisted! [store enlisted-id enlisted-data]
    "Register an enlisted member (adds to store, returns updated store).")
  (register-unit! [store unit-id unit-data]
    "Register a unit (adds to store, returns updated store).")
  (add-record! [store record-type record-data]
    "Append an immutable administrative record to the audit ledger.")
  (records [store]
    "Return all records in the audit ledger (immutable)."))

(defn- now-ms []
  #?(:clj (System/currentTimeMillis)
     :cljs (.getTime (js/Date.))))

(defrecord MemStore [enlisted-members units ledger]
  Store
  (enlisted [this enlisted-id]
    (get enlisted-members enlisted-id))
  (unit [this unit-id]
    (get units unit-id))
  (register-enlisted! [this enlisted-id enlisted-data]
    (MemStore. (assoc enlisted-members enlisted-id enlisted-data) units ledger))
  (register-unit! [this unit-id unit-data]
    (MemStore. enlisted-members (assoc units unit-id unit-data) ledger))
  (add-record! [this record-type record-data]
    (let [record (assoc record-data :type record-type :timestamp (now-ms))]
      (MemStore. enlisted-members units (conj ledger record))))
  (records [this]
    ledger))

(defn create-store
  "Create a new in-memory store for enlisted admin records."
  []
  (MemStore. {} {} []))

;; ----------------------------- DatomicStore (langchain.db) -----------------------------

(def ^:private schema
  "DataScript/Datomic-style schema: only constraint attrs are declared.
  `:enlisted/payload`/`:unit/payload` are opaque EDN-string blobs (via
  `langchain-store.core`) so `langchain.db` doesn't try to expand a
  caller-defined record into sub-entities — same convention as
  `nco-admin.store`'s `:nco/payload`/`:unit/payload`."
  (ls/identity-schema [:enlisted/id :unit/id :record/seq]))

(defn- blob-lookup
  "Look up the EDN-blob payload for the entity uniquely identified by
  `id-attr`/`id` and stored under `payload-attr`."
  [conn id-attr payload-attr id]
  (when id
    (ls/dec* (d/q {:find '[?p .] :in '[$ ?id]
                   :where [['?e id-attr '?id] ['?e payload-attr '?p]]}
                  (d/db conn) id))))

(defrecord DatomicStore [conn]
  Store
  (enlisted [_ enlisted-id]
    (blob-lookup conn :enlisted/id :enlisted/payload enlisted-id))
  (unit [_ unit-id]
    (blob-lookup conn :unit/id :unit/payload unit-id))
  (register-enlisted! [s enlisted-id enlisted-data]
    (d/transact! conn [{:enlisted/id enlisted-id :enlisted/payload (ls/enc enlisted-data)}])
    s)
  (register-unit! [s unit-id unit-data]
    (d/transact! conn [{:unit/id unit-id :unit/payload (ls/enc unit-data)}])
    s)
  (add-record! [s record-type record-data]
    (let [record (assoc record-data :type record-type :timestamp (now-ms))]
      (ls/append-blob! conn :record/seq :record/payload (count (records s)) record))
    s)
  (records [_]
    (ls/read-stream conn :record/seq :record/payload)))

(defn datomic-store
  "Create a new DatomicStore (langchain.db-backed) for enlisted admin
  records — the production-shaped backend for the same `Store`
  protocol `create-store`'s `MemStore` implements."
  []
  (->DatomicStore (d/create-conn schema)))
