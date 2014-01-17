;;   Copyright (c) Rich Hickey and contributors. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns ^{:skip-wiki true}
  clojure.core.async.impl.protocols)


(def ^:const ^int MAX-QUEUE-SIZE 1024)

#_(defprotocol ReadPort
  (take! [port fn1-handler] "derefable val if taken, nil if take was enqueued"))

#_(defprotocol WritePort
  (put! [port val fn0-handler] "derefable nil if put, nil if put was enqueued. Must throw on nil val."))

#_(defprotocol Channel
  (close! [chan]))

(defprotocol Handler
  (active? [h] "returns true if has callback. Must work w/o lock")
  (lock-id [h] "a unique id for lock acquisition order, 0 if no lock")
  (commit [h] "commit to fulfilling its end of the transfer, returns cb. Must be called within lock"))

#_(defprotocol Buffer
  (full? [b])
  (remove! [b])
  (add! [b itm]))

#_(defprotocol Executor
  (exec [e runnable] "execute runnable asynchronously"))

;; Defines a buffer that will never block (return true to full?)
#_(defprotocol UnblockingBuffer)

(defprotocol Mult
  (tap* [m ch close?])
  (untap* [m ch])
  (untap-all* [m]))

(defprotocol Mix
  (admix* [m ch])
  (unmix* [m ch])
  (unmix-all* [m])
  (toggle* [m state-map])
  (solo-mode* [m mode]))

(defprotocol Pub
  (sub* [p v ch close?])
  (unsub* [p v ch])
  (unsub-all* [p] [p v]))

