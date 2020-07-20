(ns openmind.data-caching)

(def recently-added
  "This cache creates read what you've written behaviour for a fundamentally
  fire and forget datastore. This will break down if we move this out to a
  separate process. We'll need to replace it with reddis, or maybe some sort of
  log processing."
  (atom {}))
