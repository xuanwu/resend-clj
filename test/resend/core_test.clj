(ns resend.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [resend.client :as http]
            [resend.core :as resend])
  (:import (java.io ByteArrayInputStream)
           (java.util Base64)))

(def test-client (resend/client "re_test_123"))

(defn- capture
  "Runs f with resend.client/request! redefined to record its request map,
  returning the captured request."
  [f]
  (let [captured (atom nil)]
    (with-redefs [http/request! (fn [_client req] (reset! captured req) {:id "stub"})]
      (f))
    @captured))

(deftest send!-test
  (testing "posts to /emails with the email as body"
    (let [req (capture #(resend/send! test-client {:from "a@b.c"
                                                   :to ["x@y.z"]
                                                   :subject "hi"
                                                   :html "<b>hi</b>"}))]
      (is (= :post (:method req)))
      (is (= "/emails" (:path req)))
      (is (= "hi" (get-in req [:body :subject])))
      (is (nil? (:headers req)))))
  (testing "idempotency key becomes a header"
    (let [req (capture #(resend/send! test-client
                                      {:from "a@b.c" :to "x@y.z" :subject "s" :text "t"}
                                      {:idempotency-key "welcome/42"}))]
      (is (= {"Idempotency-Key" "welcome/42"} (:headers req))))))

(deftest attachment-normalization
  (let [bs (.getBytes "hello attachment" "UTF-8")
        expected (.encodeToString (Base64/getEncoder) bs)]
    (testing "byte-array content is base64 encoded"
      (let [req (capture #(resend/send! test-client
                                        {:from "a@b.c" :to "x@y.z" :subject "s" :text "t"
                                         :attachments [{:filename "a.txt" :content bs}]}))]
        (is (= expected (get-in req [:body :attachments 0 :content])))))
    (testing "InputStream content is base64 encoded"
      (let [req (capture #(resend/send! test-client
                                        {:from "a@b.c" :to "x@y.z" :subject "s" :text "t"
                                         :attachments [{:filename "a.txt"
                                                        :content (ByteArrayInputStream. bs)}]}))]
        (is (= expected (get-in req [:body :attachments 0 :content])))))
    (testing "string content is assumed already encoded and left alone"
      (let [req (capture #(resend/send! test-client
                                        {:from "a@b.c" :to "x@y.z" :subject "s" :text "t"
                                         :attachments [{:filename "a.txt" :content expected}]}))]
        (is (= expected (get-in req [:body :attachments 0 :content])))))
    (testing "remote :path attachments pass through"
      (let [req (capture #(resend/send! test-client
                                        {:from "a@b.c" :to "x@y.z" :subject "s" :text "t"
                                         :attachments [{:path "https://x.y/a.pdf"}]}))]
        (is (= {:path "https://x.y/a.pdf"}
               (get-in req [:body :attachments 0])))))))

(deftest send-batch!-test
  (let [emails [{:from "a@b.c" :to "x@y.z" :subject "1" :text "t"}
                {:from "a@b.c" :to "q@y.z" :subject "2" :text "t"}]
        req (capture #(resend/send-batch! test-client emails))]
    (is (= "/emails/batch" (:path req)))
    (is (= 2 (count (:body req))))
    (is (vector? (:body req)))))

(deftest email-lifecycle-paths
  (is (= {:method :get :path "/emails/e-1"}
         (capture #(resend/get-email test-client "e-1"))))
  (is (= {:method :patch :path "/emails/e-1" :body {:scheduled-at "in 1 hour"}}
         (capture #(resend/update-email! test-client "e-1" {:scheduled-at "in 1 hour"}))))
  (is (= {:method :post :path "/emails/e-1/cancel"}
         (capture #(resend/cancel-email! test-client "e-1")))))

(deftest domain-paths
  (is (= {:method :post :path "/domains" :body {:name "mail.x.dev"}}
         (capture #(resend/create-domain! test-client {:name "mail.x.dev"}))))
  (is (= {:method :post :path "/domains/d-1/verify"}
         (capture #(resend/verify-domain! test-client "d-1"))))
  (is (= {:method :delete :path "/domains/d-1"}
         (capture #(resend/delete-domain! test-client "d-1")))))

(deftest contact-paths
  (is (= {:method :post
          :path "/audiences/aud-1/contacts"
          :body {:email "x@y.z" :first-name "X"}}
         (capture #(resend/create-contact! test-client "aud-1"
                                           {:email "x@y.z" :first-name "X"}))))
  (testing "contacts are addressable by email as well as id"
    (is (= {:method :patch
            :path "/audiences/aud-1/contacts/x@y.z"
            :body {:unsubscribed true}}
           (capture #(resend/update-contact! test-client "aud-1" "x@y.z"
                                             {:unsubscribed true}))))))

(deftest broadcast-paths
  (is (= {:method :post :path "/broadcasts/b-1/send"}
         (capture #(resend/send-broadcast! test-client "b-1"))))
  (is (= {:method :post
          :path "/broadcasts/b-1/send"
          :body {:scheduled-at "in 3 days"}}
         (capture #(resend/send-broadcast! test-client "b-1"
                                           {:scheduled-at "in 3 days"})))))
