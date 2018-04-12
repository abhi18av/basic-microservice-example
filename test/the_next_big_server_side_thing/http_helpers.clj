(ns the-next-big-server-side-thing.http-helpers
  (:require [io.aviso.exception :as aviso.exception]
            [io.aviso.ansi :as aviso.ansi]
            [io.pedestal.http :as bootstrap]
            [io.pedestal.test :refer [response-for]]
            [the-next-big-server-side-thing.components.debug-logger :as debug-logger]
            [the-next-big-server-side-thing.protocols.debug-logger :as protocols.debug-logger]
            [the-next-big-server-side-thing.http.serialization :as serialization]
            [the-next-big-server-side-thing.components.system-utils :as system-utils]))


(defn format-exception [throwable]
  (binding [aviso.exception/*traditional* true
            aviso.exception/*fonts*       (merge aviso.exception/*fonts*
                                                 {:message       aviso.ansi/white-font
                                                  :clojure-frame aviso.ansi/white-font
                                                  :function-name aviso.ansi/white-font})]
    (aviso.exception/format-exception throwable)))

(defn bootstrap-service []
  ;; Bootstrap a test version of a Pedestal service
  (-> :servlet system-utils/get-component! :instance ::bootstrap/service-fn))

(defn bootstrap-debug-logger-service []
  (system-utils/get-component :debug-logger))

(def default-headers
  {"Content-Type"    "application/edn ;charset=utf-8,*/*"
   "Accept-Encoding" "gzip, deflate"})

(defn output-stream->data [output]
  (if (string? output)
    (serialization/read-edn output)
    output))


(defn- print-last-interceptor-error [status]
  (println status)
  #_(println (some-> (bootstrap-debug-logger-service)
                                  protocols.debug-logger/get-last-error))
  (when (<= 400 status 600)
    (when-let [last-error (some-> (bootstrap-debug-logger-service)
                                  protocols.debug-logger/get-last-error
                                  format-exception)]
      (print
        (str "\033[0;36m === Last interceptor error [BEGIN] === \033[0m \n"
             last-error
             "\033[0;36m === Last interceptor error [END] === \033[0m \n\n")))))

(defn- assert-status! [method uri status expected-status deserialized-resp]
  (let [assertion (= status expected-status)]
    (println "_________________")
    (when-not assertion
      (print-last-interceptor-error status)
      (println "00000000000000")
      )
    (assert assertion
            (str method " request to '" uri "' expected status of "
                 expected-status ", but received "
                 status " with response: " deserialized-resp))))

(defmacro with-debug-error-logging
  "Prepare debug logger to store, in a thread local manner, the last error stored"
  [& forms]
  `(binding [debug-logger/*latest-error* nil] (do ~@forms)))

(defn req!
  ([method uri expected-status]
   (req! method uri nil expected-status))
  ([method uri body expected-status]
   (with-debug-error-logging
     ;; Raw pedestal response, without content negotiation or serialization support
     (let [service         (bootstrap-service)
           {:keys [headers
                   status
                   body]}  (response-for service method uri
                                         :body (when body
                                                 (serialization/write-edn body))
                                         :headers default-headers)]
       (let [deserialized-resp {:body   (try (output-stream->data body)
                                             (catch Exception _ body))
                                :headers headers}]
         (assert-status! method uri status expected-status deserialized-resp)

         deserialized-resp)))))

(def GET  (partial req! :get))
(def POST (partial req! :post))
