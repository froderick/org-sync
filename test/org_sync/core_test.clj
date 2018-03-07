(ns org-sync.core-test
  (:require [clojure.test :refer :all]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [clojure.spec.test.alpha :as stest]
            [org-sync.core :refer :all]
            [org-sync.org :refer :all]
            [org-sync.slack :refer :all]
            [clojure.pprint :refer [pprint]]
            [clojure.java.io]
            [clj-http.client :as client]
            [org-sync.slack :as slack]
            [org-sync.org :as org]
            [clojure.java.io :as io]
            [clj-slack.im :as im]
            [clj-slack.groups :as groups])
  (:refer-clojure :exclude [sync]))

; is this the best way to use instrumentation in tests?
(stest/instrument)

(def plain-message {::slack/message-type "message",
                    ::slack/message-user "U8KL0HD4Y",
                    ::slack/message-text "what is up",
                    ;:edited {:user "U8KL0HD4Y", :ts "1514565798.000000"},
                    ::slack/message-ts "1514565788.000191"})

(def file-message {::slack/message-file
                   {::slack/message-file-local-url "https://froderick-dev.slack.com/files/U8KL0HD4Y/F8LETJZUM/image_uploaded_from_ios.jpg",
                    ::slack/message-file-mimetype"image/jpeg"
                    ::slack/message-file-id "001"
                    ::slack/message-file-title "More"}
                   ::slack/message-type "message",
                   ::slack/message-ts "1514561276.000402",
                   ::slack/message-user "U8KL0HD4Y",
                   ::slack/message-text
                   "<@U8KL0HD4Y> uploaded a file: <https://froderick-dev.slack.com/files/U8KL0HD4Y/F8LETJZUM/image_uploaded_from_ios.jpg|More>"})

(deftest test-build-org-model-message
  (is (= (build-org-model-message plain-message)
         [[::org/text "what is up"]]))

  (is (= (build-org-model-message file-message)
         [[::org/text "<@U8KL0HD4Y> uploaded a file: <https://froderick-dev.slack.com/files/U8KL0HD4Y/F8LETJZUM/image_uploaded_from_ios.jpg|More>"]
          [::org/file
           [::org/name "More"]
           [::org/url "https://froderick-dev.slack.com/files/U8KL0HD4Y/F8LETJZUM/image_uploaded_from_ios.jpg"]
           [::org/mimetype "image/jpeg"]]])))

(deftest test-build-org-model
  (let [input-messages [plain-message file-message]
        output-model [::org/document
                      [::org/section
                       [[::org/text "Hi, this is an org document exported from a slack channel. Do not modify manually, as your changes may be overwritten."]]]
                      [::org/headline
                       [::org/stars 1]
                       [::org/title "Session 2017-12-29"]
                       [::org/tags ["slackcapture"]]
                       [::org/section
                        [[::org/text "<@U8KL0HD4Y> uploaded a file: <https://froderick-dev.slack.com/files/U8KL0HD4Y/F8LETJZUM/image_uploaded_from_ios.jpg|More>"]
                         [::org/file
                          [::org/name "More"]
                          [::org/url "https://froderick-dev.slack.com/files/U8KL0HD4Y/F8LETJZUM/image_uploaded_from_ios.jpg"]
                          [::org/mimetype "image/jpeg"]]
                         [::org/text "what is up"]]]]]]
    (is (= (build-org-model input-messages)  output-model))))

(deftest test-render-org-model
  (is (= (render-org-model
          [::org/document
           [::org/section
            [[::org/text
              "Hi, this is an org document exported from a slack channel. Do not modify manually, as your changes may be overwritten."]]]
           [::org/headline
            [::org/stars 1]
            [::org/title "Session 2017-12-29"]
            [::org/tags ["slackcapture"]]
            [::org/section
             [[::org/text
               "<@U8KL0HD4Y> uploaded a file: <https://froderick-dev.slack.com/files/U8KL0HD4Y/F8LETJZUM/image_uploaded_from_ios.jpg|More>"]
              [::org/file
               [::org/name "More"]
               [::org/url
                "https://froderick-dev.slack.com/files/U8KL0HD4Y/F8LETJZUM/image_uploaded_from_ios.jpg"]
               [::org/mimetype "image/jpeg"]]
              [::org/text "WUTO"]]]]])
"Hi, this is an org document exported from a slack channel. Do not modify manually, as your changes may be overwritten.
* Session 2017-12-29
<@U8KL0HD4Y> uploaded a file: <https://froderick-dev.slack.com/files/U8KL0HD4Y/F8LETJZUM/image_uploaded_from_ios.jpg|More>

#+ATTR_ORG: :width 300
[[https://froderick-dev.slack.com/files/U8KL0HD4Y/F8LETJZUM/image_uploaded_from_ios.jpg][More]]

WUTO
")))

