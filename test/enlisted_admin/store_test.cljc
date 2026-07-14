(ns enlisted-admin.store-test
  (:require [clojure.test :refer [deftest is testing]]
            [enlisted-admin.store :as store]))

(deftest test-create-store
  (testing "Create empty in-memory store"
    (let [s (store/create-store)]
      (is (empty? (store/records s))))))

(deftest test-register-enlisted
  (testing "Register and retrieve enlisted member"
    (let [s (-> (store/create-store)
               (store/register-enlisted! "enl-001" {:name "Alice" :rank "E-5"}))
          member (store/enlisted s "enl-001")]
      (is (= "Alice" (:name member)))
      (is (= "E-5" (:rank member))))))

(deftest test-register-unit
  (testing "Register and retrieve unit"
    (let [s (-> (store/create-store)
               (store/register-unit! "unit-001" {:name "Alpha Company"}))
          unit (store/unit s "unit-001")]
      (is (= "Alpha Company" (:name unit))))))

(deftest test-add-record
  (testing "Add administrative record to audit ledger"
    (let [s (-> (store/create-store)
               (store/register-enlisted! "enl-001" {:name "Alice"})
               (store/add-record! :training-schedule {:training-id "T-101" :status "enrolled"}))
          records (store/records s)]
      (is (= 1 (count records)))
      (is (= :training-schedule (:type (first records))))
      (is (= "T-101" (:training-id (first records)))))))

(deftest test-audit-ledger-immutable
  (testing "Audit ledger is append-only (no modification of existing records)"
    (let [s1 (-> (store/create-store)
                (store/add-record! :event {:op "op1"}))
          s2 (store/add-record! s1 :event {:op "op2"})
          records-s1 (store/records s1)
          records-s2 (store/records s2)]
      (is (= 1 (count records-s1)))
      (is (= 2 (count records-s2)))
      (is (= "op1" (:op (first records-s1)))))))
