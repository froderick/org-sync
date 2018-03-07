(ns org-sync.slack
  "fetches the history of a slack channel, including messages and files materialized locally"
  (:require [clj-slack.im :as im]
            [clj-slack.users :as users]
            [clj-slack.channels :as chan]
            [clj-slack.files :as files]
            [clj-slack.core :refer [slack-request stringify-keys]]
            [clojure.pprint :refer [pprint]]
            [clojure.spec.alpha :as s]
            [clj-http.client :as client]
            [clojure.java.io :as io]
            [clj-slack.groups :as groups])
  (:import [java.time Instant ZoneId]))

(s/def ::message-type string?)
(s/def ::message-user string?)
(s/def ::message-text string?)
(s/def ::message-ts string?)

(s/def ::message-file-id string?)
(s/def ::message-file-title string?)
(s/def ::message-file-local-url string?)
(s/def ::message-file-mimetype string?)
(s/def ::message-file (s/keys :req [::message-file-id
                                    ::message-file-title
                                    ::message-file-local-url
                                    ::message-file-mimetype]))
(s/def ::message (s/keys :req [::message-type ::message-user ::message-text ::message-ts]
                         :opt [::message-file]))
(s/def ::api-url string?)
(s/def ::token string?)
(s/def ::connection (s/keys :req-un [::api-url ::token]))

(defn get-user-id-by-name
  [conn username]
  (let [{:keys [members]} (users/list conn)]
    (->> members
         (filter #(= (:name %) username))
         first
         :id)))

(s/fdef get-user-id-by-name
        :args (s/cat :conn ::connection
                     :username string?)
        :ret string?)

(defn get-im-channel-id-by-user-id
  [conn user-id]
  (->> (im/list conn)
       :ims
       (filter #(= (:user %) user-id))
       first
       :id))

(s/fdef get-im-channel-id-by-user-id
        :args (s/cat :conn ::connection
                     :username string?)
        :ret string?)

(defn get-channel-id-by-name
  [conn channel-name]
  (let [{:keys [channels]} (chan/list conn)]
    (->> channels
         (filter #(= (:name %) channel-name))
         first
         :id)))

(defn get-group-id-by-name
  [conn group-name]
  (let [{:keys [groups]} (groups/list conn)]
    (->> groups
         (filter #(= (:name %) group-name))
         first
         :id)))

(defn get-file-stream
  "Fetch a file from a slack channel and return an input stream."
  [conn url]
  (let [token (:token conn)
        {:keys [status body] :as response} (client/get url {:oauth-token token :as :stream})]
    (when-not (= status 200)
      (throw (Exception. (str "failed to fetch file '" url "': " status))))
    body))

(s/fdef get-file-stream
        :args (s/cat :conn ::connection
                     :url string?)
        :ret #(instance? java.io.InputStream %))

(defn cached-file-location
  [name url cache-dir]
  (let [get-file-extension #(->> % (re-matches #"^.*(\.[a-zA-Z]+)$") second)
        local-file-name (if (get-file-extension name)
                          name
                          (str name (get-file-extension url)))]
    (-> (io/file cache-dir local-file-name))))

(s/fdef cached-file-location
        :args (s/cat :name string?
                     :url string?
                     :cache-dir #(instance? java.io.File %))
        :ret #(instance? java.io.File %))

(defn get-cached-file
  [conn url cache-dir name]
  (let [cache-file (cached-file-location name url cache-dir)
        cache-file-uri (-> cache-file .toURI .toURL str)]
    (when-not (.exists cache-file)
      (println "get-cached-file: fetching" url "->" cache-file-uri)
      (.mkdirs cache-dir)
      (with-open [in (get-file-stream conn url)
                  out (io/output-stream cache-file)]
        (io/copy in out)))
    cache-file-uri))

(s/fdef get-cached-file
        :args (s/cat :conn ::connection
                     :url string?
                     :cache-dir #(instance? java.io.File %)
                     :name string?)
        :ret string?)

(defn create-local-message
  [conn cache-dir {:keys [type user text ts file] :as message}]
  (let [base {::message-type type
              ::message-user user
              ::message-text text
              ::message-ts ts}]
    (if-let [{url :url_private name :title mimetype :mimetype id :id} file]
      (assoc base ::message-file {::message-file-id id
                                  ::message-file-local-url (get-cached-file conn url cache-dir id)
                                  ::message-file-title name
                                  ::message-file-mimetype mimetype})
      base)))

(s/fdef create-local-message
        :args (s/cat :conn ::connection
                     :cache-dir #(instance? java.io.File %)
                     :message map?)
        :ret ::message)

(defn get-history-page
  [conn channel-id opts]
  (let [{:keys [ok error] :as page} (->> opts
                                         stringify-keys
                                         (merge {"channel" channel-id})
                                         (slack-request conn "conversations.history"))]
    (when-not ok
      (throw (Exception. (str "conversations.history failed: " error "\n" (prn-str page)))))
    page))

(defn get-all-history
  [conn channel-id]
  (loop [collected []
         opts {}]
    (let [{:keys [messages has_more]} (get-history-page conn channel-id opts)
          collected (into collected messages)
          oldest-ts (->> messages last :ts)]
      (if has_more
        (recur collected {:latest oldest-ts})
        collected))))

(defn get-history
  [conn channel-id cache-dir]
  (->> (get-all-history conn channel-id)
       (map #(create-local-message conn cache-dir %))
       doall))

(s/fdef get-user-im-history
        :args (s/cat :conn ::connection
                     :username string?
                     :cache-dir any?)
        :ret (s/coll-of ::message))

(defn get-latest-im-event-timestamp 
  [conn channel-id]
  (->> (get-history-page conn channel-id {:count "1"})
       :messages
       first
       :ts))

(defn ts-to-instant
  [ts]
  (-> ts 
      Double/parseDouble
      long
      Instant/ofEpochSecond))
