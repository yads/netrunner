(ns netrunner.cardbrowser
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [om.core :as om :include-macros true]
            [sablono.core :as sab :include-macros true]
            [cljs.core.async :refer [chan put!] :as async]
            [netrunner.ajax :refer [GET]]))

(def app-state (atom {:cards [] :sets []}))

(defn make-span [text symbol class]
  (.replace text (js/RegExp. symbol "g") (str "<span class='anr-icon " class "'></span>")))

(defn add-symbols [card-text]
  (-> card-text
      (make-span "\\[Credits\\]" "credit")
      (make-span "\\[Click\\]" "click")
      (make-span "\\[Subroutine\\]" "subroutine")
      (make-span "\\[Recuring Credits\\]" "recuring-credit")
      (make-span "1\\[Memory Unit\\]" "mu1")
      (make-span "2\\[Memory Unit\\]" "mu2")
      (make-span "3\\[Memory Unit\\]" "mu3")
      (make-span "\\[Memory Unit\\]" "mu")
      (make-span "\\[Link\\]" "link")
      (make-span "\\[Trash\\]" "trash")))

(defn card-view [card owner]
  (om/component
   (let [base-url "http://netrunnerdb.com/web/bundles/netrunnerdbcards/images/cards/en/"]
     (sab/html
      [:div.card.blue-shade
       [:h4 (:title card)]
       (when-let [memory (:memoryunits card)]
         (if (< memory 3)
           [:div.anr-icon {:class (str "mu" memory)} ""]
           [:div.heading (str "Memory: " memory) [:span.anr-icon.mu]]))
       (when-let [cost (:cost card)]
         [:div.heading (str "Cost: " cost)])
       (when-let [trash-cost (:trash card)]
         [:div.heading (str "Trash cost: " trash-cost)])
       (when-let [strength (:strength card)]
         [:div.heading (str "Strength: " strength)])
       (when-let [requirement (:advancementcost card)]
         [:div.heading (str "Advancement requirement: " requirement)])
       (when-let [agenda-point (:agendatpoints card)]
         [:div.heading (str "Agenda points: " agenda-point)])
       (when-let [min-deck-size (:minimumdecksize card)]
         [:div.heading (str "Minimum deck size: " min-deck-size)])
       (when-let [influence-limit (:influencelimit card)]
         [:div.heading (str "Influence limit: " influence-limit)])
       (when-let [influence (:factioncost card)]
         [:div.heading (str "Influence: " influence)])
       [:div.text
        [:p [:span.type (str (:type card))] (if (empty? (:subtype card))
                                                 "" (str ": " (:subtype card)))]
        [:pre {:dangerouslySetInnerHTML #js {:__html (add-symbols (:text card))}}]]
       [:img {:src (str base-url (:code card) ".png")
              :onError #(-> % .-target js/$ .hide)}]]))))

(defn set-view [{:keys [set set-filter]} owner]
  (reify
    om/IRenderState
    (render-state [this state]
      (let [name (:name set)]
        (sab/html
         [:div {:class (if (= set-filter name) "active" "")
                :on-click #(put! (:ch state) {:filter :set-filter :value name})}
          name])))))

(defn types [side]
  (let [runner-types ["Identity" "Program" "Hardware" "Resource" "Event"]
        corp-types ["Agenda" "Asset" "ICE" "Operation" "Upgrade"]]
    (case side
      "All" (concat  runner-types corp-types)
      "Runner" runner-types
      "Corp" (cons "Identity" corp-types))))

(defn factions [side]
  (let [runner-factions ["Anarch" "Criminal" "Shaper"]
        corp-factions ["Jinteki" "Haas-Bioroid" "NBN" "Weyland Consortium" "Neutral"]]
    (case side
      "All" (concat runner-factions corp-factions)
      "Runner" (conj runner-factions "Neutral")
      "Corp" corp-factions)))

(defn options [list]
  (let [options (cons "All" list)]
    (for [option options]
      [:option {:value option} option])))

(defn filter-cards [filter-value field cards]
  (if (= filter-value "All")
    cards
    (filter #(= (field %) filter-value) cards)))

(defn match [query cards]
  (if (empty? query)
    cards
    (filter #(if (= (.indexOf (.toLowerCase (:title %)) query) -1) false true) cards)))

(defn sort-field [fieldname]
  (case fieldname
    "Name" :title
    "Influence" :factioncost
    "Cost" :cost
    "Faction" (juxt :side :faction)
    "Type" (juxt :side :type)
    "Set number" :number))

(defn handle-scroll [e owner {:keys [page]}]
  (let [$cardlist (js/$ ".card-list")
        height (- (.prop $cardlist "scrollHeight") (.innerHeight $cardlist))]
    (when (> (.scrollTop $cardlist) (- height 600))
      ;; (om/update-state! owner :page inc)
      (om/set-state! owner :page (inc (om/get-state owner :page))))))

(defn card-browser [cursor owner]
  (reify
    om/IInitState
    (init-state [this]
      {:search-query ""
       :sort-field "Faction"
       :set-filter "All"
       :type-filter "All"
       :side-filter "All"
       :faction-filter "All"
       :page 1
       :filter-ch (chan)})

    om/IWillMount
    (will-mount [this]
      (go (while true
            (let [f (<! (om/get-state owner :filter-ch))]
              (om/set-state! owner (:filter f) (:value f))))))

    om/IRenderState
    (render-state [this state]
      (.focus (js/$ ".search"))
      (sab/html
       [:div.cardbrowser
        [:div.blue-shade.panel.filters
         (let [query (:search-query state)]
           [:div.search-box
            [:span.e.search-icon {:dangerouslySetInnerHTML #js {:__html "&#128269;"}}]
            (when-not (empty? query)
              [:span.e.search-clear {:dangerouslySetInnerHTML #js {:__html "&#10006;"}
                                     :on-click #(om/set-state! owner :search-query "")}])
            [:input.search {:on-change #(om/set-state! owner :search-query (.. % -target -value))
                            :type "text" :placeholder "Search cards" :value query}]])

         [:div
          [:h4 "Sort by"]
          [:select {:value (:sort-filter state)
                    :on-change #(om/set-state! owner :sort-field (.. % -target -value))}
           (for [field ["Faction" "Name" "Type" "Influence" "Cost" "Set number"]]
             [:option {:value field} field])]]

         (for [filter [["Set" :set-filter (map :name (:sets cursor))]
                       ["Side" :side-filter ["Corp" "Runner"]]
                       ["Faction" :faction-filter (factions (:side-filter state))]
                       ["Type" :type-filter (types (:side-filter state))]]]
           [:div
            [:h4 (first filter)]
            [:select {:value ((second filter) state)
                      :on-change #(om/set-state! owner (second filter) (.. % -target -value))}
             (options (last filter))]])]

        [:div.card-list {:on-scroll #(handle-scroll % owner state)}
         (om/build-all card-view
                       (->> (:cards cursor)
                            (filter-cards (:set-filter state) :setname)
                            (filter-cards (:side-filter state) :side)
                            (filter-cards (:faction-filter state) :faction)
                            (filter-cards (:type-filter state) :type)
                            (match (.toLowerCase (:search-query state)))
                            (sort-by (sort-field (:sort-field state)))
                            (take (* (:page state) 32)))
                       {:key :code})]]))))

(om/root card-browser app-state {:target (. js/document (getElementById "cardbrowser"))})

(go (swap! app-state assoc :sets (<! (GET "data/sets"))))
(go (swap! app-state assoc :cards (<! (GET "data/cards"))))
