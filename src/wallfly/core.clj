#!/usr/bin/env bb

(ns wallfly.core
  (:require [babashka.process :as process]
            [clojure.core.async :refer [chan >!! <!! go-loop timeout alt!]]
            [clojure.string :as str]
            [cheshire.core :as json]
            [clojure.tools.cli :refer [parse-opts]]
            [clojure.pprint :refer [pprint]]))

(defn- exit! [status msg]
  (println msg)
  (System/exit status))

(defn- parse-broker-uri [uri]
  "Parse MQTT broker URI like tcp://host:port or ssl://host:port"
  (let [uri-pattern #"^(tcp|ssl)://([^:]+)(?::(\d+))?$"
        [_ protocol host port] (re-matches uri-pattern uri)]
    (when-not host
      (exit! 1 (str "Invalid broker URI format: " uri)))
    {:host host
     :port (or port (if (= protocol "ssl") "8883" "1883"))
     :use-tls (= protocol "ssl")}))

(defn- create-mqtt-config [broker-uri mqtt-username passwd]
  "Create MQTT configuration for mosquitto_pub"
  (merge (parse-broker-uri broker-uri)
         {:username mqtt-username
          :password passwd}))

(defn- shell-exec [& args]
  "Execute shell command and return {:success true :out output} or {:success false :error error}"
  (try
    (let [result (process/shell {:out :string :err :string} (str/join " " args))
          out (str/trim (:out result))]
      {:success true :out out})
    (catch Exception e
      {:success false :error (ex-message e)})))

