(ns wallfly.core
  (:require [clojure.java.shell :as shell]
            [clojure.core.async :refer [chan >!! <!! go-loop timeout alt!]]
            [clojure.string :as str :refer [trim-newline]]
            [clojure.data.json :as json]
            [clojure.tools.cli :refer [parse-opts]]
            [clojure.pprint :refer [pprint]]
            [fudo-clojure.result :refer [success failure unwrap map-success success? error-message]]
            [camel-snake-kebab.core :refer [->SCREAMING_SNAKE_CASE]])
  (:import [org.eclipse.paho.client.mqttv3
            MqttClient
            MqttConnectOptions
            MqttMessage]
           org.eclipse.paho.client.mqttv3.persist.MemoryPersistence)
  (:gen-class))

(defn- create-mqtt-client [broker-uri client-id mqtt-username passwd]
  (let [opts (doto (MqttConnectOptions.)
               (.setCleanSession true)
               (.setAutomaticReconnect true)
               (.setPassword (char-array passwd))
               (.setUserName mqtt-username))]
    (doto (MqttClient. broker-uri client-id (MemoryPersistence.))
      (.connect opts))))

(defn- shell-exec [& args]
  (let [{:keys [exit out err]} (apply shell/sh args)]
    (if (= exit 0)
      (success (trim-newline out))
      (failure err { :error err :status-code exit }))))

(defn- get-idle-time []
  (map-success (shell-exec "xprintidle")
               (fn [idle-str] (quot (Integer/parseInt idle-str) 1000))))

(defn- get-hostname [] (unwrap (shell-exec "hostname")))

(defn- get-fqdn [] (unwrap (shell-exec "hostname" "-f")))

(defn- get-username [] (System/getenv "USER"))

(defn- create-message [msg retained]
  (doto (MqttMessage. (.getBytes msg))
    (.setQos 1)
    (.setRetained retained)))

(defn- send-message [client topic msg & {:keys [retained] :or {retained false}}]
  (.publish client topic (create-message msg retained)))

(defn- create-reporter [client time-to-idle location user host host-device]
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
      (send-message client cfg-topic (json/write-str payload) :retained true)
      ;; Send one message that will persist if the host dies
      (send-message client presence-topic "OFF" :retained true))
    (fn [idle-time]
      ;; (emit-idle idle-time)
      (when (< idle-time time-to-idle)
        (send-message client presence-topic "ON")))))

(defn- execute! [delay-seconds report]
  (let [stop-chan  (chan)
        delay-time (* 1000 delay-seconds)]
    (go-loop [idle-measure (get-idle-time)]
      (if (nil? idle-measure)
        nil
        (do (if (success? idle-measure)
              (report (unwrap idle-measure))
              (println (str "error reading idle time: " (error-message idle-measure))))
            (recur (alt! (timeout delay-time) (get-idle-time)
                         stop-chan            nil)))))))

(def cli-opts
  [["-l" "--location LOCATION" "Location (in house) of the host running this job."]
   ["-b" "--mqtt-broker-uri URI" "URI of the MQTT broker."]
   ["-u" "--mqtt-username USERNAME" "Username to use when connecting to MQTT."]
   ["-p" "--password-file PASSWORD-FILE" "Path to a file containing the password for this client."]
   ["-t" "--time-to-idle SECONDS" "Number of seconds before considering this host idle."]
   ["-d" "--delay-time SECONDS" "Time to wait before polling for idle time."]])

(defn- exit! [status msg]
  (println msg)
  (System/exit status))

(defn- get-key [opts k]
  (if-let [opt (get opts k)]
    [k opt]
    [k (System/getenv (format "WALLFLY_%s"
                              (-> k name ->SCREAMING_SNAKE_CASE)))]))

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
        password       (-> mqtt-password-file (slurp) (str/trim-newline))
        username       (get-username)
        hostname       (get-hostname)
        host-device    (format "wallfly-%s" (get-fqdn))
        client-id      (format "wallfly-%s" (rand-str 10))
        client         (create-mqtt-client mqtt-broker-uri client-id mqtt-username password)
        reporter       (create-reporter client (Integer/parseInt time-to-idle) location username hostname host-device)
        stop-chan      (execute! (Integer/parseInt delay-time) reporter)]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. (fn [] (>!! catch-shutdown true))))
    (<!! catch-shutdown)
    (>!! stop-chan true)
    (System/exit 0)))
