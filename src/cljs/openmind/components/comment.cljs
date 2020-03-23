(ns openmind.components.comment
  (:require [openmind.edn :as edn]
            [openmind.components.extract.core :as core]
            [openmind.components.extract.editor :as editor]
            [openmind.util :as util]
            [re-frame.core :as re-frame]
            [reagent.core :as r]))

(defn- get-comment [db id]
  (get-in db [::new-comments id]))

(re-frame/reg-sub
 ::new-comment
 (fn [db [_ id]]
   (get-comment db id)))

(re-frame/reg-event-db
 ::new-comment
 (fn [db [_ id text]]
   (assoc-in db [::new-comments id :text] text)))

(re-frame/reg-event-fx
 ::save-new-comment
 (fn [{:keys [db]} [_ page-id reply-id]]
   (let [id             (or reply-id page-id)
         {:keys [text]} (get-comment db id)]
     (if (empty? text)
       {:db (assoc-in db [::new-comments id :errors] "Comments can't be blank.")}
       (let [author  (:login-info db)
             comment (merge
                      {:text text :author author :extract page-id}
                      (when reply-id
                        {:reply-to reply-id}))]
         ;; TODO: Clear comment entry area on successful intern
         {:db         (assoc-in db [::new-comments id] {})
          :dispatch-n [[:->server
                        [:openmind/intern (util/immutable comment)]]
                       (when reply-id
                         [::close-reply reply-id])]})))))


(re-frame/reg-event-fx
 ::revalidate
 (fn [{:keys [db]} [_ id]]
   (let [{:keys [text]} (get-comment db id)]
     (when (seq text)
       {:db (assoc-in db [::new-comments id :errors] nil)}))))

(re-frame/reg-sub
 ::comments
 (fn [[_ id]]
   (re-frame/subscribe [:lookup id]))
 (fn [meta [_ id]]
   (:comments (:content meta))))

(def dateformat
  (new (.-DateTimeFormat js/Intl) "en-GB"
       (clj->js {:year   "numeric"
                 :month  "long"
                 :day    "numeric"
                 :hour   "numeric"
                 :minute "numeric"})))

(defn comment-entry-box [{:keys [id on-save message cancel?]}]
  (let [{:keys [text errors]} @(re-frame/subscribe [::new-comment id])]
    [:div.flex.full-width
     [:div.full-width.pr1
      [:textarea.full-width-textarea
       (merge
        {:type :text
         :rows 2
         :style {:resize :vertical}
         :on-change (fn [ev]
                      (let [v (-> ev .-target .-value)]
                        (when errors
                          (re-frame/dispatch [::revalidate id]))
                        (re-frame/dispatch [::new-comment id v])))
         :value text}
        (when errors
          {:class "form-error"}))]
      (when errors
        [editor/error errors])]
     [:button.bg-dark-grey.border-round.wide.text-white.p1
      {:style {:max-height "4rem"
               :right 0}
       :on-click on-save}
      message]
     (when cancel?
       [:button.bg-red.border-round.wide.text-white.p1.mlh
        {:on-click (fn [_]
                     (re-frame/dispatch [::close-reply id]))
         :style {:opacity 0.6
                 :max-height "4rem"}}
        "CANCEL"])]))

(defn new-comment [id]
  [comment-entry-box {:id      id
                      :on-save #(re-frame/dispatch [::save-new-comment id nil])
                      :message "COMMENT"}])
(defn reply [eid cid]
  [comment-entry-box {:id      cid
                      :on-save #(re-frame/dispatch [::save-new-comment eid cid])
                      :cancel? true
                      :message "REPLY"}])

(re-frame/reg-sub
 ::active-reply?
 (fn [db [_ cid]]
   (get-in db [::active-replies cid])))

(re-frame/reg-event-db
 ::open-reply
 (fn [db [_ cid]]
   (assoc-in db [::active-replies cid] true)))

(re-frame/reg-event-db
 ::close-reply
 (fn [db [_ cid]]
   (update db ::active-replies dissoc cid)))

