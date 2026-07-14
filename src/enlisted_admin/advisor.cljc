(ns enlisted-admin.advisor
  "Enlisted Admin Advisor — the proposal layer for the ISCO-08 0310
  Enlisted Administrative Support actor. Proposes administrative
  operations based on requests, but never commits records itself or
  makes governance decisions.")

(defprotocol Advisor
  "Advisor protocol for proposing administrative operations."
  (propose [advisor request context]
    "Propose an administrative operation from a request. Returns a proposal map
     with :op, :effect (always :propose), :confidence, and supporting data."))

(defn mock-advisor
  "Default deterministic advisor that proposes standard administrative
   operations based on request type. Always returns :propose effect and
   moderate-to-high confidence for valid requests."
  []
  (reify Advisor
    (propose [this request context]
      (let [req-type (:type request)
            op (case req-type
                 :schedule-training {:op :schedule-training
                                     :confidence 0.85
                                     :training-id (:training-id request)
                                     :curriculum (:curriculum request)}
                 :log-readiness {:op :log-readiness-report
                                :confidence 0.9
                                :status (:status request)
                                :notes (:notes request)}
                 :draft-correspondence {:op :draft-correspondence
                                       :confidence 0.8
                                       :corr-type (:corr-type request)
                                       :subject (:subject request)}
                 :process-leave {:op :process-leave-request
                                :confidence 0.75
                                :leave-type (:leave-type request)
                                :start-date (:start-date request)
                                :end-date (:end-date request)}
                 {:op :unknown :confidence 0.0})]
        (assoc op :effect :propose)))))

(defn llm-advisor
  "Advisor backed by an LLM (ChatModel). Always returns :propose effect;
   LLM parse failures yield confidence 0.0 (forces escalation)."
  [chat-model]
  (reify Advisor
    (propose [this request context]
      ; Placeholder: real implementation would call chat-model
      ; and parse response to extract :op, :confidence, etc.
      ; On any error, return confidence 0.0
      {:op :unknown :effect :propose :confidence 0.0})))
