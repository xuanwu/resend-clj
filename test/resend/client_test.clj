(ns resend.client-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [resend.client :as http]))

(deftest wire-key-conversion
  (testing "keyword keys become snake_case strings, recursively"
    (is (= {"reply_to" "a@b.c"
            "tags" [{"name" "category" "value" "welcome"}]}
           (http/->wire {:reply-to "a@b.c"
                         :tags [{:name "category" :value "welcome"}]}))))
  (testing "string keys (custom email headers) are untouched"
    (is (= {"headers" {"X-Entity-Ref-ID" "123"}}
           (http/->wire {:headers {"X-Entity-Ref-ID" "123"}}))))
  (testing "incoming snake_case becomes kebab-case keywords"
    (is (= :created-at (http/wire-key->kw "created_at")))
    (is (= {:last-event "delivered" :object "email"}
           (http/parse-json "{\"last_event\":\"delivered\",\"object\":\"email\"}")))))

(deftest parse-json-edge-cases
  (is (nil? (http/parse-json "")))
  (is (nil? (http/parse-json nil)))
  (is (= {:data [{:id "1"} {:id "2"}]}
         (http/parse-json "{\"data\":[{\"id\":\"1\"},{\"id\":\"2\"}]}"))))

(deftest client-construction
  (testing "string shorthand"
    (let [c (http/client "re_test_123")]
      (is (= "re_test_123" (:api-key c)))
      (is (= "https://api.resend.com" (:base-url c)))))
  (testing "trailing slashes on :base-url are stripped"
    (is (= "http://localhost:8080"
           (:base-url (http/client {:api-key "k" :base-url "http://localhost:8080/"})))))
  (testing "missing api key throws"
    (when-not (System/getenv "RESEND_API_KEY")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Missing Resend API key"
                            (http/client {}))))))

(deftest build-request-test
  (let [c (http/client "re_test_123")]
    (testing "auth and content-type headers, JSON body encoding"
      (let [{:keys [url method headers body]}
            (http/build-request c {:method :post
                                   :path "/emails"
                                   :body {:reply-to "a@b.c" :subject "hi"}})]
        (is (= "https://api.resend.com/emails" url))
        (is (= "POST" method))
        (is (= "Bearer re_test_123" (get headers "Authorization")))
        (is (= "application/json" (get headers "Content-Type")))
        (is (= {"reply_to" "a@b.c" "subject" "hi"}
               (json/read-str body)))))
    (testing "GET has no body or content-type"
      (let [{:keys [headers body]}
            (http/build-request c {:method :get :path "/domains"})]
        (is (nil? body))
        (is (not (contains? headers "Content-Type")))))
    (testing "query params are encoded and nils dropped"
      (is (= "https://api.resend.com/emails?limit=10&last-id=a%2Fb"
             (:url (http/build-request c {:method :get
                                          :path "/emails"
                                          :query-params {:limit 10
                                                         "last-id" "a/b"
                                                         :after nil}})))))
    (testing "extra headers are merged in"
      (is (= "abc" (-> (http/build-request c {:method :post
                                              :path "/emails"
                                              :body {}
                                              :headers {"Idempotency-Key" "abc"}})
                       :headers
                       (get "Idempotency-Key")))))))
