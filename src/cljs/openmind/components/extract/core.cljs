(ns openmind.components.extract.core
  (:require [re-frame.core :as re-frame]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Public Utilities
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn add-extract [db extract]
  (update-in db [::extracts (:id extract)]
             assoc
             :content extract
             :fetched (js/Date.)))

(defn get-extract [db id]
  (get-in db [::extracts id]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Subs
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(re-frame/reg-sub
 :extract
 (fn [db [_ k]]
   (get (::extracts db) k)))

(re-frame/reg-sub
 :extract/content
 (fn [[_ dk] _]
   (re-frame/subscribe [:extract dk]))
 (fn [extract e]
   (:content extract)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Events
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def blank-new-extract
  {:selection []
   :content   {:tags      #{}
               :comments  [""]
               :related   [""]
               :contrast  [""]
               :confirmed [""]}
   :errors    nil})

(re-frame/reg-event-db
 :extract/clear
 (fn [db [_ id]]
   (update db ::extracts dissoc id)))

(re-frame/reg-event-fx
 :extract/mark
 (fn [{:keys [db]} [_ id]]
   (if (= :openmind.components.extract.editor/new id)
     {:db (update-in db [::extracts id] #(if (nil? %) blank-new-extract %))}
     (if-not (contains? (::extracts db) id)
       ;; If we don't have it, get it
       {:dispatch [:openmind.events/try-send [:openmind/fetch-extract id]]}
       ;; If we still have it, make sure we don't drop it
       {:db (update-in db [::extracts id]
                       assoc
                       :last-access (js/Date.)
                       :gc-ready? false)}))))

(re-frame/reg-event-fx
 :extract/unmark
 (fn [{:keys [db]} [_ id]]
   ;; TODO: clean up the app state. I'm pretty confident that this won't be a
   ;; big deal unless you browse for a long while and get lots of images in your
   ;; app state. When the images get moved to S3 and the broser cache is used to
   ;; handle them, then I'm not sure cleaning up the app state will be very
   ;; important at all for most users.
   ;; TODO: Keep the server posted about what you have and don't so as to
   ;; minimise extra traffic and have changes pushed automatically.
   (update-in db [::extracts id]
              ;; Mark extract as inessential
              assoc :gc-ready? true)))

(re-frame/reg-event-db
 :openmind/fetch-extract-response
 (fn [db [_ extract]]
   (add-extract db extract)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Routing
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn mark-extract
  "Marks the extract as 'in use'. This means that it's data must be fetched if
  not already present, and kept ready."
  [{{id :id} :path}]
  (re-frame/dispatch [:extract/mark id]))

(defn unmark-extract
  "Marks the extract as no longer 'in use'. The data may not be deleted
  immediately, but if space is needed it will not be considered essential to
  keep."
  [{{id :id} :path}]
  (re-frame/dispatch [:extract/unmark id]))

(def extract-controllers
  "All extract viewing and editing flows use the same set of controllers."
  [{:parameters {:path [:id]}
    :start      mark-extract
    :stop       unmark-extract}])
