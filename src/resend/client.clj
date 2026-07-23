(ns resend.client
  "Low-level HTTP client for the Resend API (https://resend.com/docs/api-reference).

  Most applications should use the higher-level functions in `resend.core`.
  This namespace is public so that new or uncommon endpoints can be called
  directly via `request!` without waiting for a wrapper function.

  A client is a plain map created with `client`. All requests are synchronous
  and return the parsed JSON body with snake_case keys converted to
  kebab-case keywords. Non-2xx responses throw an `ex-info` whose data map
  contains `:type :resend.client/error`, `:status`, `:error` (the parsed
  error body, if any) and `:headers`."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.walk :as walk])
  (:import (java.net URI URLEncoder)
           (java.net.http HttpClient HttpClient$Redirect HttpRequest
                          HttpRequest$Builder HttpRequest$BodyPublishers
                          HttpResponse HttpResponse$BodyHandlers)
           (java.nio.charset StandardCharsets)
           (java.time Duration)))

(set! *warn-on-reflection* true)

(def default-base-url "https://api.resend.com")

(def ^:private version "0.1.0")
(def ^:private user-agent (str "resend-clj/" version))

;; ---------------------------------------------------------------------------
;; Key conversion

(defn- kw->wire-key
  [k]
  (if (keyword? k)
    (str/replace (name k) "-" "_")
    k))

(defn ->wire
  "Recursively converts keyword map keys from :kebab-case keywords to
  \"snake_case\" strings, e.g. :reply-to -> \"reply_to\". String keys (such
  as custom email headers like \"X-Entity-Ref-ID\") are left untouched."
  [x]
  (walk/postwalk
   (fn [form]
     (if (map? form)
       (into {} (map (fn [[k v]] [(kw->wire-key k) v])) form)
       form))
   x))

(defn wire-key->kw
  "Converts a snake_case JSON key to a kebab-case keyword."
  [k]
  (keyword (str/replace k "_" "-")))

(defn parse-json
  "Parses a JSON string, keywordizing keys as kebab-case. Returns nil for a
  blank string."
  [s]
  (when-not (str/blank? (str s))
    (json/read-str s :key-fn wire-key->kw)))

;; ---------------------------------------------------------------------------
;; Client construction

(defn client
  "Creates a Resend client. Accepts either an API key string or an options map:

    :api-key             Resend API key (\"re_...\"). Falls back to the
                         RESEND_API_KEY environment variable.
    :base-url            API root, default \"https://api.resend.com\".
    :connect-timeout-ms  Connection timeout, default 10000.
    :request-timeout-ms  Per-request timeout, default 30000.
    :http-client         A prebuilt java.net.http.HttpClient to use instead
                         of constructing one.

  The returned client is an immutable map and is safe to share across
  threads."
  ([] (client {}))
  ([api-key-or-opts]
   (let [opts (if (string? api-key-or-opts)
                {:api-key api-key-or-opts}
                api-key-or-opts)
         {:keys [api-key base-url connect-timeout-ms request-timeout-ms http-client]
          :or {base-url default-base-url
               connect-timeout-ms 10000
               request-timeout-ms 30000}} opts
         api-key (or api-key (System/getenv "RESEND_API_KEY"))]
     (when (str/blank? api-key)
       (throw (ex-info "Missing Resend API key: pass :api-key or set RESEND_API_KEY"
                       {:type ::missing-api-key})))
     {:api-key api-key
      :base-url (str/replace base-url #"/+$" "")
      :request-timeout-ms request-timeout-ms
      :http-client (or http-client
                       (-> (HttpClient/newBuilder)
                           (.connectTimeout (Duration/ofMillis connect-timeout-ms))
                           (.followRedirects HttpClient$Redirect/NORMAL)
                           (.build)))})))

;; ---------------------------------------------------------------------------
;; Requests

(defn- url-encode ^String [v]
  (URLEncoder/encode (str v) StandardCharsets/UTF_8))

(defn- query-string [params]
  (->> params
       (remove (comp nil? val))
       (map (fn [[k v]] (str (url-encode (kw->wire-key k)) "=" (url-encode v))))
       (str/join "&")))

(defn- response-headers [^HttpResponse response]
  (into {}
        (map (fn [[k vs]] [(str/lower-case (str k)) (str/join ", " vs)]))
        (.map (.headers response))))

(defn build-request
  "Builds the request data for a Resend API call without executing it.
  Returns {:url ... :method ... :headers ... :body ...} where :body is the
  encoded JSON string (or nil). Exposed for testing and debugging."
  [{:keys [api-key base-url]} {:keys [method path body query-params headers]}]
  (let [qs (some-> query-params not-empty query-string)]
    {:url (str base-url path (when qs (str "?" qs)))
     :method (str/upper-case (name method))
     :headers (cond-> {"Authorization" (str "Bearer " api-key)
                       "User-Agent" user-agent}
                body (assoc "Content-Type" "application/json")
                headers (into headers))
     :body (some-> body ->wire (json/write-str :escape-slash false))}))

(defn request!
  "Executes a request against the Resend API and returns the parsed body.

  The request map supports:

    :method        :get, :post, :patch, :put or :delete
    :path          e.g. \"/emails\"
    :body          Clojure map, serialized as JSON with kebab-case keywords
                   converted to snake_case
    :query-params  map of query parameters (nil values are dropped)
    :headers       map of extra request headers

  Successful map responses carry `{:status ... :headers ...}` as metadata.
  Non-2xx responses throw ex-info (see namespace docstring)."
  [{:keys [^HttpClient http-client request-timeout-ms] :as client} req]
  (let [{:keys [url method headers body]} (build-request client req)
        builder (reduce-kv (fn [^HttpRequest$Builder b k v]
                             (.header b (name k) (str v)))
                           (-> (HttpRequest/newBuilder (URI/create url))
                               (.timeout (Duration/ofMillis (or request-timeout-ms 30000))))
                           headers)
        request (.build (.method ^HttpRequest$Builder builder
                                 ^String method
                                 (if body
                                   (HttpRequest$BodyPublishers/ofString body)
                                   (HttpRequest$BodyPublishers/noBody))))
        response (.send http-client request (HttpResponse$BodyHandlers/ofString))
        status (.statusCode response)
        raw-body (.body response)
        parsed (try (parse-json raw-body)
                    (catch Exception _ nil))]
    (if (<= 200 status 299)
      (if (map? parsed)
        (with-meta parsed {:status status :headers (response-headers response)})
        parsed)
      (throw (ex-info (str "Resend API error (HTTP " status ")"
                           (when-let [msg (and (map? parsed) (:message parsed))]
                             (str ": " msg)))
                      {:type ::error
                       :status status
                       :error parsed
                       :body raw-body
                       :headers (response-headers response)})))))
