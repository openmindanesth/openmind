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

(defn login-link []
  [:a {:href "/oauth2/orcid"} "login with Orcid"])

(defn logout-link []
  [:a {:on-click #(re-frame/dispatch [::events/logout])} "logout"])

(defn create-extract-link []
  [:a {:on-click #(re-frame/dispatch [::events/nav-create-extract])}
   "create new extract"])

(defn logged-in-menu-items []
  (let [route @(re-frame/subscribe [::subs/route])]
    (if (= route ::create)
      [[logout-link]]
      [[create-extract-link]
       [logout-link]])))

(def anon-menu-items
  [[login-link]])

(defn fake-key [xs]
  (map-indexed (fn [i x]
                 (with-meta x (assoc (meta x) :key i)))
               xs))

(defn menu []
  (let [login @(re-frame/subscribe [::subs/login-info])]
    [:div.search-result.padded.absolute.bg-grey.translucent-9.wide.pb2.pl1.pr1
     {:style {:top 5
              :left 5}
      :id "nav-menu"
      :on-mouse-leave #(re-frame/dispatch [::events/close-menu])}
     [:div.mt4
      (when (seq login)
        [:span "welcome " (:name login)])]
     [:hr.mb1.mt1]
     (fake-key
      (interpose [:hr.mb1.mt1]
                 (if (seq login)
                   (logged-in-menu-items)
                   anon-menu-items)))]))

(defn title-bar []
  [:div
   [:div.flex.space-between
    [:button.z100
     {:on-click #(re-frame/dispatch (if @(re-frame/subscribe [::subs/menu-open?])
                                      [::events/close-menu]
                                      [::events/open-menu]))}
     [:span.ham "Ξ"]]
    [:div.ctext.grow-1.pl1.pr1.xxl.pth
     {:on-click #(re-frame/dispatch [::events/nav-search])
      :style {:cursor :pointer}}
     "open" [:b "mind"]]
    [:input.grow-2 {:type :text
                    :on-change (fn [e]
                                 (let [v (-> e .-target .-value)]
                                   (re-frame/dispatch
                                    [::events/search v])))
                    :placeholder "specific term"}]]
   (when @(re-frame/subscribe [::subs/menu-open?])
     [menu])])

(defn status-message-bar [{:keys [status message]}]
  [:div.pt1.pb1.pl1
   {:class (if (= status :success)
             "bg-green"
             "bg-red")}
   [:span message]])

(defn window [content]
  (let [status-message @(re-frame/subscribe [::subs/status-message])]
    [:div.padded
     [title-bar]
     (when status-message
       [:div.vspacer
        [status-message-bar status-message]])
     [:div.vspacer]
     [content]]))


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
         [:div.absolute
          float-content])])))

(defn comments-link []
  [hlink "comments"])

(defn history-link []
  [hlink "history"])

(defn reference-link [ref]
  [hlink ref])

(defn format-tags [tags]
  ;; FIXME: flex isn't the right solution here.
  (let [tag-lookup @(re-frame/subscribe [::subs/tag-lookup])]
    (when (seq tags)
      (into [:div.flex.flex-column.bg-white.p1.border-round]
            (map (fn [id]
                   [:div (get tag-lookup id)]))
            tags))))

(defn result [{:keys [text reference]
               {:keys [comments details related figure] :as tags} :tags}]
  [:div.search-result.padded
   [:div.break-wrap.ph text]
   [:div.pth
    [:div.flex.flex-wrap.space-evenly
     [comments-link (:comments tags)]
     [history-link]
     [hlink "related" related]
     [hlink "details" details]
     [hlink "tags" (format-tags tags)]
     [hlink "figure" figure]
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
    (re-frame/dispatch [::events/form-edit [k] (-> ev .-target .-value)])))

(defn text-box
  [k label & [{:keys [placeholder class]}]]
  (let [content @(re-frame/subscribe [k])]
    [:div.flex.vcenter.mb1h
     [:span.basis-12 [:b label]]
     [:input.grow-4 (merge {:id        (name k)
                            :type      :text
                            :on-change (pass-off k)}
                           (cond
                             (seq content) {:value content}
                             placeholder   {:value       nil
                                            :placeholder placeholder})
                           (when class
                             {:class class}))]]))

(defn pass-edit [k i]
  (fn [ev]
    (re-frame/dispatch [::events/form-edit [k i] (-> ev .-target .-value)])))

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
                           [::events/form-edit [k (count content)] ""]))}
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
   [text-box :source "source article"
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
       ;; TODO: Link route to URL, ditch stupid tag system
       (= route ::search) search-view
       (= route ::create) editor-panel
       :else              four-o-four)]))
