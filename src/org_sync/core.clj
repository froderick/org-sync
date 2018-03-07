(ns org-sync.core
  "A Clojure program designed to monitor the history of a Slack conversation and
  synchronize that information to local files. It synchronizes one-way, and it
  includes both text and files posted to the conversation. The text is output
  as [org](https://orgmode.org/) format, and includes links to locally-cached
  versions of the referenced conversation files.

  Calls the `slack` ns to fetch channel data, calls the `org` ns to generate org
  files from it."
  (:require [clojure.pprint :refer [pprint]]
            [clojure.spec.alpha :as s]
            [clojure.java.io :as io]
            [org-sync.org :as org]
            [org-sync.slack :as slack]
            [clojure.tools.cli :refer [parse-opts]])
  (:refer-clojure :exclude [sync])
  (:import [java.time Instant ZoneId]
           [java.util.concurrent TimeUnit LinkedBlockingQueue])
  (:gen-class))

(defn build-org-model-message
  "Returns a vec of org elements that describe the message."
  [{text ::slack/message-text file ::slack/message-file}]
  (if-let [{title ::slack/message-file-title
            url ::slack/message-file-local-url
            mimetype ::slack/message-file-mimetype} file]
    [[::org/text text]
     [::org/file
      [::org/name title]
      [::org/url url]
      [::org/mimetype mimetype]]]
    [[::org/text text]]))

(s/fdef build-org-model-message
        :args (s/cat :message ::slack/message)
        :ret (s/* (s/or :text ::org/text
                        :file ::org/file)))

(defn chat-session-key
  "The sequential key by which chats are grouped in the org file"
  [ts tz]
  (-> ts 
      slack/ts-to-instant
      (.atZone (ZoneId/of tz))
      .toLocalDate str))

(s/fdef chat-session-key
        :args (s/cat :ts string? :tz string?)
        :ret string?)

(defn build-org-model
  [messages]
  (let [default-tz (-> (ZoneId/systemDefault) .getId)
        part-fn #(chat-session-key (::slack/message-ts %) default-tz)
        doc [::org/document
             [::org/section [[::org/text (str "Hi, this is an org document exported from a slack channel. Do not modify manually, as "
                             "your changes may be overwritten.")]]]]
        headlines (->> messages
                       (sort-by ::slack/message-ts)
                       (partition-by part-fn)
                       (map (fn [part]
                              [::org/headline
                               [::org/stars 1]
                               [::org/title (str "Session " (part-fn (first part)))]
                               [::org/tags ["slackcapture"]]
                               [::org/section (->> part
                                               (map build-org-model-message)
                                               (apply concat)
                                               vec)]])))]
    (->> (concat doc headlines)
         vec)))

(s/fdef build-org-model
        :args (s/cat :messages (s/coll-of ::slack/message))
        :ret ::org/document)

(defn sync
  [conn channel-id cache-dir output-file]
  (->> (slack/get-history conn channel-id cache-dir)
       build-org-model
       org/render-org-model
       (spit output-file))
  nil)

(defn dir-state-file
  [dir]
  (io/file dir "sync-state"))

(defn read-dir-sync-state
  [dir]
  (let [f (dir-state-file dir)]
    (if (.exists f)
      (->> f slurp (clojure.edn/read-string))
      {})))

(defn write-dir-sync-state
  [dir state]
  (.mkdirs dir)
  (->> (prn-str state)
       (spit (dir-state-file dir))))

(defn sync-dir
  [conn name channel-id dir]
  (let [dir (io/file dir)
        {:keys [last-fetched-ts] :as sync-state} (read-dir-sync-state dir)
        most-recent-ts (slack/get-latest-im-event-timestamp conn channel-id)
        new-changes? (not= last-fetched-ts most-recent-ts)]

    (when-not (.exists dir)
      (.mkdirs dir))

    (if new-changes?
      (let [cache-dir (io/file dir "cache")
            output-file (io/file dir (str name ".org"))]
        (println "changes detected since " last-fetched-ts ": latest is" most-recent-ts)
        (sync conn channel-id cache-dir output-file)
        (write-dir-sync-state dir (assoc sync-state :last-fetched-ts most-recent-ts)))
      (println "no changes since" last-fetched-ts)))
  nil)

(defn make-dir-syncer
  "Returns a function that when called, synchronizes the user data to the directory"
  [conn name channel-id dir]
  (fn []
    (sync-dir conn name channel-id (io/file dir))))

(defn do-every
  "returns a fn to cancel the doing"
  [f interval-millis]
  (let [queue (LinkedBlockingQueue. 1)
        cancel #(.offer queue :cancel)]
    (future
      (loop []
        (println "doing...")
        (try 
          (f)
          (catch Exception e
            (println "failed while doing")
            (.printStackTrace e)))
        (println "waiting")
        (let [stop? (try
                      (.poll queue interval-millis TimeUnit/MILLISECONDS)
                      (catch Exception e
                        (.printStackTrace e)))]
          (println "done waiting")
          (if stop?
            (println "stopping...")
            (recur)))))
    cancel))

(def cli-options
  ;; An option with a required argument
  [["-a" "--api-url URL"          "Slack API URL" :default "https://slack.com/api/"]
   ["-t" "--api-token TOKEN"      "Slack API Token"]
   ["-u" "--username USERNAME"    "Username whose IMs to sync from"]
   ["-c" "--channel CHANNEL_NAME" "The Channel to sync from"]
   ["-g" "--group GROUP_NAME"     "The Group to sync from"]
   ["-o" "--output-dir USERNAME"  "Directory to sync into"]
   ["-m" "--monitor"              "Continuously poll for changes to sync"]
   ["-i" "--interval INTERVAL"    "Polling interval in milliseconds" :parse-fn #(Long/parseLong %) :default 5000]
   ["-h" "--help"]])

(defn usage
  [summary]
  (->> ["org-sync - transcribes the contents of a slack channel into local files, encoded"
        "           in org-mode format."
        ""
        summary]
       (clojure.string/join \newline)))

(defn determine-source
  [conn username channel group]
  (cond
    username
    [username (->> username
                  (slack/get-user-id-by-name conn)
                  (slack/get-im-channel-id-by-user-id conn))]
    channel
    [channel (slack/get-channel-id-by-name conn channel)]

    group
    [group (slack/get-group-id-by-name conn group)]))

(defn -main
  [& args]
  (let [{:keys [options summary]} (parse-opts args cli-options)
        {:keys [api-url api-token username channel group output-dir monitor interval help]} options
        conn {:api-url api-url :token api-token}]
    (cond

      help
      (println (usage summary))

      (or username channel group)
      (let [[name channel-id] (determine-source conn username channel group)
            syncer (make-dir-syncer conn name channel-id output-dir)]
        (if monitor
          (do-every syncer interval)
          (syncer)))

      :else
      (do
        (println "error: no username, channel or group supplied")
        (println (usage summary))))))


