(ns openmind.components.forms
  (:require [re-frame.core :as re-frame]
            [openmind.components.common :as common]))

;; HACK: This breaks up the editor ns, but these components are still
;; coupled to it. They won't be reusable until where they store their
;; state becomes configurable.
;;
;; TODO: replace all events in the editor ns with config options.

(defn pass-edit [id ks & [sub-key]]
  (fn [ev]
    (let [v (-> ev .-target .-value)
          v' (if sub-key {sub-key v} v)]
      (re-frame/dispatch [:openmind.components.extract.editor/form-edit
                          id ks v']))))

(defn date-string
  "Given a javascript Date, return a string which [:input {:type :date}] will
  understand.
  This is seriously messed up. Who the hell counts months starting at zero? Days
  are counted from one...
  "
  [d]
  (if (inst? d)
    (let [month (inc (.getMonth d))
          day (.getDate d)]
      (str (.getFullYear d) "-"
           (when (< month 10) "0")
           month
           "-"
           (when (< day 10) "0")
           day))
    ""))

(defn update-date [data-key key]
  (fn [ev]
    (let [s (-> ev .-target .-value)]
      ;; When the widget is cleared, the browser sends an empty string.
      (if (seq s)
        (let [date (js/Date. (str s " 00:00"))]
          (re-frame/dispatch [:openmind.components.extract.editor/form-edit
                              data-key key date]))
        (re-frame/dispatch [:openmind.components.extract.editor/clear-form-element
                            data-key key])))))

(defn date [{:keys [content errors key data-key]}]
  [:div.full-width
   [:input {:type :date
            :class (when errors "form-error")
            :on-change (juxt (update-date data-key key)
                             #(when errors
                                (re-frame/dispatch
                                 [:openmind.components.extract.editor/revalidate
                                  data-key])))
            :value (date-string content)}]
   (when errors
       [common/error errors])])

(defn text
  [{:keys [label key placeholder errors content data-key on-change on-blur]
    :as   opts}]
  (let [ks (if (vector? key) key [key])]
    [:div.full-width
     [:input.full-width-textarea
      (merge {:id        (apply str ks)
              :type      :text
              :on-blur   #(when on-blur (on-blur opts))
              :on-change (juxt (pass-edit data-key ks)
                               #(when on-change
                                  (on-change (-> % .-target .-value)))
                               #(when errors
                                  (re-frame/dispatch
                                   [:openmind.components.extract.editor/revalidate
                                    data-key])))}
             (cond
               (seq content) {:value content}
               placeholder   {:value       nil
                              :placeholder placeholder})
             (when errors
               {:class "form-error"}))]
     (when errors
       [common/error errors])]))

(defn text-input-list
  [{:keys [key placeholder spec errors content data-key sub-key]}]
  [:div
   [:div.flex
    (into [:div.flex.flex-wrap
           {:class (when errors "form-error")}]
          (map-indexed
           (fn [i c]
             (let [err (get errors i)]
               [:div
                {:style {:padding-right "0.2rem"}}
                [:input.full-width-textarea
                 (merge {:type      :text
                         :on-change (pass-edit data-key (conj key i) sub-key)}
                        (if (seq c)
                          {:value (if sub-key (get c sub-key) c)}
                          {:value       nil
                           :placeholder placeholder}))]])))
          content)
    [:a.plh.ptp {:on-click (fn [_]
                             (if (nil? content)
                               (re-frame/dispatch
                                [:openmind.components.extract.editor/form-edit
                                 data-key key [""]])
                               (re-frame/dispatch
                                [:openmind.components.extract.editor/form-edit
                                 data-key (conj key (count content)) ""])))}
     "[+]"]]
   (when errors
     [common/error errors])])

(defn textarea
  [{:keys [label key required? placeholder spec errors content rows
           data-key on-change on-blur] :as opts}]
  (let [ks (if (vector? key) key [key])]
    [:div
     [:textarea.full-width-textarea
      (merge {:id        (str key)
              :rows      (or rows 2)
              :style     {:resize :vertical}
              :type      :text
              :on-blur   #(when on-blur (on-blur opts))
              :on-change (juxt (pass-edit data-key ks)
                               #(when on-change
                                  (on-change (-> % .-target .-value)))
                               #(when errors
                                  (re-frame/dispatch
                                   [:openmind.components.extract.editor/revalidate
                                    data-key])))}
             (cond
               (seq content) {:value content}
               placeholder   {:value       nil
                              :placeholder placeholder})
             (when errors
               {:class "form-error"}))]
     (when errors
       [common/error errors])]))

(defn textarea-list
  [{:keys [key placeholder spec errors content data-key] :as opts}]
  (println "!!!"  key data-key content)
  [:div
   (into [:div]
         (map-indexed
          (fn [i c]
            (let [err (get errors i)]
              [textarea (assoc opts
                               :errors err
                               :content c
                               :key [key i])]))
              content))
   [:a.bottom-right {:on-click
                     (fn [_]
                       (re-frame/dispatch
                        [:openmind.components.extract.editor/form-edit
                         data-key [key (count content)] ""]))}
    "[+]"]])
