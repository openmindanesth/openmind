(ns openmind.components.extract.core
  (:require [clojure.edn :as edn]
            [re-frame.core :as re-frame]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Public Utilities
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(re-frame/reg-event-fx
 ::add-extract
 (fn [{:keys [db]} [_ extract]]
   {:dispatch-n [[:openmind.events/s3-receive {:hash    (:hash extract)
                                               :content extract}]
                 [::fetch-sub-data extract]]}))

(re-frame/reg-event-fx
 ::fetch-sub-data
 (fn [{:keys [db]} [_ {:keys [figures]}]]
   {:dispatch-n (into [] (comp (remove #(nil? %))
                               (map #(.-hash-string %))
                               (map (fn [f] [:s3-get f])))
                      figures)}))

(defn get-extract [db id]
  (get-in db [::table id]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Subs
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(re-frame/reg-sub
 :extract
 (fn [db [_ k]]
   (get-extract db k)))

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
   (update db ::table dissoc id)))

(re-frame/reg-event-fx
 :extract/mark
 (fn [{:keys [db]} [_ id]]
   (if (= :openmind.components.extract.editor/new id)
     {:db (update-in db [::table id] #(if (nil? %) blank-new-extract %))}
     (let [id (edn/read-string id)]
       (if-not (contains? (::table db) id)
         ;; If we don't have it, get it
         {:dispatch [:s3-get id]}
         ;; If we still have it, make sure we don't drop it
         {:db (update-in db [::table id]
                         assoc
                         :last-access (js/Date.)
                         :gc-ready? false)})))))

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
   (update-in db [::table id]
              ;; Mark extract as inessential
              ;; TODO: mark references recursively
              assoc :gc-ready? true)))

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
