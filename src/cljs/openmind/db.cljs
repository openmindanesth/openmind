(ns openmind.db)

(def default-db
  {:domain           "anaesthesia"
   :tag-tree         nil
   :search           {:term    nil
                      :filters #{}}
   :route            :openmind.views/search
   :create           {:selection []
                      :tags      #{}}
   :results          []})
