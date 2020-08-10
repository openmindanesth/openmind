(ns openmind.components.window
  (:require [clojure.string :as string]
            [openmind.components.search :as search]
            [openmind.events :as events]
            [openmind.subs :as subs]
            [re-frame.core :as re-frame]
            reitit.frontend.easy))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Page Level
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def href reitit.frontend.easy/href)

(defn login-link []
  [:a {:href (href :login)} "login"])

(defn logout-link []
  ;; TODO: Make this a real page.
  [:a {:on-click #(re-frame/dispatch [::events/logout])} "logout"])

(defn create-extract-link []
  [:a {:href (href :extract/create)} "create new extract"])

;;;;; Login

(re-frame/reg-sub
 ::stay-logged-in?
 (fn [db]
   (::stay-logged-in? db)))

(re-frame/reg-event-db
 ::set-stay-logged-in
 (fn [db [_ v]]
   (assoc db ::stay-logged-in? v)))

(re-frame/reg-event-fx
 ::login
 (fn [cofx [_ stay? service]]
   ;; Only option
   (when (= service :orcid)
     {::nav-out (str "/login/orcid?stay=" (boolean stay?))})))

(re-frame/reg-fx
 ::nav-out
 (fn [url]
   (-> js/document
       .-location
       (set! url))))

(defn login-page []
  (let [stay? @(re-frame/subscribe [::stay-logged-in?])]
    [:div.flex.flex-column.left.mt2.ml2
     [:button.p1 {:on-click #(re-frame/dispatch [::login stay? :orcid])}
      [:img {:src "images/orcid.png"
             :style {:vertical-align :bottom}}]
      [:span.pl1 " login with Orcid"]]
     [:button.mt1.p1 {:disabled true}
      [:span "login via Orcid is the only method available at present"]]
     [:div.mt2
      [:label.pr1 {:for "stayloggedin"} [:b "stay logged in?"]]
      [:input {:type     :checkbox
               :checked  stay?
               :on-click #(re-frame/dispatch
                           [::set-stay-logged-in (not stay?)])
               :id       "stayloggedin"}]
      [:p.pl2.pt1.smaller.justify {:style {:max-width "25rem"}}
       (str
        "This checkbox doesn't actually do anything right now."
        " It's only here because it's expected."
        " You will stay logged in until either you log out,"
        " or the server gets updated,"
        " whichever comes first.")
       #_(if stay?
         "You will remain logged in until you explicitly log out."
         "You will be logged out automatically in 12 hours.")]]
     [:p.small.mt2.justify {:style {:max-width "24.5rem"}}
      [:em
       "This site uses cookies solely to maintain login information."]]]))

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
    [:div.search-result.ph.absolute.bg-light-grey.wide.pb2.pl1.pr1
     {:style          {:top     5
                       :left    5
                       :z-index 500
                       :opacity 0.95}
      :id             "nav-menu"
      :on-mouse-leave (fn [e]
                        (when-not (-> e
                                      .-relatedTarget
                                      .-id
                                      (= "menu-button"))
                          (re-frame/dispatch [::events/close-menu])))}
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
  (let [size   @(re-frame/subscribe [:screen-width])
        mb     [:button.border-round.menu-button
                {:id       "menu-button"
                 :style    {:z-index      1000
                            :border-width "2px"}
                 :on-click #(re-frame/dispatch
                             (if @(re-frame/subscribe [::subs/menu-open?])
                               [::events/close-menu]
                               [::events/open-menu]))}
                [:span
                 {:style {:padding         "0.2rem"
                          :text-decoration :underline}}
                 "menu"]]
        om     [:a.ctext.grow-1.pl1.pr1.xxl.pth.plain
                {:href  (href :search)
                 :style {:cursor :pointer}}
                "open" [:b "mind"]]
        search [search/search-box]]
    [:div
     (if (< size 620)
       [:div
        [:div.flex.space-between.pb1.mr1 mb om]
        search]
       [:div.flex.space-between
        mb om search])
     (when @(re-frame/subscribe [::subs/menu-open?])
       [menu])]))

(re-frame/reg-sub
 ::status-message
 (fn [db]
   (::status-message db)))

(re-frame/reg-event-db
 ::wipe-notify
 (fn [db [_ id]]
   (let [nid (get-in db [::status-message :id])]
     (if (= nid id)
       (dissoc db ::status-message)
       db))))

(re-frame/reg-event-fx
 ::dismiss-notify
 (fn [{:keys [db]} [_ id]]
   (let [nid (get-in db [::status-message :id])]
     (when (= id nid)
       {:db (update db ::status-message #(when % (assoc % :closing? true)))
        :dispatch-later [{:ms 1000 :dispatch [::wipe-notify id]}]}))))

(re-frame/reg-event-fx
 :notify
 (fn [{:keys [db]} [_ msg]]
   (let [id (keyword (gensym "notification"))]
     {:db             (assoc db ::status-message (assoc msg :id id))
      :dispatch-later [{:ms 4000 :dispatch [::dismiss-notify id]}]})))

(defn status-message-bar [{:keys [status message closing? id] :as notif}]
  (when notif
    [:div.fixed
     {:style {:opacity (if closing? 0.0 0.95)
              :top 0
              :left 0
              :width "100%"
              :z-index 2000
              :transition "opacity 2s ease"}}
     [:div.pt1.pb1.pl1.border-round.m1
      {:style {:min-height "5rem"

               :margin "0.2rem"}
       :class (if (= status :success)
                "bg-green"
                "bg-red")}
      [:a.right.pr2.underline
       {:on-click #(re-frame/dispatch [::dismiss-notify id])} "dismiss"]
      (into [:div.pt1.pl3.pr3.ctext]
            (map #(do [:p %]) (string/split-lines message)))]]))

(re-frame/reg-sub
 ::spinner
 (fn [db _]
   (::spinner db)))

(re-frame/reg-event-db
 ::spin
 (fn [db _]
   (assoc db ::spinner true)))

(re-frame/reg-event-db
 ::unspin
 (fn [db _]
   (dissoc db ::spinner)))

(re-frame/reg-cofx
 ::screen-width
 (fn [cofx _]
   (assoc cofx ::screen-width (.-innerWidth js/window))))

(re-frame/reg-event-fx
 ::resize-window
 [(re-frame/inject-cofx ::screen-width)]
 (fn [cofx _]
   {:db (assoc (:db cofx) ::screen-width (::screen-width cofx))}))

(re-frame/reg-sub
 :screen-width
 (fn [db]
   (::screen-width db)))

(re-frame/reg-sub
 :responsive-size
 (fn [_]
   (re-frame/subscribe [:screen-width]))
 (fn [width]
   (cond
     (< width 420) :mobile
     ;; (< width 800) :tablet
     :else         :desktop)))

(defn main [content]
  (let [status-message @(re-frame/subscribe [::status-message])
        spinning?      @(re-frame/subscribe [::spinner])]
    [:div.ph
     (when spinning? {:style {:cursor :wait}})
     [title-bar]
     [status-message-bar status-message]
     [:div.vspacer]
     [content]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Main Routing Table
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def other-routes
  [["/login" {:name      :login
              :component login-page}]])
