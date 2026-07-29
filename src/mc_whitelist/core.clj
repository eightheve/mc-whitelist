(ns mc-whitelist.core
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [compojure.core :refer [defroutes GET POST]]
            [compojure.route :as route]
            [hiccup2.core :as h]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.params :refer [wrap-params]])
  (:import [java.io File]
           [java.net URI]
           [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]
           [java.time Instant])
  (:gen-class))

;;; Configuration (from environment)

(def config
  {:fifo           (or (System/getenv "MC_FIFO") "/run/minecraft-server.stdin")
   :whitelist-json (or (System/getenv "MC_WHITELIST_JSON") "/var/lib/minecraft/whitelist.json")
   :server-name    (or (System/getenv "MC_SERVER_NAME") "mc.doppel.moe")
   :host           (or (System/getenv "HOST") "0.0.0.0")
   :port           (parse-long (or (System/getenv "PORT") "25566"))})

;;; Rate limiting: max N submissions per IP per hour (in-memory)

(def ^:private max-per-hour 10)
(def ^:private submissions (atom {}))

(defn- client-ip [request]
  (let [xff (get-in request [:headers "x-forwarded-for"])]
    (if (and xff (not (str/blank? xff)))
      (str/trim (first (str/split xff #",")))
      (:remote-addr request))))

(defn- rate-limited? [ip]
  (let [cutoff (.minusSeconds (Instant/now) 3600)
        recent (->> (get @submissions ip [])
                    (filter #(.isAfter ^Instant % cutoff))
                    vec)]
    (swap! submissions assoc ip recent)
    (>= (count recent) max-per-hour)))

(defn- record-submission! [ip]
  (swap! submissions update ip (fnil conj []) (Instant/now)))

;;; Mojang profile lookup

(def ^:private http-client (HttpClient/newHttpClient))

(defn- lookup-profile
  "Returns {:id <undashed uuid> :name <canonical name>} or nil if no such account."
  [username]
  (try
    (let [req (-> (HttpRequest/newBuilder
                   (URI/create (str "https://api.mojang.com/users/profiles/minecraft/" username)))
                  (.header "User-Agent" "mc-whitelist")
                  (.GET)
                  (.build))
          resp (.send http-client req (HttpResponse$BodyHandlers/ofString))]
      (when (= 200 (.statusCode resp))
        (json/parse-string (.body resp) true)))
    (catch Exception _ nil)))

;;; Whitelist interaction

(defn- already-whitelisted? [canonical-name]
  (let [f (File. (:whitelist-json config))]
    (boolean
     (and (.exists f)
          (let [entries (json/parse-string (slurp f) true)]
            (some #(= (str/lower-case (or (:name %) ""))
                      (str/lower-case canonical-name))
                  entries))))))

(defn- write-whitelist-entry! [profile]
  ;; Mirror the entry into whitelist.json so a duplicate submission while the
  ;; server is down still shows as "already whitelisted" rather than
  ;; queueing a second command. This mirrors the vanilla JSON schema; the
  ;; server will not clobber it when it processes the console command.
  (let [f (File. (:whitelist-json config))]
    (when (.exists f)
      (let [entries (json/parse-string (slurp f) true)
            id (str/replace (:id profile)
                            #"(.{8})(.{4})(.{4})(.{4})(.{12})"
                            "$1-$2-$3-$4-$5")
            new (conj (vec entries) {:uuid id :name (:name profile)})]
        (spit f (json/generate-string new))))))

(defn- whitelist-add! [profile]
  ;; Writing to the server's stdin FIFO issues a console command; the server
  ;; persists whitelist.json itself. If the server is down the write fails
  ;; silently, which is fine: whitelist.json was already updated above, and
  ;; the server reads it at boot.
  (try
    (spit (:fifo config) (str "whitelist add " (:name profile) "\n") :append true)
    (catch Exception _ nil)))

;;; Pages

(defn- page [& body]
  (str
   (h/raw "<!DOCTYPE html>")
   (h/html
    [:html {:lang "en"}
     [:head
      [:meta {:charset "utf-8"}]
      [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
      [:title (:server-name config)]
      [:style
       (h/raw "
:root { color-scheme: dark; }
* { box-sizing: border-box; }
body { background: #1a1b1e; color: #e4e4e7; font-family: system-ui, sans-serif;
       display: flex; justify-content: center; padding: 3rem 1rem; margin: 0; }
main { max-width: 26rem; width: 100%; }
h1 { font-size: 1.5rem; margin-bottom: 0.25rem; }
p.dim { color: #a1a1aa; margin-top: 0; }
code { background: #27272a; padding: 0.15em 0.4em; border-radius: 4px; }
form { display: flex; gap: 0.5rem; margin: 1.5rem 0; }
input { flex: 1; padding: 0.6rem 0.8rem; border-radius: 6px; border: 1px solid #3f3f46;
        background: #27272a; color: inherit; font-size: 1rem; }
input:focus { outline: 2px solid #7c6cf0; border-color: transparent; }
button { padding: 0.6rem 1.2rem; border-radius: 6px; border: none; cursor: pointer;
         background: #7c6cf0; color: white; font-size: 1rem; font-weight: 600; }
button:hover { background: #8d7ff5; }
.notice { border-radius: 8px; padding: 1rem 1.25rem; margin: 1.5rem 0; }
.notice.ok { background: #14311f; border: 1px solid #2d6a4f; }
.notice.info { background: #1b2a41; border: 1px solid #3b5b8c; }
.notice.err { background: #3d1d1d; border: 1px solid #8c3b3b; }
.notice img { vertical-align: middle; border-radius: 4px; margin-right: 0.6rem; }
footer { margin-top: 2.5rem; color: #71717a; font-size: 0.85rem; }
")]]
     [:body
      [:main
       [:h1 (:server-name config)]
       [:p.dim "Minecraft: Java Edition"]
       body]]])))

(def ^:private form
  [:form {:method "post" :action "/"}
   [:input {:type "text" :name "username" :placeholder "Minecraft username"
            :minlength "3" :maxlength "16" :pattern "[A-Za-z0-9_]{3,16}"
            :autocomplete "off" :required true}]
   [:button {:type "submit"} "Join"]])

(defn- notice [kind & body]
  (into [:div {:class (str "notice " (name kind))}] body))

;;; Handlers

(defn- home-page []
  (page
   [:p "This server is whitelisted. Enter your Minecraft username below to add yourself, then connect to "
    [:code "doppel.moe"]]
   form))

(defn- submit [request]
  (let [username (str/trim (or (get-in request [:params "username"]) ""))
        ip (client-ip request)]
    (cond
      (not (re-matches #"[A-Za-z0-9_]{3,16}+" username))
      (page (notice :err "That doesn't look like a valid Minecraft username.") form)

      :else
      (if-let [profile (lookup-profile username)]
        (let [canonical (:name profile)]
          (cond
            (already-whitelisted? canonical)
            (page (notice :info
                          [:img {:src (str "https://mc-heads.net/avatar/" canonical "/40")
                                 :width "40" :height "40" :alt ""}]
                          [:strong canonical] " is already whitelisted. Connect to "
                          [:code (:server-name config)] "!"))

            (rate-limited? ip)
            (page (notice :err "Too many whitelist requests from your address. Try again later.") form)

            :else
            (do
              (record-submission! ip)
              (whitelist-add! profile)
              (write-whitelist-entry! profile)
              (page (notice :ok
                            [:img {:src (str "https://mc-heads.net/avatar/" canonical "/40")
                                   :width "40" :height "40" :alt ""}]
                            "Welcome, " [:strong canonical] "! You're whitelisted — connect to "
                            [:code (:server-name config)] ".")))))
        (page (notice :err "No Minecraft account exists with that username.") form)))))

(defroutes app-routes
  (GET "/" [] (home-page))
  (POST "/" request (submit request))
  (GET "/healthz" [] "ok")
  (route/not-found (page (notice :err "Not found."))))

(def app (wrap-params app-routes))

(defn -main [& _]
  (println (str "mc-whitelist listening on " (:host config) ":" (:port config)))
  (jetty/run-jetty app {:host (:host config)
                        :port (:port config)
                        :join? true}))
