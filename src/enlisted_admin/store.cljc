(ns enlisted-admin.store
  "Enlisted Admin Store — the append-only audit ledger and persistent
  state for the ISCO-08 0310 Enlisted Administrative Support actor.
  Implements the Store protocol for enlisted/unit identity verification
  and administrative record management.")

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
    (let [record (assoc record-data :type record-type :timestamp (System/currentTimeMillis))]
      (MemStore. enlisted-members units (conj ledger record))))
  (records [this]
    ledger))

(defn create-store
  "Create a new in-memory store for enlisted admin records."
  []
  (MemStore. {} {} []))
