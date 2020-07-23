(ns openmind.spec.validation)

(defn describe-problem [{:keys [pred via path]}]
  ;; HACK: This is an horrid way to get human messages from specs. There's got
  ;; to be a better way...
  (cond
    (= pred 'cljs.core/not-empty)
    "field cannot be left blank"

    (and  (= pred '(cljs.core/fn [%] (cljs.core/< (cljs.core/count %) 500)))
          (= path [:text]))
    "extracts should be concise, less than 500 characters. use the comments if you need to make additional points."

    (and (= (first pred) 'cljs.core/<=) (= path [:authors]))
    "there must be at least one author"

    (let [[f s & _] (nth pred 2)]
      (and (= f 'cljs.spec.alpha/valid?)
           (= s :openmind.spec.shared/url-record)))
    "invalid url"))

(defn required? [{:keys [pred path]}]
  (and (= path [])
       (= 'cljs.core/fn (first pred))
       (= 'cljs.core/contains? (first (nth pred 2)))))

(defn missing-required [{:keys [pred]}]
  [(last (nth pred 2)) "field cannot be left blank"])

(defn mk
  "Hack to interpret spec's use of indicies in map specs."
  [path in]
  (if (= path in)
    path
    (butlast in)))

(defn interpret-explanation [{:keys [cljs.spec.alpha/problems]}]
  (when problems
    (let [missing (->> problems
                       (filter required?)
                       (map missing-required)
                       (into {}))]
      (reduce (fn [acc {:keys [path in pred] :as problem}]
                (assoc-in acc (mk path in) (describe-problem problem)))
              missing (remove required? problems)))))
