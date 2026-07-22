(ns enlisted-admin.actor-test
  "Integration tests for `enlisted-admin.actor` — builds the REAL
  compiled `langgraph.graph` and runs `run-request!`/`approve!`
  end-to-end through all three terminal routes (commit /
  escalate-then-approve / hold). This namespace did not exist before:
  `build-graph` previously called `graph/state-graph-builder`, a
  function that does not exist anywhere in `kotoba-lang/langgraph`,
  and `run-request!` was an explicit stub — both went uncaught for as
  long as they did precisely because no test ever exercised this
  namespace. These tests close that gap and prove the audit ledger
  (`enlisted-admin.store/add-record!`) is genuinely wired into the
  `:commit`/`:hold` nodes."
  (:require [clojure.test :refer [deftest is testing]]
            [enlisted-admin.actor :as actor]
            [enlisted-admin.advisor :as advisor]
            [enlisted-admin.store :as store]))

(defn- fresh-store []
  (-> (store/create-store)
      (store/register-enlisted! "enl-001" {:name "Test Enlisted"})))

(deftest run-request-commits-clean-proposal
  (testing "a valid, high-confidence, nominal readiness report runs the
            real compiled graph end to end and reaches :done"
    (let [st (fresh-store)
          g (actor/build-graph (advisor/mock-advisor) st)
          result (actor/run-request! g {:enlisted-id "enl-001" :type :log-readiness
                                         :status :nominal :notes "all clear"}
                                      {} "thread-commit-1")
          state (:state result)]
      (is (= :done (:status result)))
      (is (= [{:recorded true :op :log-readiness-report}] (:records state)))
      (is (false? (:hard? (:decision state))))
      (is (false? (:escalate? (:decision state))))
      (testing "the commit is genuinely durable in the store's real audit
                ledger (`enlisted-admin.store/add-record!`), not just the
                transient graph-state `:records` mirror"
        (let [ledger (store/records (:store state))]
          (is (= 1 (count ledger)))
          (is (= :log-readiness-report (:type (first ledger))))
          (is (= "enl-001" (:enlisted-id (first ledger)))))))))

(deftest run-request-holds-unregistered-enlisted
  (testing "an unregistered enlisted member is a HARD violation -- the
            real graph routes to :hold and terminates, never :commit"
    (let [st (fresh-store)
          g (actor/build-graph (advisor/mock-advisor) st)
          result (actor/run-request! g {:enlisted-id "no-such-enlisted"
                                         :type :log-readiness :status :nominal}
                                      {} "thread-hold-1")
          state (:state result)]
      (is (= :done (:status result)))
      (is (true? (:hard? (:decision state))))
      (is (= [{:held true :violations (:violations (:decision state))}] (:records state))
          "no COMMITTED record -- only the transient :held audit trace")
      (testing "the HARD violation is ALSO durably recorded to the real
                audit ledger by the :hold node -- previously :hold did
                nothing observable at all"
        (let [ledger (store/records (:store state))]
          (is (= 1 (count ledger)))
          (is (= :held (:type (first ledger))))
          (is (= "no-such-enlisted" (:enlisted-id (first ledger))))
          (is (seq (:violations (first ledger)))))))))

(deftest run-request-escalates-then-approve-commits
  (testing "draft-correspondence always escalates -- the real graph
            GENUINELY interrupts (checkpointed) at :request-approval
            and stops there; a human approve! resumes the SAME
            compiled graph and commits the record via the actual
            :request-approval -> :commit edge, not a hand-rolled
            parallel commit path"
    (let [st (fresh-store)
          g (actor/build-graph (advisor/mock-advisor) st)
          held (actor/run-request! g {:enlisted-id "enl-001"
                                       :type :draft-correspondence
                                       :corr-type :memo :subject "test"}
                                    {} "thread-escalate-1")
          held-state (:state held)]
      (is (= :interrupted (:status held)))
      (is (= [:request-approval] (:frontier held)))
      (is (true? (:escalate? (:decision held-state))))
      (is (empty? (:records held-state)) "not yet committed -- awaiting human sign-off")
      (is (empty? (store/records st)) "the ORIGINAL store has no record yet either")
      (let [approved (actor/approve! g "thread-escalate-1")
            approved-state (:state approved)]
        (is (= :done (:status approved)))
        (is (= [{:recorded true :op :draft-correspondence}] (:records approved-state)))
        (testing "approve! also genuinely persists to the real audit ledger"
          (let [ledger (store/records (:store approved-state))]
            (is (= 1 (count ledger)))
            (is (= :draft-correspondence (:type (first ledger))))))))))

(deftest run-request-low-confidence-forbidden-op-holds
  (testing "confidence below the governor's floor also escalates in
            isolation, but an unrecognized request :type maps (via
            mock-advisor) to {:op :unknown :confidence 0.0} -- :unknown
            is ALSO a forbidden op, so the real compiled graph takes the
            HARD (forbidden-op) route, matching governor.cljc's own
            priority order (hard beats escalate)"
    (let [st (fresh-store)
          g (actor/build-graph (advisor/mock-advisor) st)
          result (actor/run-request! g {:enlisted-id "enl-001" :type :bogus}
                                      {} "thread-bogus-1")
          state (:state result)]
      (is (= :done (:status result)))
      (is (true? (:hard? (:decision state))))
      (is (= 1 (count (:records state))))
      (is (true? (:held (first (:records state))))))))
