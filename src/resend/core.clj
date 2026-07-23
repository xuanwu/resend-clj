(ns resend.core
  "Idiomatic Clojure wrapper for the Resend email API.

  Create a client once and pass it to every call:

    (require '[resend.core :as resend])

    (def client (resend/client \"re_123...\"))   ; or (resend/client) to use
                                                 ; the RESEND_API_KEY env var

    (resend/send! client
      {:from    \"Acme <onboarding@acme.dev>\"
       :to      [\"user@example.com\"]
       :subject \"Hello\"
       :html    \"<strong>It works!</strong>\"})

  Conventions:

  * All maps use kebab-case keywords (:reply-to, :scheduled-at, ...) and are
    converted to the snake_case wire format automatically. String keys are
    passed through untouched, so custom email headers keep their exact names.
  * Responses are maps with kebab-case keywords. List endpoints return the
    raw API shape, e.g. {:object \"list\" :data [...]}.
  * Functions with side effects end in `!`. All calls are synchronous.
  * API errors throw ex-info; see `resend.client` for the error data shape.
  * Endpoints without a wrapper here can be reached with
    `resend.client/request!`."
  (:require [resend.client :as http])
  (:import (java.io File InputStream)
           (java.nio.file Files)
           (java.util Base64)))

(def client
  "Creates a Resend client from an API key string or an options map.
  See `resend.client/client` for all options."
  http/client)

;; ---------------------------------------------------------------------------
;; Attachments

(defn- base64 ^String [^bytes bs]
  (.encodeToString (Base64/getEncoder) bs))

(defn- content->base64
  "Coerces attachment :content to a base64 string. Accepts a byte array,
  java.io.File, InputStream, or an already-encoded base64 String."
  [content]
  (cond
    (nil? content) nil
    (string? content) content
    (bytes? content) (base64 content)
    (instance? File content) (base64 (Files/readAllBytes (.toPath ^File content)))
    (instance? InputStream content) (base64 (.readAllBytes ^InputStream content))
    :else (throw (ex-info "Unsupported attachment :content type"
                          {:type ::invalid-attachment :content-class (class content)}))))

(defn- normalize-attachment [attachment]
  (if-let [content (:content attachment)]
    (assoc attachment :content (content->base64 content))
    attachment))

(defn- normalize-email [email]
  (cond-> email
    (seq (:attachments email))
    (update :attachments #(mapv normalize-attachment %))))

;; ---------------------------------------------------------------------------
;; Emails

(defn send!
  "Sends an email. Required keys: :from, :to, :subject, and at least one of
  :html or :text.

    :from         \"Name <sender@domain.com>\" (domain must be verified)
    :to           string or vector of strings (max 50)
    :cc, :bcc     string or vector of strings
    :reply-to     string or vector of strings
    :subject      string
    :html, :text  body variants
    :headers      map of custom headers, e.g. {\"X-Entity-Ref-ID\" \"123\"}
    :attachments  vector of {:filename ... :content ...} or {:path <url>};
                  :content may be a byte array, File, InputStream, or an
                  already base64-encoded string
    :tags         vector of {:name ... :value ...}
    :scheduled-at natural language (\"in 1 hour\") or ISO-8601 string

  Options map (second argument, optional):

    :idempotency-key  string (unique for up to 24h) to make retries safe

  Returns {:id \"...\"}."
  ([client email] (send! client email nil))
  ([client email {:keys [idempotency-key]}]
   (http/request! client
                  (cond-> {:method :post
                           :path "/emails"
                           :body (normalize-email email)}
                    idempotency-key (assoc :headers {"Idempotency-Key" idempotency-key})))))

(def send-email!
  "Alias for `send!`."
  send!)

(defn send-batch!
  "Sends up to 100 emails in one call. Each element takes the same shape as
  `send!` (attachments, tags and scheduling are not supported by the batch
  endpoint). Accepts the same options map as `send!` (:idempotency-key).

  Returns {:data [{:id ...} ...]}."
  ([client emails] (send-batch! client emails nil))
  ([client emails {:keys [idempotency-key]}]
   (http/request! client
                  (cond-> {:method :post
                           :path "/emails/batch"
                           :body (mapv normalize-email emails)}
                    idempotency-key (assoc :headers {"Idempotency-Key" idempotency-key})))))

(defn get-email
  "Retrieves a single email by id."
  [client email-id]
  (http/request! client {:method :get :path (str "/emails/" email-id)}))

(defn update-email!
  "Updates a scheduled email, e.g. {:scheduled-at \"2026-08-05T11:52:01.858Z\"}."
  [client email-id updates]
  (http/request! client {:method :patch
                         :path (str "/emails/" email-id)
                         :body updates}))

(defn cancel-email!
  "Cancels a scheduled email."
  [client email-id]
  (http/request! client {:method :post :path (str "/emails/" email-id "/cancel")}))

;; ---------------------------------------------------------------------------
;; Domains

(defn create-domain!
  "Registers a sending domain, e.g. {:name \"mail.example.com\"}.
  Optional: :region (\"us-east-1\", \"eu-west-1\", \"sa-east-1\"),
  :custom-return-path. Returns the domain including DNS :records to set up."
  [client domain]
  (http/request! client {:method :post :path "/domains" :body domain}))

(defn list-domains
  [client]
  (http/request! client {:method :get :path "/domains"}))

(defn get-domain
  [client domain-id]
  (http/request! client {:method :get :path (str "/domains/" domain-id)}))

(defn update-domain!
  "Updates domain settings, e.g. {:open-tracking true :click-tracking true :tls \"enforced\"}."
  [client domain-id updates]
  (http/request! client {:method :patch
                         :path (str "/domains/" domain-id)
                         :body updates}))

(defn verify-domain!
  "Triggers verification of the domain's DNS records."
  [client domain-id]
  (http/request! client {:method :post :path (str "/domains/" domain-id "/verify")}))

(defn delete-domain!
  [client domain-id]
  (http/request! client {:method :delete :path (str "/domains/" domain-id)}))

;; ---------------------------------------------------------------------------
;; API keys

(defn create-api-key!
  "Creates an API key, e.g. {:name \"production\"}. Optional :permission
  (\"full_access\" or \"sending_access\") and :domain-id to restrict a
  sending key to one domain. The returned :token is only shown once."
  [client api-key]
  (http/request! client {:method :post :path "/api-keys" :body api-key}))

(defn list-api-keys
  [client]
  (http/request! client {:method :get :path "/api-keys"}))

(defn delete-api-key!
  [client api-key-id]
  (http/request! client {:method :delete :path (str "/api-keys/" api-key-id)}))

;; ---------------------------------------------------------------------------
;; Audiences

(defn create-audience!
  "Creates an audience (a contact list), e.g. {:name \"Newsletter\"}."
  [client audience]
  (http/request! client {:method :post :path "/audiences" :body audience}))

(defn list-audiences
  [client]
  (http/request! client {:method :get :path "/audiences"}))

(defn get-audience
  [client audience-id]
  (http/request! client {:method :get :path (str "/audiences/" audience-id)}))

(defn delete-audience!
  [client audience-id]
  (http/request! client {:method :delete :path (str "/audiences/" audience-id)}))

;; ---------------------------------------------------------------------------
;; Contacts

(defn create-contact!
  "Adds a contact to an audience. Requires :email; optional :first-name,
  :last-name, :unsubscribed."
  [client audience-id contact]
  (http/request! client {:method :post
                         :path (str "/audiences/" audience-id "/contacts")
                         :body contact}))

(defn list-contacts
  [client audience-id]
  (http/request! client {:method :get
                         :path (str "/audiences/" audience-id "/contacts")}))

(defn get-contact
  "Retrieves a contact by id or by email address."
  [client audience-id id-or-email]
  (http/request! client {:method :get
                         :path (str "/audiences/" audience-id "/contacts/" id-or-email)}))

(defn update-contact!
  "Updates a contact (looked up by id or email), e.g. {:unsubscribed true}."
  [client audience-id id-or-email updates]
  (http/request! client {:method :patch
                         :path (str "/audiences/" audience-id "/contacts/" id-or-email)
                         :body updates}))

(defn delete-contact!
  "Deletes a contact by id or by email address."
  [client audience-id id-or-email]
  (http/request! client {:method :delete
                         :path (str "/audiences/" audience-id "/contacts/" id-or-email)}))

;; ---------------------------------------------------------------------------
;; Broadcasts

(defn create-broadcast!
  "Creates a broadcast to an audience. Requires :audience-id, :from,
  :subject and :html or :text; optional :name, :reply-to, :preview-text.
  The html/text may use template variables like {{{FIRST_NAME|there}}} and
  {{{RESEND_UNSUBSCRIBE_URL}}}."
  [client broadcast]
  (http/request! client {:method :post :path "/broadcasts" :body broadcast}))

(defn list-broadcasts
  [client]
  (http/request! client {:method :get :path "/broadcasts"}))

(defn get-broadcast
  [client broadcast-id]
  (http/request! client {:method :get :path (str "/broadcasts/" broadcast-id)}))

(defn update-broadcast!
  [client broadcast-id updates]
  (http/request! client {:method :patch
                         :path (str "/broadcasts/" broadcast-id)
                         :body updates}))

(defn send-broadcast!
  "Sends (or schedules, with {:scheduled-at ...}) a created broadcast."
  ([client broadcast-id] (send-broadcast! client broadcast-id nil))
  ([client broadcast-id {:keys [scheduled-at]}]
   (http/request! client
                  (cond-> {:method :post
                           :path (str "/broadcasts/" broadcast-id "/send")}
                    scheduled-at (assoc :body {:scheduled-at scheduled-at})))))

(defn delete-broadcast!
  "Deletes a broadcast (only drafts and non-sending broadcasts can be deleted)."
  [client broadcast-id]
  (http/request! client {:method :delete :path (str "/broadcasts/" broadcast-id)}))
