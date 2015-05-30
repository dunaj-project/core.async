;;   Copyright (c) Rich Hickey and contributors. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns ^{:skip-wiki true}
  clojure.core.async.impl.channels
  (:require [clojure.core.async.impl.protocols :as impl]
            [clojure.core.async.impl.dispatch :as dispatch]
            [clojure.core.async.impl.mutex :as mutex]
            [dunaj.coll :as dc]
            [dunaj.coll.helper :as dch]
            [dunaj.state :as ds]
            [dunaj.buffer :as db]
            [dunaj.state.basic :as dsb]
            [dunaj.concurrent.port :as dp])
  (:import [java.util LinkedList Queue Iterator]
           [java.util.concurrent.locks Lock]))

(set! *warn-on-reflection* true)

(defmacro assert-unlock [lock test msg]
  `(when-not ~test
     (.unlock ~lock)
     (throw (new AssertionError (str "Assert failed: " ~msg "\n" (pr-str '~test))))))

(defn box [val]
  (reify clojure.lang.IDeref
         (deref [_] val)))

(defprotocol MMC
  (cleanup [_])
  (abort [_]))

(deftype ManyToManyChannel [^LinkedList takes ^LinkedList puts ^:unsynchronized-mutable wrap buf-ref closed ^Lock mutex ^dunaj.coll.IReducing r]
  MMC
  (cleanup
   [_]
   (when-not (.isEmpty takes)
     (let [iter (.iterator takes)]
       (loop [taker (.next iter)]
         (when-not (impl/active? taker)
           (.remove iter))
         (when (.hasNext iter)
           (recur (.next iter))))))
   (when-not (.isEmpty puts)
     (let [iter (.iterator puts)]
       (loop [[putter] (.next iter)]
         (when-not (impl/active? putter)
           (.remove iter))
         (when (.hasNext iter)
           (recur (.next iter)))))))

  (abort
   [this]
   (let [iter (.iterator puts)]
     (when (.hasNext iter)
       (loop [^Lock putter (.next iter)]
         (.lock putter)
         (let [put-cb (and (impl/active? putter) (impl/commit putter))]
           (.unlock putter)
           (when put-cb
             (dispatch/run (fn [] (put-cb true))))
           (when (.hasNext iter)
             (recur (.next iter)))))))
   (.clear puts)
   (dp/-close! this))

  dp/ITargetPort
  (-put!
   [this val handler]
   (when (nil? val)
     (throw (IllegalArgumentException. "Can't put nil on channel")))
   (.lock mutex)
   (cleanup this)
   (if @closed
     (do (.unlock mutex)
         (box false))
     (let [^Lock handler handler
           buf (when buf-ref @buf-ref)]
       (if (and buf (not (dc/full? buf)) (not (.isEmpty takes)))
         (do
           (.lock handler)
           (let [put-cb (and (impl/active? handler) (impl/commit handler))]
             (.unlock handler)
             (if put-cb
               (let [nwrap (._step r wrap val)
                     done? (reduced? nwrap)
                     nwrap (dch/strip-reduced nwrap)]
                 (set! wrap nwrap)
                 (loop [buf @buf-ref]
                   (if (pos? (count buf))
                     (let [iter (.iterator takes)
                           take-cb (when (.hasNext iter)
                                     (loop [^Lock taker (.next iter)]
                                       (.lock taker)
                                       (let [ret (and (impl/active? taker) (impl/commit taker))]
                                         (.unlock taker)
                                         (if ret
                                           (do
                                             (.remove iter)
                                             ret)
                                           (when (.hasNext iter)
                                             (recur (.next iter)))))))]
                       (if take-cb
                         (let [val (dc/peek buf)
                               buf (dc/pop! buf)
                               _ (ds/reset! buf-ref buf)]
                           (dispatch/run (fn [] (take-cb val)))
                           (recur @buf-ref))
                         (do
                           (when done?
                             (abort this))
                           (.unlock mutex))))
                     (do
                       (when done?
                         (abort this))
                       (.unlock mutex))))
                 (box true))
               (do (.unlock mutex)
                   nil))))
         (let [iter (.iterator takes)
               [put-cb take-cb] (when (.hasNext iter)
                                  (loop [^Lock taker (.next iter)]
                                    (if (< (impl/lock-id handler) (impl/lock-id taker))
                                      (do (.lock handler) (.lock taker))
                                      (do (.lock taker) (.lock handler)))
                                    (let [ret (when (and (impl/active? handler) (impl/active? taker))
                                                [(impl/commit handler) (impl/commit taker)])]
                                      (.unlock handler)
                                      (.unlock taker)
                                      (if ret
                                        (do
                                          (.remove iter)
                                          ret)
                                        (when (.hasNext iter)
                                          (recur (.next iter)))))))]
           (if (and put-cb take-cb)
             (do
               (.unlock mutex)
               (dispatch/run (fn [] (take-cb val)))
               (box true))
             (if (and buf (not (dc/full? buf)))
               (do
                 (.lock handler)
                 (let [put-cb (and (impl/active? handler) (impl/commit handler))]
                   (.unlock handler)
                   (if put-cb
                     (let [nwrap (._step r wrap val)
                           done? (reduced? nwrap)]
                       (set! wrap (dch/strip-reduced nwrap))
                       (when done?
                         (abort this))
                       (.unlock mutex)
                       (box true))
                     (do (.unlock mutex)
                         nil))))
               (do
                 (when (and (impl/active? handler) (impl/blockable? handler))
                   (assert-unlock mutex
                                  (< (.size puts) impl/MAX-QUEUE-SIZE)
                                  (str "No more than " impl/MAX-QUEUE-SIZE
                                       " pending puts are allowed on a single channel."
                                       " Consider using a windowed buffer."))
                   (.add puts [handler val]))
                 (.unlock mutex)
                 nil))))))))
  
  dp/ISourcePort
  (-take!
   [this handler]
   (.lock mutex)
   (cleanup this)
   (let [^Lock handler handler
         commit-handler (fn []
                          (.lock handler)
                          (let [take-cb (and (impl/active? handler) (impl/commit handler))]
                            (.unlock handler)
                            take-cb))
         buf (when buf-ref @buf-ref)]
     (if (and buf (pos? (count buf)))
       (do
         (if-let [take-cb (commit-handler)]
           (let [val (dc/peek buf)
                 buf (dc/pop! buf)
                 _ (ds/reset! buf-ref buf)
                 iter (.iterator puts)
                 [done? cbs nwrap]
                 (when (.hasNext iter)
                   (loop [cbs []
                          [^Lock putter val] (.next iter)
                          xwrap wrap]
                     (.lock putter)
                     (let [cb (and (impl/active? putter) (impl/commit putter))]
                       (.unlock putter)
                       (.remove iter)
                       (let [cbs (if cb (conj cbs cb) cbs)
                             nwrap (if cb (._step r xwrap val) xwrap)
                             done? (reduced? nwrap)
                             nwrap (dch/strip-reduced nwrap)
                             buf @buf-ref]
                         (if (and (not done?) (not (dc/full? buf)) (.hasNext iter))
                           (recur cbs (.next iter) nwrap)
                           [done? cbs nwrap])))))]
             (when nwrap
               (set! wrap nwrap))
             (when done?
               (abort this))
             (.unlock mutex)
             (doseq [cb cbs]
               (dispatch/run #(cb true)))
             (box val))
           (do (.unlock mutex)
               nil)))
       (let [iter (.iterator puts)
             [take-cb put-cb val]
             (when (.hasNext iter)
               (loop [[^Lock putter val] (.next iter)]
                 (if (< (impl/lock-id handler) (impl/lock-id putter))
                   (do (.lock handler) (.lock putter))
                   (do (.lock putter) (.lock handler)))
                 (let [ret (when (and (impl/active? handler) (impl/active? putter))
                             [(impl/commit handler) (impl/commit putter) val])]
                   (.unlock handler)
                   (.unlock putter)
                   (if ret
                     (do
                       (.remove iter)
                       ret)
                     (when-not (impl/active? putter)
                       (.remove iter)
                       (when (.hasNext iter)
                         (recur (.next iter))))))))]
         (if (and put-cb take-cb)
           (do
             (.unlock mutex)
             (dispatch/run #(put-cb true))
             (box val))
           (if @closed
             (do
               (when wrap (._finish r wrap))
               (let [buf (when buf-ref @buf-ref)
                     has-val (and buf (pos? (count buf)))]
                 (set! wrap nil)
                 (if-let [take-cb (commit-handler)]
                   (let [val (when has-val (dc/peek buf))
                         buf (when has-val (dc/pop! buf))
                         _ (when has-val (ds/reset! buf-ref buf))]
                     (.unlock mutex)
                     (box val))
                   (do
                     (.unlock mutex)
                     nil))))
             (do
               (when (impl/blockable? handler)
                 (assert-unlock mutex
                                (< (.size takes) impl/MAX-QUEUE-SIZE)
                                (str "No more than " impl/MAX-QUEUE-SIZE
                                     " pending takes are allowed on a single channel."))
                 (.add takes handler))
               (.unlock mutex)
               nil)))))))

  ds/IOpenAware
  (-open? [_] (not @closed))
  dp/ICloseablePort
  (-close!
   [this]
   (.lock mutex)
   (cleanup this)
   (if @closed
     (do
       (.unlock mutex)
       nil)
     (do
       (reset! closed true)
       (when (.isEmpty puts)
         (do (when wrap (._finish r wrap))
             (set! wrap nil)))
       (let [iter (.iterator takes)]
         (when (.hasNext iter)
           (loop [^Lock taker (.next iter)]
             (.lock taker)
             (let [take-cb (and (impl/active? taker) (impl/commit taker))]
               (.unlock taker)
               (when take-cb
                 (let [buf (when buf-ref @buf-ref)
                       has-val (and buf (pos? (count buf)))
                       val (when has-val (dc/peek buf))
                       buf (when has-val (dc/pop! buf))
                       _ (when has-val (ds/reset! buf-ref buf))]
                   (dispatch/run (fn [] (take-cb val)))))
               (.remove iter)
               (when (.hasNext iter)
                 (recur (.next iter)))))))
       (let [buf (when buf-ref @buf-ref)]
         (when buf (db/-close! buf)))       
       (.unlock mutex)
       nil))))

(defn- ex-handler [ex]
  (-> (Thread/currentThread)
      .getUncaughtExceptionHandler
      (.uncaughtException (Thread/currentThread) ex))
  nil)

(defn radd!
  ([buf-ref] buf-ref)
  ([buf-ref itm]
     (assert (not (nil? itm)))
     (ds/reset! buf-ref (dc/conj! @buf-ref itm))
     buf-ref))

(defn- handle! [buf-ref exh t]
  (let [else ((or exh ex-handler) t)]
    (when-not (nil? else) (radd! buf-ref else))
    nil))

(deftype HandledReducing
  [^dunaj.coll.IReducing r exh]
  dc/IReducing
  (-finish [this wrap] (try (._finish r wrap)
                            (catch Throwable t
                              (let [ret (._unwrap r wrap)]
                                (do (handle! ret exh t)
                                    ret)))))
  (-wrap [this ret] (._wrap r ret))
  (-unwrap [this wrap] (._unwrap r wrap))
  (-step [this wrap val] (try (._step r wrap val)
                              (catch Throwable t
                                (do (handle! (._unwrap r wrap) exh t)
                                    wrap)))))

(defn chan
  ([buf] (chan buf nil))
  ([buf xform] (chan buf xform nil))
  ([buf xform exh]
     (let [dr (dc/reducing radd!)
           r (if xform (xform dr) dr)
           r (->HandledReducing r exh)
           buf-ref (dsb/unsynchronized-reference buf)]
       (ManyToManyChannel.
        (LinkedList.) (LinkedList.) (._wrap ^dunaj.coll.IReducing r buf-ref) buf-ref (atom false) (mutex/mutex) r))))
