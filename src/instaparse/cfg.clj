(ns instaparse.cfg
  (:use instaparse.combinators)
  (:use [instaparse.reduction :only [apply-standard-reductions]])
  (:use [instaparse.gll :only [parse]])
  (:require [clojure.string :as str])
  (:require clojure.edn)
  (:use clojure.pprint clojure.repl))

;(def single-quoted-string #"'(?:[^\\']|\\.)*'")
;(def single-quoted-regexp #"#'(?:[^\\']|\\.)*'")
;(def double-quoted-string #"\"(?:[^\\\"]|\\.)*\"")
;(def double-quoted-regexp #"#\"(?:[^\\\"]|\\.)*\"")
; New improved
(def single-quoted-string #"'(?:[^']|(?<=\\)')*'")
(def single-quoted-regexp #"#'(?:[^']|(?<=\\)')*'")
(def double-quoted-string #"\"(?:[^\"]|(?<=\\)\")*\"")
(def double-quoted-regexp #"#\"(?:[^\"]|(?<=\\)\")*\"")


(def opt-whitespace (hide (nt :opt-whitespace)))

(def cfg 
  (apply-standard-reductions 
    :hiccup    ; use the hiccup output format 
    {:rules (hide-tag (cat opt-whitespace
                           (plus (nt :rule))))
     :whitespace (regexp "[,\\s]+")
     :opt-whitespace (regexp "[,\\s]*")
     :rule-separator (alt (string ":")
                          (string ":=")
                          (string "::=")
                          (string "="))
     :rule (cat (alt (nt :nt)
                     (nt :hide-nt))
                opt-whitespace
                (hide (nt :rule-separator))
                opt-whitespace
                (nt :alt-or-ord)
                (hide (alt (nt :opt-whitespace)
                           (regexp "\\s*[.;]\\s*"))))          
     :nt (cat
           (neg (nt :epsilon))
           (regexp "[^, \\r\\t\\n<>(){}\\[\\]+*?:=|'\"#&!;./]+"))
          :hide-nt (cat (hide (string "<"))
                        opt-whitespace
                        (nt :nt)
                        opt-whitespace
                        (hide (string ">")))
          :alt-or-ord (hide-tag (alt (nt :alt) (nt :ord)))
          :alt (cat (nt :cat)                           
                    (star
                      (cat
                        opt-whitespace
                        (hide (string "|"))
                        opt-whitespace
                        (nt :cat))))
          :ord (cat (nt :cat)
                    (plus
                      (cat
                        opt-whitespace
                        (hide (string "/"))
                        opt-whitespace
                        (nt :cat))))
          :paren (cat (hide (string "("))
                      opt-whitespace
                      (nt :alt-or-ord)
                      opt-whitespace
                      (hide (string ")")))
          :hide (cat (hide (string "<"))
                     opt-whitespace	
                     (nt :alt-or-ord)
                     opt-whitespace
                     (hide (string ">")))
          :cat (plus (cat
                       opt-whitespace
                       (alt (nt :factor) (nt :look) (nt :neg))
                       opt-whitespace))
          :string (alt
                    (regexp single-quoted-string)
                    (regexp double-quoted-string))
          :regexp (alt
                    (regexp single-quoted-regexp)
                    (regexp double-quoted-regexp))
          :opt (alt
                 (cat (hide (string "["))
                      opt-whitespace
                      (nt :alt-or-ord)
                      opt-whitespace
                      (hide (string "]")))
                 (cat (nt :factor)
                      opt-whitespace
                      (hide (string "?"))))
          :star (alt
                  (cat (hide (string "{"))
                       opt-whitespace
                       (nt :alt-or-ord)
                       opt-whitespace
                       (hide (string "}")))
                  (cat (nt :factor)
                       opt-whitespace
                       (hide (string "*"))))
          :plus (cat (nt :factor)
                     opt-whitespace
                     (hide (string "+")))
          :look (cat (hide (string "&"))
                     opt-whitespace
                     (nt :factor))
          :neg (cat (hide (string "!"))
                    opt-whitespace
                    (nt :factor))
          :epsilon (alt (string "Epsilon")
                        (string "epsilon")
                        (string "EPSILON")
                        (string "eps")
                        (string "\u03b5"))
          :factor (hide-tag (alt (nt :nt)
                                 (nt :string)
                                 (nt :regexp)
                                 (nt :opt)     
                                 (nt :star)
                                 (nt :plus)
                                 (nt :paren)
                                 (nt :hide)
                                 (nt :epsilon)))}))
  
(def tag first)
(def contents next)
(def content fnext)

(defn safe-read-string [s]
  (binding [*read-eval* false]
    (read-string s)))

(defn process-string
  "Converts single quoted string to double-quoted"
  [s]
  ;(prn s)
  (let [stripped
        (subs s 1 (dec (count s)))
        remove-escaped-single-quotes
        (str/replace stripped "\\'" "'")
        final-string
        (safe-read-string (str \" remove-escaped-single-quotes \"))]
        
    ;(prn final-string)
    final-string))

(defn regexp-replace
  "Replaces whitespace characters with escape sequences for better printing" 
  [s]
  (case s
    "\n" "\\n"
    "\b" "\\b"
    "\f" "\\f"
    "\r" "\\r"
    "\t" "\\t"
    :else s))

(defn process-regexp
  "Converts single quoted regexp to double-quoted"
  [s]
  ;(println (with-out-str (pr s)))
  (let [stripped
        (subs s 2 (dec (count s)))
        remove-escaped-single-quotes
        (str/replace stripped "\\'" "'")
        add-backslashes
        (str/replace remove-escaped-single-quotes 
                     #"[\s]" regexp-replace)        
        final-string
        (safe-read-string (str "#\"" add-backslashes \"))]
        
    ;(println (with-out-str (pr final-string)))
    final-string))


(defn build-rule [tree]
  ;(println tree)
  (case (tag tree)
    :rule (let [[nt alt-or-ord] (contents tree)]
            (if (= (tag nt) :hide-nt)
              [(keyword (content (content nt)))
               (hide-tag (build-rule alt-or-ord))]
              [(keyword (content nt))
               (build-rule alt-or-ord)]))
    :nt (nt (keyword (content tree)))
    :alt (apply alt (map build-rule (contents tree)))
    :ord (apply ord (map build-rule (contents tree)))
    :paren (recur (content tree))
    :hide (hide (nt (content tree)))
    :cat (apply cat (map build-rule (contents tree)))
    :string (string (process-string (content tree)))
    :regexp (regexp (process-regexp (content tree)))
    :opt (opt (build-rule (content tree)))
    :star (star (build-rule (content tree)))
    :plus (plus (build-rule (content tree)))
    :look (look (build-rule (content tree)))
    :neg (neg (build-rule (content tree)))
    :epsilon Epsilon))

(defn seq-nt
  "Returns a sequence of all non-terminals in a parser built from combinators."
  [parser]
  (case (:tag parser)
    :nt [(:keyword parser)]
    (:string :regexp :epsilon) []
    (:opt :plus :star :look :neg) (recur (:parser parser))
    (:alt :cat) (mapcat seq-nt (:parsers parser))
    :ord (mapcat seq-nt (juxt [:parser1 :parser2] parser))))
    
(defn check-grammar [grammar-map]
  (let [valid-nts (set (keys grammar-map))]
    (doseq [nt (distinct (mapcat seq-nt (vals grammar-map)))]
      (when-not (valid-nts nt)
        (throw (RuntimeException. (format "%s occurs on the right-hand side of your grammar, but not on the left"
                                          (subs (str nt) 1)))))))
  grammar-map)
          
(defn build-parser [spec output-format]
  (let [rules (parse cfg :rules spec)]
    (if (instance? instaparse.gll.Failure rules)
      (throw (RuntimeException. (str "Error parsing grammar specification:\n"
                                    (with-out-str (println rules)))))
      (let [productions (map build-rule rules)
            start-production (first (first productions))] 
        {:grammar (check-grammar (apply-standard-reductions output-format (into {} productions)))
         :start-production start-production}))))
  
(defn build-parser-from-combinators [grammar-map output-format start-production]
  (if (nil? start-production)
    (throw (IllegalArgumentException. 
             "When you build a parser from a map of parser combinators, you must provide a start production using the :start keyword argument."))
    {:grammar (check-grammar (apply-standard-reductions output-format grammar-map))
     :start-production start-production}))