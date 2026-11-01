(ns memo-router.whisper-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [memo-router.whisper :as whisper]))

(def audio-path "/memos/2026-10-31/11-23-32.m4a")

(defn- fake-whisper!
  "Writes an executable stand-in for mlx_whisper into dir and returns its path.
  It records its arguments (one per line) in args.txt next to itself, writes
  transcript to <output-dir>/<memo name>.txt, prints stderr, and exits with exit."
  [dir {:keys [transcript stderr exit] :or {transcript "" stderr "" exit 0}}]
  (let [bin (str (fs/path dir "fake-mlx-whisper"))]
    (spit bin (str "#!/bin/sh\n"
                   "printf '%s\\n' \"$@\" > \"$(dirname \"$0\")/args.txt\"\n"
                   "audio=\"$1\"\n"
                   "while [ $# -gt 0 ]; do\n"
                   "  [ \"$1\" = --output-dir ] && out=\"$2\"\n"
                   "  shift\n"
                   "done\n"
                   "base=$(basename \"$audio\" .m4a)\n"
                   "cat > \"$out/$base.txt\" <<'EOF'\n" transcript "\nEOF\n"
                   "printf '%s' '" stderr "' >&2\n"
                   "exit " exit "\n"))
    (fs/set-posix-file-permissions bin "rwx------")
    bin))

(defn- recorded-args [dir]
  (str/split-lines (slurp (str (fs/path dir "args.txt")))))

(defn- arg-after
  "The value that follows flag in args, e.g. (arg-after args \"--model\")."
  [args flag]
  (second (drop-while #(not= flag %) args)))

(deftest transcribe-test
  (testing "returns the transcript, trimmed"
    (fs/with-temp-dir [dir {}]
      (let [bin (fake-whisper! dir {:transcript "  Remind me to call Dana.  \n"})]
        (is (= "Remind me to call Dana."
               (whisper/transcribe {:bin bin :model "test-model"} audio-path))))))

  (testing "passes the audio path, model, and txt output to mlx_whisper"
    (fs/with-temp-dir [dir {}]
      (let [bin (fake-whisper! dir {:transcript "hello"})
            _ (whisper/transcribe {:bin bin :model "test-model"} audio-path)
            args (recorded-args dir)]
        (is (= audio-path (first args)))
        (is (= "test-model" (arg-after args "--model")))
        (is (= "txt" (arg-after args "--output-format"))))))

  (testing "deletes its temporary output folder afterwards"
    (fs/with-temp-dir [dir {}]
      (let [bin (fake-whisper! dir {:transcript "hello"})
            _ (whisper/transcribe {:bin bin :model "test-model"} audio-path)
            out-dir (arg-after (recorded-args dir) "--output-dir")]
        (is (some? out-dir))
        (is (not (fs/exists? out-dir))))))

  (testing "returns \"\" when nothing was recognised"
    (fs/with-temp-dir [dir {}]
      (let [bin (fake-whisper! dir {:transcript "   "})]
        (is (= "" (whisper/transcribe {:bin bin :model "test-model"} audio-path))))))

  (testing "throws ex-info with :exit, :err and :file when mlx_whisper fails"
    (fs/with-temp-dir [dir {}]
      (let [bin (fake-whisper! dir {:stderr "model not found" :exit 2})
            e (is (thrown? clojure.lang.ExceptionInfo
                           (whisper/transcribe {:bin bin :model "test-model"} audio-path)))]
        (is (= {:exit 2 :err "model not found" :file audio-path}
               (ex-data e)))))))
