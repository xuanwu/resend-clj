# resend-clj

A Clojure client for the [Resend](https://resend.com) email API. Covers emails
(including batch, scheduling and attachments), domains, API keys, audiences,
contacts and broadcasts.

- Idiomatic kebab-case keyword maps in and out; snake_case conversion is automatic.
- No HTTP client dependency — uses the JDK's `java.net.http` (Java 11+).
  Only external dependency is `org.clojure/data.json`.
- Synchronous calls; API errors throw `ex-info` with full response data.
- Thread-safe: a client is an immutable map you create once and share.

## Installation

As a git dependency in `deps.edn` ([repository](https://github.com/xuanwu/resend-clj)):

```clojure
{:deps {io.github.xuanwu/resend-clj {:git/tag "v0.1.0" :git/sha "b363fbe"}}}
```

Or as a local dependency:

```clojure
{:deps {io.github.xuanwu/resend-clj {:local/root "../resend-clj"}}}
```

## Quick start

```clojure
(require '[resend.core :as resend])

;; Reads RESEND_API_KEY from the environment if no key is passed.
(def client (resend/client "re_123..."))

(resend/send! client
  {:from    "Acme <onboarding@acme.dev>"
   :to      ["delivered@resend.dev"]
   :subject "Hello world"
   :html    "<strong>It works!</strong>"})
;; => {:id "49a3999c-0ce1-4ea6-ab68-afcd6dc2e794"}
```

## Emails

```clojure
;; Everything the send endpoint supports:
(resend/send! client
  {:from        "Acme <onboarding@acme.dev>"
   :to          ["a@example.com" "b@example.com"]
   :cc          "manager@example.com"
   :reply-to    "support@acme.dev"
   :subject     "Monthly report"
   :html        "<h1>Report</h1>"
   :text        "Report"
   :headers     {"X-Entity-Ref-ID" "123"}      ; string keys pass through as-is
   :tags        [{:name "category" :value "report"}]
   :scheduled-at "in 1 hour"                   ; or an ISO-8601 timestamp
   :attachments [{:filename "report.pdf"
                  :content  (java.io.File. "report.pdf")}  ; File, bytes,
                 {:path "https://acme.dev/tos.pdf"}]}      ; InputStream, or
  ;; optional second argument:                             ; base64 string
  {:idempotency-key "monthly-report/2026-07"})

;; Batch send (up to 100; no attachments/tags/scheduling on this endpoint):
(resend/send-batch! client
  [{:from "a@acme.dev" :to "x@example.com" :subject "Hi" :text "…"}
   {:from "a@acme.dev" :to "y@example.com" :subject "Hi" :text "…"}])

(resend/get-email     client email-id)
(resend/update-email! client email-id {:scheduled-at "2026-08-05T11:52:01Z"})
(resend/cancel-email! client email-id)
```

## Domains

```clojure
(def domain (resend/create-domain! client {:name "mail.acme.dev"}))
(:records domain)                       ; DNS records to configure
(resend/verify-domain! client (:id domain))
(resend/list-domains   client)          ; => {:object "list" :data [...]}
(resend/get-domain     client domain-id)
(resend/update-domain! client domain-id {:open-tracking true :tls "enforced"})
(resend/delete-domain! client domain-id)
```

## API keys

```clojure
(resend/create-api-key! client {:name "ci" :permission "sending_access"})
;; => {:id "..." :token "re_..."}   ; token is only returned once
(resend/list-api-keys   client)
(resend/delete-api-key! client api-key-id)
```

## Audiences and contacts

```clojure
(def audience (resend/create-audience! client {:name "Newsletter"}))

(resend/create-contact! client (:id audience)
  {:email "user@example.com" :first-name "Ada" :unsubscribed false})

;; Contacts are addressable by id or by email:
(resend/get-contact    client (:id audience) "user@example.com")
(resend/update-contact! client (:id audience) "user@example.com" {:unsubscribed true})
(resend/delete-contact! client (:id audience) "user@example.com")
(resend/list-contacts   client (:id audience))
(resend/list-audiences  client)
(resend/delete-audience! client audience-id)
```

## Broadcasts

```clojure
(def broadcast
  (resend/create-broadcast! client
    {:audience-id (:id audience)
     :from        "Acme <news@acme.dev>"
     :subject     "July update"
     :html        "Hi {{{FIRST_NAME|there}}}, … <a href=\"{{{RESEND_UNSUBSCRIBE_URL}}}\">Unsubscribe</a>"}))

(resend/send-broadcast! client (:id broadcast))                            ; now
(resend/send-broadcast! client (:id broadcast) {:scheduled-at "in 3 days"}) ; later
(resend/list-broadcasts  client)
(resend/get-broadcast    client broadcast-id)
(resend/update-broadcast! client broadcast-id {:subject "July update (fixed)"})
(resend/delete-broadcast! client broadcast-id)
```

## Error handling

Non-2xx responses throw `ex-info`. The `ex-data` contains `:status`, the parsed
`:error` body, the raw `:body` string, and response `:headers`:

```clojure
(try
  (resend/send! client {:from "not-verified@nope.dev" :to "x@y.z" :subject "…" :text "…"})
  (catch clojure.lang.ExceptionInfo e
    (let [{:keys [status error]} (ex-data e)]
      (case status
        403 (log/error "domain not verified" (:message error))
        429 (retry-later)
        (throw e)))))
```

Successful map responses carry `{:status ... :headers ...}` as metadata, which
is useful for reading rate-limit headers: `(meta (resend/get-email client id))`.

## Client options

```clojure
(resend/client {:api-key            "re_123..."      ; default: RESEND_API_KEY env var
                :base-url           "https://api.resend.com"
                :connect-timeout-ms 10000
                :request-timeout-ms 30000
                :http-client        my-java-http-client}) ; optional override
```

## Escape hatch

Endpoints without a wrapper can be called directly:

```clojure
(require '[resend.client :as resend.http])
(resend.http/request! client {:method :get :path "/emails/some-new-endpoint"})
```

## Design notes

- **Client as a value.** `resend/client` returns an immutable map; create it
  once and pass it to every call. It is safe to share across threads. The
  `:base-url` option makes it easy to point the client at a mock server in
  tests.
- **Key conversion is automatic and symmetric.** Kebab-case keywords go out as
  snake_case (`:reply-to` → `reply_to`), and responses come back keywordized
  the other way (`created_at` → `:created-at`). Only keyword keys are
  converted — string keys pass through untouched, which is what lets custom
  email headers keep their exact names.
- **Attachments are coerced.** `:content` may be a byte array, `java.io.File`,
  `InputStream`, or an already base64-encoded string; encoding to base64
  happens automatically.
- **Errors are `ex-info`, success metadata is preserved.** Non-2xx responses
  throw with the full response in `ex-data`; successful map responses carry
  `:status` and `:headers` as metadata rather than polluting the body.
- **Zero HTTP dependency.** Built on the JDK's `java.net.http` (Java 11+), so
  the only external dependency is `org.clojure/data.json` and the library
  won't conflict with whatever HTTP stack the host project uses.
- **Naming.** Side-effecting functions end in `!`; read-only functions
  (`get-email`, `list-domains`) do not. All calls are synchronous — wrap in
  `future`/executors if you need async.
- **Tests run offline.** The suite stubs `resend.client/request!` with
  `with-redefs` and asserts on the request maps, so no network or API key is
  needed.

## Development

```
clojure -X:test
```
