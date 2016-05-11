(ns cryptoquants.core
  (:require-macros [cljs.core.async.macros :refer [go-loop]])
  (:require [goog.dom :as gdom]
            [om.next :as om :refer-macros [defui]]
            [om.dom :as dom]
            [cljsjs.bootstrap]
            [cljsjs.autobahn]
            [cryptoquants.table :as table]
            [cryptoquants.poloniex :as poloniex]
            [cljs.core.async :refer [<!]]))

(enable-console-print!)

(def markets (atom {:app/route '[:app/markets _]
                    :app/markets {:markets/title "Poloniex Markets"
                                  :markets/content {:table {:hdgs ["Currency Pair" "Last" "Lowest Ask" "Highest Bid" "% Change" "Base Volume" "Quote Volume" "24hr High" "24hr Low"]
                                                            :rows {}}}}
                    :app/about {:about/title "About CryptoQuants"
                                :about/content "Some blurb about CryptoQuants"}}))

(defui Markets
  static om/IQuery
  (query [this]
    [:markets/title :markets/content])
  Object
  (render [this]
    (let [{:keys [markets/title markets/content]} (om/props this)]
      (dom/div #js {:className "col-md-12"}
        (dom/h1 nil title)
        (table/market-table content)))))

(defui About
  static om/IQuery
  (query [this]
    [:about/title :about/content])
  Object
  (render [this]
    (let [{:keys [about/title about/content]} (om/props this)]
      (dom/div #js {:className "col-md-12"}
        (dom/h1 nil title)
        (dom/p nil content)))))

(def route->component
  {:app/markets Markets
   :app/about About})

(def route->factory
  (zipmap (keys route->component)
    (map om/factory (vals route->component))))

(defn- change-route [c route e]
  (.preventDefault e)
  (om/transact! c `[(~'change/route! {:route ~route})]))

(defui Root
  static om/IQuery
  (query [this]
    [:app/route
     {:route/data (zipmap (keys route->component)
                    (map om/get-query (vals route->component)))}])
  Object
  (render [this]
    (dom/div nil 
      (dom/nav #js {:id "nav" :className "navbar navbar-inverse navbar-fixed-top"}
        (dom/div #js {:className "container"}
          (dom/div #js {:className "navbar-header"}
            (dom/button #js {:type "button" :className "navbar-toggle collapsed" 
                             :data-toggle "collapse" :data-target "#navbar" 
                             :aria-expanded "false" :aria-controls "navbar"}
              (dom/span #js {:className "sr-only"} "Toggle navigation")
              (dom/span #js {:className "icon-bar"})
              (dom/span #js {:className "icon-bar"})
              (dom/span #js {:className "icon-bar"}))
            (dom/a #js {:className "navbar-brand" :href "#"} "CryptoQuants"))
          (dom/div #js {:id "navbar" :className "navbar-collapse collapse"}
            (dom/ul #js {:className "nav navbar-nav"}
              (dom/li #js {:className "active"} 
                (dom/a #js {:href "#"
                            :onClick #(change-route this '[:app/markets _] %)} "Home"))
              (dom/li nil 
                (dom/a #js {:href "#about"
                            :onClick #(change-route this '[:app/about _] %)} "About"))))))
      (let [{:keys [app/route route/data]} (om/props this)]
        ((route->factory (first route)) data)))))

    ;; <nav id="nav" class="navbar navbar-inverse navbar-fixed-top">
    ;;   <div class="container">
    ;;     <div class="navbar-header">
    ;;       <button type="button" class="navbar-toggle collapsed" data-toggle="collapse" data-target="#navbar" aria-expanded="false" aria-controls="navbar">
    ;;         <span class="sr-only">Toggle navigation</span>
    ;;         <span class="icon-bar"></span>
    ;;         <span class="icon-bar"></span>
    ;;         <span class="icon-bar"></span>
    ;;       </button>
    ;;       <a class="navbar-brand" href="#">Cryptoquants</a>
    ;;     </div>
    ;;     <div id="navbar" class="navbar-collapse collapse">
    ;;       <ul class="nav navbar-nav">
    ;;         <li class="active"><a href="#">Home</a></li>
    ;;         <li><a href="#about">About</a></li>
    ;;       </ul>
    ;;     </div><!--/.nav-collapse -->
    ;;   </div>
    ;; </nav>


(defmulti read om/dispatch)
(defmulti mutate om/dispatch)

(defmethod read :route/data
  [{:keys [state query]} k _]
  (let [st @state
        route (get st :app/route)
        route (cond-> route (= (second route) '_) pop)]
    ;; since the route is an ident, it could also be 
    ;; passed as the second argument to 'db->tree' if 
    ;; our data was normalized
    {:value (get-in st route)}))

(defmethod read :app/route
  [{:keys [state query]} k _]
  (let [st @state]
    {:value (get st k)}))

(defmethod mutate 'change/route!
  [{:keys [state]} _ {:keys [route]}]
  {:value {:keys [:app/route]}
   :action #(swap! state assoc :app/route route)})

;; (defn read [{:keys [state] :as env} key params]
;;   (let [st @state]
;;     (if-let [[_ value] (find st key)]
;;       {:value value}
;;       {:value "not-found"})))

;; (defn mutate [{:keys [state] :as env} key params]
;;   (println "mutate, key: " key ", params: " params)
;;   (if (= :table key)
;;     {:value {:keys [:rows]}
;;      :action #(swap! state assoc-in [:table :rows (key params)] (vals params))}
;;     {:value :not-found}))

(defn merge-market [state market] 
  (let  [currency-pair   (get market "currencyPair")
         currency-values (map market ["currencyPair" "last" "lowestAsk" "highestBid" "percentChange" "baseVolume" "quoteVolume" "24hrHigh" "24hrLow"])
         frozen?         (= 1 (get market "isFrozen"))]
    (assoc-in state 
              [:app/markets :markets/content :table :rows currency-pair] 
              {:isFrozen frozen? :market-values (vec currency-values)})))

(def reconciler
  (om/reconciler 
    {:state markets
     :parser (om/parser {:read read :mutate mutate})
     :merge-tree merge-market}))

(om/add-root! reconciler
  Root (gdom/getElement "app"))

(defn update-market [market]
  (om/merge! reconciler market)
  (om/force-root-render! reconciler))

(let [ticker-chan (poloniex/start)]
  (go-loop [market (<! ticker-chan)]
    (update-market market)
    (recur (<! ticker-chan))))


;; TODO:
;; -  Manage css using sass or equivalent
;; 
;; - List the markets with the currency with the most activity listed first:
;; --"Currency" "%Increase: Minute, 15 Mins, 30 Mins, Hour, 8 Hours, 24 Hours"
;;


