(ns onyx.plugin.twitter
  (:require [clojure.core.async :refer [<!! >!! chan close! poll!]]
            [onyx.plugin.protocols.plugin :as p]
            [onyx.plugin.protocols.input :as i]
            [clojure.java.data :refer [from-java]])
  (:import [twitter4j Status StatusListener TwitterStream TwitterStreamFactory StatusJSONImpl FilterQuery]
           [twitter4j.conf Configuration ConfigurationBuilder]))

(defn config-with-password ^Configuration [consumer-key consumer-secret
                                           access-token access-secret]
  "Build a twitter4j configuration object with a username/password pair"
  (.build (doto  (ConfigurationBuilder.)
            (.setOAuthConsumerKey consumer-key)
            (.setOAuthConsumerSecret consumer-secret)
            (.setOAuthAccessToken access-token)
            (.setOAuthAccessTokenSecret access-secret))))

(defn status-listener [cb]
  "Implementation of twitter4j's StatusListener interface"
  (proxy [StatusListener] []
    (onStatus [^twitter4j.Status status]
      (cb status))
    (onException [^java.lang.Exception e] (.printStackTrace e))
    (onDeletionNotice [^twitter4j.StatusDeletionNotice statusDeletionNotice])
    (onScrubGeo [userId upToStatusId] ())
    (onTrackLimitationNotice [numberOfLimitedStatuses]
      (println numberOfLimitedStatuses))))

(defn get-twitter-stream ^TwitterStream [config]
  (let [factory (TwitterStreamFactory. ^Configuration config)]
    (.getInstance factory)))

(defn add-stream-callback! [stream cb track]
  (let [tc (chan 1000)]
    (.addListener stream (status-listener cb))
    (if (= 0 (count track))
      (.sample stream)
      (.filter stream (FilterQuery. 0 (long-array []) (into-array String track))))))

(defmacro safeget [f obj]
  `(try (~f ~obj) (catch NullPointerException e# nil)))

(defn tweetobj->map [^StatusJSONImpl tweet-obj]
  (try (from-java tweet-obj)
       (catch Exception e
         {:error e})))

(defrecord ConsumeTweets [event task-map twitter-feed-ch twitter-stream]
  p/Plugin
  (start [this event]
    (let [{:keys [twitter/consumer-key
                  twitter/consumer-secret
                  twitter/access-token
                  twitter/access-secret
                  twitter/keep-keys
                  twitter/track]} task-map 
          configuration (config-with-password consumer-key consumer-secret
                                              access-token access-secret)
          twitter-stream (get-twitter-stream configuration)
          twitter-feed-ch (chan 1000)]
      (assert consumer-key ":twitter/consumer-key not specified")
      (assert consumer-secret ":twitter/consumer-secret not specified")
      (assert access-token ":twitter/access-token not specified")
      (assert access-secret ":twitter/access-secret not specified")
      (add-stream-callback! twitter-stream (fn [m] (>!! twitter-feed-ch m)) track)
      (assoc this
             :twitter-stream twitter-stream
             :twitter-feed-ch twitter-feed-ch)))

  (stop [this event] 
    this)

  i/Input
  (checkpoint [this])

  (recover! [this replica-version checkpoint]
    this)

  (synced? [this epoch]
    true)

  (checkpointed! [this epoch])

  (poll! [this _]
    (let [keep-keys (get task-map :twitter/keep-keys)
          tweet (poll! twitter-feed-ch)]
      (when tweet
        (if (= :all keep-keys)
          (tweetobj->map tweet)
          (select-keys (tweetobj->map tweet) 
                       (or keep-keys [:id :text :lang]))))))

  (completed? [this]
    false))

(defn consume-tweets [{:keys [onyx.core/task-map] :as event}]
  (map->ConsumeTweets {:event event
                       :task-map task-map}))

(def twitter-reader-calls
  {})
