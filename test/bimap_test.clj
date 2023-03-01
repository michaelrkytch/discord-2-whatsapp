(ns bimap-test
  (:require [bimap :as b]
            [clojure.test :refer :all]))

(deftest constructor
  (let [m {:a "a", "b" :b, 3 "3" :d {1 :one, 2 "two"}}]
    (is (= m (b/->BiMap m)) "Construct BiMap from map")
    (is (= m (b/->BiMap (seq m))) "Construct BiMap from pairs")
    (is (= {} (b/->BiMap [])) "Construct BiMap from empty list")
    (is (= {} (b/->BiMap nil)) "Construct BiMap from nil")
    ))

(deftest backward-map
  (let [m {:a "a", "b" :b, 3 "3" :d {1 :one, 2 "two"}}
        reversed {"a" :a, :b "b", "3" 3, {1 :one, 2 "two"} :d}
        bmap (b/->BiMap m)]
    (is (= reversed (b/backward-map bmap)))))

(deftest get-backward
  (let [m {:a "a", "b" :b, 3 "3" :d {1 :one, 2 "two"}}
        bm (b/->BiMap m)]
    (is (nil? (b/get-backward bm :xyz)))
    (is (= 3 (b/get-backward bm "3")))))

(deftest test-assoc
  (let [m1 {:a "a"}
        bm1 (b/->BiMap m1)
        bm2 (assoc bm1 :b "b")]
    (is (= (Class/forName "bimap.BiMap")  (type bm2)))
    (is (= m1 bm1) "no side-effect of assoc")
    (is (= {:a "a", :b "b"} bm2))
    (is (= {"a" :a, "b" :b} (b/backward-map bm2)) "assoc updates forward and backward views")))

(deftest test-dissoc
  (let [m1 {:a "a", :b "b"}
        bm1 (b/->BiMap m1)]
    (is (= (Class/forName "bimap.BiMap")  (type (dissoc bm1 :a))))
    (is (= {:b "b"} (dissoc bm1 :a)))
    (is (= {"a" :a} (b/backward-map (dissoc bm1 :b))))
    (is (= {} (dissoc bm1 :a :b) (b/backward-map (dissoc bm1 :a :b))))))

(deftest test-keys
  (let [m {:a "a", "b" :b, 3 "3" :d {1 :one, 2 "two"}}]
    (is (= (keys m) (keys (b/->BiMap m))))))

(deftest test-meta
  (let [m {:a "a", "b" :b, 3 "3" :d {1 :one, 2 "two"}}
        bm (b/->BiMap m)
        bm-meta (with-meta bm {:k "some meta"})]
    (is (= bm bm-meta))
    (is (= (b/backward-map bm) (b/backward-map bm-meta)))
    (is (= {:k "some meta"} (meta bm-meta)))
    (is (= nil (meta bm)))))