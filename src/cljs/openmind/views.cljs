(ns openmind.views
  (:require
   [reagent.core :as reagent]
   [re-frame.core :as re-frame]
   [openmind.events :as events]
   [openmind.subs :as subs]
   [openmind.views.tags :as tags]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Page Level
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn four-o-four []
  [:h2 "You're in a bad place."])

(defn title-bar []
  [:div.flex.space-between
   [:button
    {:on-click #(re-frame/dispatch [::events/toggle-edit])}
    [:span.ham "Ξ"]]
   [:div.ctext.grow-1.pl1.pr1.xxl.pth "open" [:b "mind"]]
   [:input.grow-2 {:type :text
                   :on-change (fn [e]
                                (let [v (-> e .-target .-value)]
                                  (re-frame/dispatch
                                   [::events/search v])))
                   :placeholder "specific term"}]])

(defn window [content]
  [:div.padded
   [title-bar]
   [:div.vspacer]
   [content]])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Extract Display
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn hlink [text float-content]
  (let [hover? (reagent/atom false)]
    (fn [text float-content]
      [:div
       [:a.plh.prh.link-blue {:on-mouse-over #(reset! hover? true)
                     :on-mouse-out  #(reset! hover? false)}
        text]
       (when @hover?
         [:div.absolute.absolute-c
          float-content])])))

(defn comments-link []
  [hlink "comments"])

(defn history-link []
  [hlink "history"])

(defn reference-link [ref]
  [hlink ref])

(defn format-tags [tags]
  ;; FIXME: flex isn't the right solution here.
  (into [:div.flex.flex-column.bg-white.p1.border-round]
        (comp (remove nil?)
              (map (fn [[k v]]
                     (when (and k (seq v))
                       [:div
                        (into
                         [:div.flex.space-between
                          [:span.basis-12 (str (name k) ":")]]
                         (interpose [:span.pl1.shrink-2 ","]
                                    (map (fn [x] [:span.pl1 (name x)]) v)))]))))
        tags))

(defn result [{:keys [text reference tags]}]
  [:div.search-result.padded
   [:div.break-wrap.ph text]
   [:div.pth
    [:div.flex.flex-wrap.space-evenly
     [comments-link (:comments tags)]
     [history-link]
     [hlink "related"]
     [hlink "details"]
     [hlink "tags" (format-tags tags)]
     [hlink "figure"]
     [reference-link reference]]]])

(defn search-results []
  (let [results @(re-frame/subscribe [::subs/extracts])]
    (into [:div]
          (map (fn [r] [result r]) results))))

(defn search-view []
  [:div
   [tags/search-filter]
   [:hr.mb1.mt1]
   [search-results]])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; New Extract Authoring
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn pass-off [k]
  (fn [ev]
    (re-frame/dispatch [::events/form-data k (-> ev .-target .-value)])))

(defn text-box
  [k label & [{:keys [placeholder class]}]]
  (let [content @(re-frame/subscribe [k])]
    [:div.flex.vcenter.mb1h
     [:span.basis-12  [:b label]]
     [:input.grow-4 (merge {:id        (name k)
                            :type      :text
                            :on-change (pass-off k)}
                           (cond
                             (seq content) {:value content}
                             placeholder   {:value       nil
                                            :placeholder placeholder})
                           (when class
                             {:class class}))]]))

;; FIXME: stub
(defn pass-edit [k i]
  (fn [ev]
    (re-frame/dispatch [::events/nested-form k i (-> ev .-target .-value)])))

(defn list-element [k i c]
  [:input (merge {:type :text
                  :on-change (pass-edit k i)}
                 (if (seq c)
                   {:value c}
                   {:value nil
                    :placeholder "link to paper"}))])

(defn addable-list
  [k label & [opts]]
  (let [content @(re-frame/subscribe [k])]
    [:div.flex.vcenter.mb1h
     [:span.basis-12 [:b label]]
     (into [:div]
        (map (fn [[i c]]
               [list-element k i c]))
        content)
     [:a.plh {:on-click (fn [_]
                          (re-frame/dispatch
                           [::events/nested-form k (count content) ""]))}
      "[+]"]]))

(defn editor-panel []
  [:div.flex.flex-column.flex-start.pl2.pr2
   [:div.flex.pb1.space-between
    [:h2 "create a new extract"]
    [:button.blue.border-round.wide
     {:on-click (fn [_]
                  (re-frame/dispatch [::events/create-extract]))}
     "CREATE"]]
   [text-box :extract "extract"
    {:placeholder "an insight or takeaway from the paper"}]
   [text-box :link "source article"
    {:placeholder "https://www.ncbi.nlm.nih.gov/pubmed/..."}]
   [text-box :figure "figure link"
    {:placeholder "link to a figure that demonstrates your point"}]
   [text-box :comments "comments"
    {:placeholder "anything you think is important"}]
   [addable-list :confirmed "confirmed by"]
   [addable-list :contrast "in contrast to"]
   [addable-list :related "related results"]
   [:h4.ctext "add filter tags"]
   [tags/tag-selector]])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Entry
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn main-view []
  (let [route @(re-frame/subscribe [::subs/route])]
    [window
     (cond
       (= route ::search) search-view
       (= route ::create) editor-panel
       :else              four-o-four)]))