(defn- get-current-session-id []
  "Get the current user's session ID from loginctl"
  (let [username (System/getenv "USER")
        result (shell-exec "loginctl" "list-sessions" "--no-legend")]
    (when (:success result)
      (->> (str/split-lines (:out result))
           (filter #(str/includes? % username))
           first
           (re-find #"^\s*(\S+)")
           second))))

(defn- get-idle-time-from-loginctl []
  "Get idle time in seconds using systemd-logind (works with X11 and Wayland)"
  (if-let [session-id (get-current-session-id)]
    (let [idle-hint-result (shell-exec "loginctl" "show-session" session-id "-p" "IdleHint" "--value")
          idle-since-result (shell-exec "loginctl" "show-session" session-id "-p" "IdleSinceHintMonotonic" "--value")]
      (if (and (:success idle-hint-result) (:success idle-since-result))
        (if (= "yes" (:out idle-hint-result))
          ;; Session is idle - calculate how long
          (let [idle-since-usec (Long/parseLong (:out idle-since-result))
                uptime-result (shell-exec "awk" "{print $1*1000000}" "/proc/uptime")
                now-usec (when (:success uptime-result) (long (Double/parseDouble (:out uptime-result))))
                idle-usec (when now-usec (- now-usec idle-since-usec))
                idle-sec (when idle-usec (max 0 (quot idle-usec 1000000)))]
            (if idle-sec
              {:success true :out idle-sec}
              {:success false :error "Failed to calculate idle time"}))
          ;; Session is not idle
          {:success true :out 0})
        {:success false :error (str "Failed to get session idle info: " (:error idle-since-result))}))
    {:success false :error "Could not determine current session"}))

(defn- get-idle-time-from-xprintidle []
  "Get idle time in seconds using xprintidle (X11 only, legacy fallback)"
  (let [result (shell-exec "xprintidle")]
    (if (:success result)
      {:success true :out (quot (Integer/parseInt (:out result)) 1000)}
      result)))

(defn- get-idle-time []
  "Get idle time in seconds.
  
  Tries systemd-logind first (works with both X11 and Wayland),
  falls back to xprintidle (X11 only) if loginctl fails.
  
  This ensures compatibility with modern Linux systems that use Wayland,
  while maintaining backwards compatibility with X11-only systems."
  (let [result (get-idle-time-from-loginctl)]
    (if (:success result)
      result
      (get-idle-time-from-xprintidle))))

(defn- get-hostname []
  (let [result (shell-exec "hostname")]
    (if (:success result)
      (:out result)
      (exit! 1 (str "Failed to get hostname: " (:error result))))))

(defn- get-fqdn []
  (let [result (shell-exec "hostname" "-f")]
    (if (:success result)
      (:out result)
      (exit! 1 (str "Failed to get FQDN: " (:error result))))))

(defn- get-username [] (System/getenv "USER"))

(defn- send-message [mqtt-config topic msg & {:keys [retained] :or {retained false}}]
  "Send MQTT message using mosquitto_pub"
  (let [{:keys [host port username password use-tls]} mqtt-config
        base-args ["mosquitto_pub"
                   "-h" host
                   "-p" (str port)
                   "-u" username
                   "-P" password
                   "-t" topic
                   "-m" msg
                   "-q" "1"]
        args (if retained
               (conj base-args "-r")
               base-args)]
    (try
      (let [result @(process/process args {:out :string :err :string})]
        (when (not= 0 (:exit result))
          (exit! 1 (str "mosquitto_pub failed: " (:err result)))))
      (catch Exception e
        (exit! 1 (str "Failed to send MQTT message: " (ex-message e)))))))

(defn- create-reporter [mqtt-config time-to-idle location user host host-device]
  (let [base-topic     (format "homeassistant/binary_sensor/wallfly_%s_%s"
                               user host)
        presence-topic (format "%s/state" base-topic)]
    (let [cfg-topic (format "%s/config" base-topic)
          payload   {:name            (format "%s present on %s" user host)
                     :device_class    :occupancy
                     :entity_category :diagnostic
                     :unique_id       (format "wallfly_%s_%s" user host)
                     :state_topic     presence-topic
                     :icon            "mdi:account-check"
                     :off_delay       time-to-idle
                     :device          {:identifiers    [host-device]
                                       :name           (format "%s WallFly" host)
                                       :model          "WallFly"
                                       :manufacturer   "Fudo"
                                       :suggested_area location}}]
      (println (format "sending to %s: %s" cfg-topic (with-out-str (pprint payload))))
      (send-message mqtt-config cfg-topic (json/generate-string payload) :retained true)
      ;; Send one message that will persist if the host dies
      (send-message mqtt-config presence-topic "OFF" :retained true))
    (fn [idle-time]
      ;; (emit-idle idle-time)
      (when (< idle-time time-to-idle)
        (send-message mqtt-config presence-topic "ON")))))

(defn- execute! [delay-seconds report]
  (let [stop-chan  (chan)
        delay-time (* 1000 delay-seconds)]
    (go-loop [idle-measure (get-idle-time)]
      (if (nil? idle-measure)
        nil
        (do (if (:success idle-measure)
              (report (:out idle-measure))
              (println (str "error reading idle time: " (:error idle-measure))))
            (recur (alt! (timeout delay-time) (get-idle-time)
                         stop-chan            nil)))))))

(def cli-opts
  [["-l" "--location LOCATION" "Location (in house) of the host running this job."]
   ["-b" "--mqtt-broker-uri URI" "URI of the MQTT broker."]
   ["-u" "--mqtt-username USERNAME" "Username to use when connecting to MQTT."]
   ["-p" "--mqtt-password-file PASSWORD-FILE" "Path to a file containing the password for this client."]
   ["-t" "--time-to-idle SECONDS" "Number of seconds before considering this host idle."]
   ["-d" "--delay-time SECONDS" "Time to wait before polling for idle time."]])

(defn- ->screaming-snake-case [s]
  "Convert kebab-case string to SCREAMING_SNAKE_CASE"
  (-> s (str/replace "-" "_") (str/upper-case)))

(defn- get-key [opts k]
  (if-let [opt (get opts k)]
    [k opt]
    [k (System/getenv (format "WALLFLY_%s"
                              (-> k name ->screaming-snake-case)))]))

(defn- get-args [keys args]
  (let [{:keys [options errors summary]} (parse-opts args cli-opts)]
    (when (:help options) (exit! 0 summary))
    (when (seq errors)    (exit! 1 (str/join \newline errors)))
    (let [resolved (into {} (map (partial get-key options) keys))
          missing  (filter (fn [k] (not (get resolved k false))) keys)]
      (if (seq missing)
        (exit! 2 (str "missing arguments: " (str/join " " (map name missing))))
        resolved))))

(let [chars (map char (apply concat (map (fn [[s e]] (range (int s) (int e))) [[\0 \9] [\a \z] [\A \Z]])))]
  (defn- rand-str [n]
    (apply str (take n (repeatedly #(nth chars (rand (count chars))))))))

(defn -main [& args]
  (let [required-keys [:location
                       :mqtt-broker-uri
                       :mqtt-password-file
                       :time-to-idle
                       :delay-time
                       :mqtt-username]
        {:keys [location
                mqtt-broker-uri
                mqtt-password-file
                time-to-idle
                delay-time
                mqtt-username]} (get-args required-keys args)
        catch-shutdown (chan)
        password       (-> mqtt-password-file (slurp) (str/trim))
        username       (get-username)
        hostname       (get-hostname)
        host-device    (format "wallfly-%s" (get-fqdn))
        mqtt-config    (create-mqtt-config mqtt-broker-uri mqtt-username password)
        reporter       (create-reporter mqtt-config (Integer/parseInt time-to-idle) location username hostname host-device)
        stop-chan      (execute! (Integer/parseInt delay-time) reporter)]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. (fn [] (>!! catch-shutdown true))))
    (<!! catch-shutdown)
    (>!! stop-chan true)
    (System/exit 0)))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
