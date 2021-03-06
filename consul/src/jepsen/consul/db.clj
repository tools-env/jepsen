(ns jepsen.consul.db
  (:require [clojure.tools.logging :refer [info warn]]
            [clojure.string :as str]
            [jepsen.consul.client :as client]
            [jepsen.core :as jepsen]
            [jepsen.db :as db]
            [jepsen.control :as c]
            [jepsen.control.net :as net]
            [jepsen.control.util :as cu]
            [slingshot.slingshot :refer [throw+ try+]]))

(def dir "/opt")
(def binary "consul")
;; TODO Condense these into dir
(def pidfile "/var/run/consul.pid")
(def logfile "/var/log/consul.log")
(def data-dir "/var/lib/consul")
(def retry-interval "5s")

(defn start-consul!
  [test node]
  (info node "starting consul")

  (cu/start-daemon!
   {:logfile logfile
    :pidfile pidfile
    :chdir   dir}
   binary
   :agent
   :-server
   :-log-level "debug"
   :-client    "0.0.0.0"
   :-bind      (net/ip (name node))
   :-data-dir  data-dir
   :-node      (name node)
   :-retry-interval retry-interval

   ;; Setup node in bootstrap mode if it resolves to primary
   (when (= node (jepsen/primary test)) :-bootstrap)

   ;; Join if not primary
   (when-not (= node (jepsen/primary test))
     [:-retry-join (net/ip (name (jepsen/primary test)))])

   ;; Shovel stdout to logfile
   :>> logfile
   (c/lit "2>&1")))

(defn db
  "Install consul specific version"
  []
  (reify db/DB
    (setup! [this test node]
      (let [version (:version test)]
        (info node "installing consul" version)
        (c/su
         (let [
               url (str "https://releases.hashicorp.com/consul/"
                        version "/consul_" version "_linux_amd64.zip")]
           (cu/install-archive! url (str dir "/" binary)))))

      (start-consul! test node)

      (info "Waiting for cluster to converge")
      (client/await-cluster-ready node (count (:nodes test)))

      (jepsen/synchronize test)
      (reset! (:initialized? test) true))

    (teardown! [_ test node]
      (c/su
       (cu/stop-daemon! binary pidfile)
       (info node "consul killed")

       (c/exec :rm :-rf pidfile logfile data-dir)
       (c/su
        (c/exec :rm :-rf binary)))
      (info node "consul nuked"))

    db/LogFiles
    (log-files [_ test node]
      [logfile])))
