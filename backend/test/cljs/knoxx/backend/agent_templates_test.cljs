(ns knoxx.backend.agent-templates-test
  (:require [cljs.test :refer [deftest is testing]]
            [knoxx.backend.domain.agent.agent-templates :as templates]))

(deftest render-prompt-renders-json-round-tripped-template-vector
  (testing "template forms survive JSON/JS round-trips that turn lists/symbols into vectors/strings"
    (is (= "alpha\n\nbeta\n\ngamma"
           (templates/render-prompt
            ["template" {:separator "\n\n"}
             ["alpha"
              ["template" {:separator "\n\n"} ["beta" "gamma"]]]]
            {}
            nil
            nil)))))

(deftest render-prompt-renders-json-round-tripped-expression-vectors
  (testing "nested keyword calls, conditionals, map, and fn forms survive JSON/JS vectorization"
    (is (= "- TRUSTED_DISCORD_INPUT hello\n- UNTRUSTED_DISCORD_QUOTE world"
           (templates/render-prompt
            ["template" {:separator "\n"}
             [["if" ["=" 0 ["count" ["source" "messages" "ctx"]]]
               "empty"
               ["template" {:separator "\n"}
                ["map" ["source" "messages" "ctx"]
                 ["fn" ["msg"]
                  ["template" {:separator " "}
                   ["-"
                    ["if" ["trusted?" "msg"] "TRUSTED_DISCORD_INPUT" "UNTRUSTED_DISCORD_QUOTE"]
                    ["text" "msg"]]]]]]]]]
            {}
            nil
            {:source {:messages [{:trusted? true :text "hello"}
                                  {:trusted? false :text "world"}]}})))))

(deftest render-prompt-evaluates-logical-template-forms
  (testing "and/or/not compose inside JSON-round-tripped template forms"
    (is (= "yes fallback"
           (templates/render-prompt
            ["template" {:separator " "}
             [["if" ["and" true ["not" false]] "yes" "no"]
              ["or" false nil "fallback"]]]
            {}
            nil
            nil)))))

(deftest render-prompt-preserves-ordinary-vector-as-data-string
  (testing "non-template vectors are not treated as executable forms"
    (is (= "[\"not-template\" \"x\"]"
           (templates/render-prompt ["not-template" "x"] {} nil nil)))))

(deftest legacy-email-aliases-retain-existing-priority
  (doseq [[auth expected]
          [[{:email "first" :user-email "second"} "first"]
           [{:user-email "second" :userEmail "third"} "second"]
           [{:userEmail "third" :user {:email "fourth"}} "third"]
           [{:user {:email "fourth" :user-email "fifth"}} "fourth"]
           [{:user {:user-email "fifth" :userEmail "sixth"}} "fifth"]
           [{:user {:userEmail "sixth"}} "sixth"]]]
    (is (= expected (templates/render-prompt "{ctx.email}" {} auth)))))

(deftest legacy-display-name-aliases-retain-email-fallback
  (doseq [[auth expected]
          [[{:name "first" :display-name "second"} "first"]
           [{:display-name "second" :displayName "third"} "second"]
           [{:displayName "third" :user {:name "fourth"}} "third"]
           [{:user {:name "fourth" :display-name "fifth"}} "fourth"]
           [{:user {:display-name "fifth" :displayName "sixth"}} "fifth"]
           [{:user {:displayName "sixth"} :email "fallback"} "sixth"]
           [{:email "fallback"} "fallback"]]]
    (is (= expected (templates/render-prompt "{ctx.name}" {} auth)))))

(deftest legacy-organization-and-membership-aliases-retain-priority
  (doseq [[auth expected]
          [[{:org-slug "first" :orgSlug "second"} "first"]
           [{:orgSlug "second" :org {:slug "third"}} "second"]
           [{:org {:slug "third" :org-slug "fourth"}} "third"]
           [{:org {:org-slug "fourth" :orgSlug "fifth"}} "fourth"]
           [{:org {:orgSlug "fifth"}} "fifth"]]]
    (is (= expected (templates/render-prompt "{ctx.org.slug}" {} auth))))
  (doseq [[auth expected]
          [[{:membership-id "first" :membershipId "second"} "first"]
           [{:membershipId "second" :membership {:id "third"}} "second"]
           [{:membership {:id "third"}} "third"]]]
    (is (= expected (templates/render-prompt "{ctx.membership.id}" {} auth)))))

(deftest legacy-auth-retains-terminal-false-and-empty-values
  (is (= "false|false|false|false"
         (templates/render-prompt
          "{ctx.name}|{ctx.email}|{ctx.org.slug}|{ctx.membership.id}" {}
          {:user {:userEmail false} :org {:orgSlug false} :membership {:id false}})))
  (is (= "|||"
         (templates/render-prompt
          "{ctx.name}|{ctx.email}|{ctx.org.slug}|{ctx.membership.id}" {}
          {:name "" :email "" :org-slug "" :membership-id ""})))
  (is (= "fallback" (templates/render-prompt
                     "{ctx.email}" {} {:email false :user-email nil :userEmail "fallback"}))))

(deftest legacy-template-context-overrides-and-missing-values-remain-visible
  (is (= "override|false|member|{ctx.unknown}"
         (templates/render-prompt
          "{ctx.email}|{ctx.org.slug}|{ctx.membership.id}|{ctx.unknown}"
          {} {:email "auth" :org-slug "auth-org" :membership-id "auth-member"}
          {:email "override" :org {:slug false} :membership {:id "member"}})))
  (is (= "{ctx.email}" (templates/render-prompt "{ctx.email}" {} nil))))

(deftest trusted-get-preserves-key-precedence-and-present-falsy-values
  (is (false? (templates/eval-template-form '(get {:flag false} :flag "missing") {})))
  (is (nil? (templates/eval-template-form '(get {:flag nil} :flag "missing") {})))
  (is (= "missing" (templates/eval-template-form '(get {} :flag "missing") {})))
  (is (= "string" (templates/eval-template-form '(get {:flag "keyword" "flag" "string"} "flag") {})))
  (is (= "keyword" (templates/eval-template-form '(get {:flag "keyword" "flag" "string"} :flag) {})))
  (is (= "qualified" (templates/eval-template-form '(get {:scope/flag "qualified" :flag "plain"} :scope/flag) {}))))

(deftest render-prompt-retains-live-public-evaluator-lookup
  (with-redefs [templates/eval-template-form (fn [_ _] "dynamic")]
    (is (= "dynamic" (templates/render-prompt '(template ["original"]) {} nil)))))

(deftest trusted-get-keeps-reduced-defaults-outside-alias-search
  (let [marker (reduced :payload)
        env {'fallback marker}]
    (is (identical? marker
                    (templates/eval-template-form '(get {} :absent fallback) env)))
    (is (= "actual"
           (templates/eval-template-form '(get {:flag "actual"} "flag" fallback) env)))
    (is (= "false-key"
           (templates/eval-template-form '(get {false "false-key"} false fallback) env)))))

(deftest trusted-get-retains-found-reduced-nil-and-false-values
  (let [marker (reduced :payload)
        env {'haystack {:flag marker} 'fallback (reduced :unused)}]
    (is (identical? marker
                    (templates/eval-template-form '(get haystack :flag fallback) env)))
    (is (nil? (templates/eval-template-form '(get {:flag nil} "flag" fallback) env)))
    (is (false? (templates/eval-template-form '(get {:flag false} "flag" fallback) env)))))
