(ns org-sync.org
  "generates org files based on specced data structures"
  (:require [clojure.pprint :refer [pprint]]
            [clojure.spec.alpha :as s]))

(s/def ::text     (s/tuple #{::text} string?))

(s/def ::file     (s/spec
                   (s/cat   :file #{::file}
                            :attrs (s/* (s/or :name (s/tuple #{::name} string?)
                                              :url  (s/tuple #{::url} string?)
                                              :mimetype (s/tuple #{::mimetype} string?)
                                              :text ::text)))))

(s/def ::section  (s/cat :section #{::section}
                         :attrs (s/coll-of (s/or :text ::text
                                                 :file ::file))))
(s/def ::stars    (s/tuple #{::stars}   int?))
(s/def ::title    (s/tuple #{::title}   string?))
(s/def ::tags     (s/tuple #{::tags}    (s/coll-of string?)))
(s/def ::headline (s/spec
                   (s/cat :headline #{::headline}
                          :attrs (s/* (s/or :stars   ::stars
                                            :title   ::title
                                            :tags    ::tags
                                            :section ::section)))))
(s/def ::document (s/spec (s/cat :document #{::document}
                                 :section (s/? ::section)
                                 :headlines (s/* ::headline))))

(defn render-org-model
  "Renders the `data` input as an org document. For example:
  [:document
    [:section \"Hi there\"]
    [:headline [:stars 3] [:priority \"\"] [:title \"\"] [:tags \"\"] [:section [...]] [:headlines [...]]]]"
  [[elem & rest :as foo]]

  (assert (keyword? elem))

  (case elem
    ::document (->> rest (map render-org-model) (clojure.string/join "\n"))

    ::section (->> (first rest)
                   (map render-org-model)
                   (clojure.string/join "\n\n"))
                   
    ::text (first rest)
    ::file (let [{:keys [::name ::url ::mimetype]} (into {} rest)]
             (str "#+ATTR_ORG: :width 300\n[[" url "][" name "]]"))
    
    ::headline (let [{:keys [::stars ::priority ::title ::tags ::section ::headlines]} (into {} rest)
                    stars-text (->> (repeat "*")
                                    (take stars)
                                    clojure.string/join)]
                (str stars-text " " title "\n" (render-org-model [::section section]) "\n"
                     (->> headlines (map render-org-model) (clojure.string/join "\n"))))))

(s/def ::doc-elem (s/spec (s/cat :tag keyword? :attr (s/* any?))))

;; TODO: this is a weak spec, it really ought to enforce the entire document spec, but that
;; requires a coherent grammar to be specified concisely. fix this?
(s/fdef render-org-model
        :args (s/cat :elem ::doc-elem)
        :ret string?)

