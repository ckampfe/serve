(ns serve.core
  (:require [cljs.nodejs :as nodejs]
            [cljs.core.async :refer [chan >! <! alts! go]]
            [cljs.tools.cli :refer [parse-opts]]
            [cljs-node-io.fs :as fs]
            [hiccups.runtime :as hiccupsrt]
            [http :as node-http])
  (:require-macros [hiccups.core :as hiccups :refer [html]]))

(nodejs/enable-util-print!)

(def cli-options
  [["-p" "--port NUMBER" "port"
    :default 8000
    :parse-fn #(js/parseInt % 10)]
   ["-h" "--help"]])

(defn js->clj-impl
  [x]
  (into {} (for [k (.keys js/Object x)] [k (aget x k)])))

;; this is so we can `(js->clj req),
;; because req is an http/IncomingMessage`
(extend-protocol IEncodeClojure
  http/IncomingMessage
  (-js->clj [x options] (js->clj-impl x)))

(defn ls [directory-path]
  (go
    (let [[err files] (<! (fs/areaddir directory-path))]
      (if-not err
        files
        (throw (js/Error. err))))))

(defn is-dir? [path]
  (.isDirectory (fs/lstat path)))

(defn build-template [directory-path]
  (go
    (html
     [:head]
     [:body
      [:h1 directory-path]
      [:ul
       (for [path (<! (ls directory-path))]
         [:li
          [:a {:href (if (is-dir? (str directory-path "/" path))
                       (str path "/")
                       path)} path]])]])))

(defn build-full-path [cwd url]
  (str cwd (when url url)))

(defn server-handler [req res]
  (go
    (let [cwd (.cwd nodejs/process)
          url (get (js->clj ^js/Object req) "url")
          full-path (build-full-path cwd url)]

      (set! (.-statusCode res) 200)

      (if (is-dir? full-path)
        (do
          (.setHeader res "Content-Type", "text/html")
          (.end res (<! (build-template full-path))))
        (do
          ;; for now assume plain text.
          ;; in the future, this could be a lookup.
          (.setHeader res "Content-Type", "text/plain")
          (.end res (fs/readFile full-path "")))))))

(defn start-server! [port]
  (let [hostname "127.0.0.1"
        server (.createServer node-http server-handler)]
    (.listen server
             port
             hostname
             (fn []
               (println "running server on port" port)))))

(defn -main [& args]
  (let [{:keys [options arguments errors summary]} (parse-opts args cli-options)]

    (cond
      (:help options) (do
                        (println summary)
                        (.exit nodejs/process 0))
      errors (do
               (println "errors")
               (println errors)
               (.exit nodejs/process 1)))

    (start-server! (:port options))))

(set! *main-cli-fn* -main)