(re-frame/reg-event-fx
 ::vote
 (fn [{:keys [db]} [_ {:keys [extract hash votes] :as comment} vote]]
   (let [author (:login-info db)]
     (let [rec {:vote vote :extract extract :comment hash :author author}]
       {:dispatch [:->server [:openmind/intern (util/immutable rec)]]}))))

(defn points [rank]
  [:span (or rank 0) " points"])

(defn upvoted [rank]
  [:div.flex.flex-centre
   [:div.prh
    {:style {:margin-left  "2rem"}}
    [:b.text-dark-grey "↑"]]])

(defn downvoted [rank]
  [:div.flex.flex-centre
   [:div.prh
    {:style {:margin-left  "2rem"}}
    [:b.text-dark-grey "↓"]]])

(defn unvoted [{:keys [rank] :as comment}]
  [:div.flex.flex-centre
   [:div.plh
    {:on-click #(re-frame/dispatch [::vote comment 1])
     :style    {:cursor        :pointer
                :padding-right "0.05rem"
                :margin-right  "0.2rem"}}
    [:b "↑"]]
   [:div.prh
    {:on-click #(re-frame/dispatch [::vote comment -1])
     :style    {:cursor       :pointer
                :padding-left "0.05rem"
                :margin-left  "0.2rem"}}
    [:b "↓"]]])

(defn vote-widget [{:keys [author rank votes] :as comment} login]
  (let [self? (= author login)
        vote  (-> votes (get login) :vote)]
    (cond self?       [:div.ml3]
          (= vote 1)  [upvoted]
          (= vote -1) [downvoted]
          :else       [unvoted comment])))

(declare comment-box)

(defn comment-list [comments]
  (into [:div.flex.flex-column]
        (map (fn [c]
               ^{:key (:hash c)} [comment-box c]))
        (sort-by :rank > comments)))

(defn comment-box [{:keys [text time/created replies author hash rank extract]
                    :as   comment}]
  (let [active-reply? @(re-frame/subscribe [::active-reply? hash])
        login         @(re-frame/subscribe [:openmind.subs/login-info])]
    [:div.break-wrap.ph.mbh.flex.flex-column
     [:div.flex
      [vote-widget comment login]
      [:div
       [:a.unlink {:href (str "https://orcid.org/" (:orcid-id author))}
        [:span.text-black.small [:b (:name author)]]]]
      [:div.pl1
       [points rank]]
      [:span.pl1 [:em (.format dateformat created)]]
      [:div.text-dark-grey.ml2
       {:style    {:cursor :pointer}
        :on-click #(re-frame/dispatch [::open-reply hash])}
       "reply⤵"]]
     [:div.flex.flex-column.ml2
      [:p text]
      (when active-reply?
        [reply extract hash])
      (when replies
        [comment-list replies])]]))

(defn comment-tree [id content]
  [:div.flex.flex-column
   [new-comment id]
   [:div.vspacer]
   (if (seq content)
     [:div.p1.pbh
      [comment-list content]]
     [:span.p2 "No one has commented on this extract yet."])])

(defn comment-hover-content [id]
  (let [content (r/atom [])]
    (fn [id]
      (let [meta-id  @(re-frame/subscribe [:extract-metadata id])
            comments (when meta-id
                       @(re-frame/subscribe [::comments meta-id]))]
        (when comments
          (reset! content comments))
        [comment-tree id @content]))))

(defn comments-page
  [{{:keys [id]} :path-params}]
  (let [id      (edn/read-string id)
        content (r/atom [])]
    (fn [{{:keys [id]} :path-params}]
      (let [id       (edn/read-string id)
            meta-id  @(re-frame/subscribe [:extract-metadata id])
            comments (when meta-id
                       @(re-frame/subscribe [::comments meta-id]))]
        (when comments
          (reset! content comments))
        [comment-tree id @content]))))

(def routes
  [["/:id/comments"
    {:name :extract/comments
     :parameters {:path {:id any?}}
     :component  comments-page
     :controllers core/extract-controllers}]])
