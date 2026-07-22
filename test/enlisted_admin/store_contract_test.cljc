(ns enlisted-admin.store-contract-test
  "MemStore ≡ DatomicStore parity for the Store protocol — proves the
  backend swap (ADR-2607011000 injection boundary) is real: the same
  sequence of operations against either backend produces the same
  observable results. Mirrors `nco-admin.store-contract-test`
  (cloud-itonami-isco-0210)."
  (:require [clojure.test :refer [deftest is testing]]
            [enlisted-admin.store :as store]))

(defn- exercise
  "Runs the same op sequence against `s` and reads back through
  whatever store the LAST op returned -- required because MemStore is
  immutable (each mutator returns a NEW store; the original binding
  never changes) while DatomicStore mutates its conn in place and
  returns the same store either way. Threading through `->` and
  reading from the final result observes the same committed state on
  both backends."
  [s]
  (let [s (-> s
              (store/register-enlisted! "enl-001" {:name "Sergeant Smith" :rank "E-5"})
              (store/register-unit! "unit-001" {:name "Alpha Company" :strength 100})
              (store/add-record! :readiness-report {:enlisted-id "enl-001" :status "nominal"})
              (store/add-record! :held {:enlisted-id "no-such-enlisted" :violations [{:rule :no-enlisted}]}))]
    {:enlisted (store/enlisted s "enl-001")
     :unit (store/unit s "unit-001")
     :absent (store/enlisted s "no-such-enlisted")
     :records (store/records s)}))

(deftest mem-and-datomic-parity
  (testing "same operations against MemStore and DatomicStore observe the same results"
    (let [mem (exercise (store/create-store))
          dat (exercise (store/datomic-store))]
      (is (= "Sergeant Smith" (:name (:enlisted mem))))
      (is (= "Sergeant Smith" (:name (:enlisted dat))))
      (is (= "E-5" (:rank (:enlisted mem))))
      (is (= "E-5" (:rank (:enlisted dat))))
      (is (= "Alpha Company" (:name (:unit mem))))
      (is (= "Alpha Company" (:name (:unit dat))))
      (is (nil? (:absent mem)))
      (is (nil? (:absent dat)))
      (is (= 2 (count (:records mem))))
      (is (= 2 (count (:records dat))))
      (is (= :readiness-report (:type (first (:records mem)))))
      (is (= :readiness-report (:type (first (:records dat)))))
      (is (= :held (:type (second (:records mem)))))
      (is (= :held (:type (second (:records dat)))))
      (is (some? (:timestamp (first (:records mem)))))
      (is (some? (:timestamp (first (:records dat))))))))

(deftest datomic-store-nil-lookups
  (testing "unregistered enlisted/unit lookups are nil on the DatomicStore too"
    (let [dat (store/datomic-store)]
      (is (nil? (store/enlisted dat "no-such-enlisted")))
      (is (nil? (store/unit dat "no-such-unit")))
      (is (empty? (store/records dat))))))
