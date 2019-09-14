(ns openmind.views
  (:require [openmind.events :as events]
            [openmind.search :as search]
            [openmind.subs :as subs]
            [openmind.views.extract :as extract]
            [re-frame.core :as re-frame]
            reitit.frontend.easy))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Page Level
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def href reitit.frontend.easy/href)

(defn login-link []
  [:a {:href "/oauth2/orcid"} "login with Orcid"])

(defn logout-link []
  [:a {:on-click #(re-frame/dispatch [::events/logout])} "logout"])

(defn create-extract-link []
  [:a {:href (href ::new-extract)} "create new extract"])

(defn logged-in-menu-items []
  [[create-extract-link]
   [logout-link]])

(def anon-menu-items
  [[login-link]])

(defn fake-key [xs]
  (map-indexed (fn [i x]
                 (with-meta x (assoc (meta x) :key i)))
               xs))

(defn menu []
  (let [login @(re-frame/subscribe [::subs/login-info])]
    [:div.search-result.padded.absolute.bg-light-grey.wide.pb2.pl1.pr1
     {:style          {:top     5
                       :left    5
                       :opacity 0.95}
      :id             "nav-menu"
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
   [:div.flex.space-between.mr2
    [:button.z100
     {:on-click #(re-frame/dispatch (if @(re-frame/subscribe [::subs/menu-open?])
                                      [::events/close-menu]
                                      [::events/open-menu]))}
     [:span.ham "Ξ"]]
    [:a.ctext.grow-1.pl1.pr1.xxl.pth
     {:href (href :openmind.search/search)
      :style {:cursor :pointer
              :text-decoration :inherit
              :color :inherit}}
     "open" [:b "mind"]]
    [:input.grow-2 {:type :text
                    :on-change (fn [e]
                                 (let [v (-> e .-target .-value)]
                                   (re-frame/dispatch
                                    [::search/update-term v])))
                    :placeholder "specific term"}]]
   (when @(re-frame/subscribe [::subs/menu-open?])
     [menu])])

(defn status-message-bar [{:keys [status message]}]
  [:div.pt1.pb1.pl1
   {:class (if (= status :success)
             "bg-green"
             "bg-red")}
   [:span message]])

(defn main [content]
  (let [status-message @(re-frame/subscribe [::subs/status-message])]
    [:div.padded
     [title-bar]
     (when status-message
       [:div.vspacer
        [status-message-bar status-message]])
     [:div.vspacer]
     [content]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Main Routing Table
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def extract-creation-routes
  [["/new" {:name      ::new-extract
            :component extract/editor-panel}]])

(def routes
  "Combined routes from all pages."
  (concat search/routes extract-creation-routes))
