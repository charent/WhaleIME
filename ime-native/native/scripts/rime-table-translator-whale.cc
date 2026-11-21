//
// Copyright RIME Developers
// Distributed under the BSD License
//
// 2011-07-10 GONG Chen <chen.sst@gmail.com>
//
#include <boost/algorithm/string.hpp>
#include <boost/range/adaptor/reversed.hpp>
#include <algorithm>
#include <cmath>
#include <utf8.h>
#include <rime/candidate.h>
#include <rime/common.h>
#include <rime/composition.h>
#include <rime/config.h>
#include <rime/context.h>
#include <rime/engine.h>
#include <rime/schema.h>
#include <rime/translation.h>
#include <rime/dict/dictionary.h>
#include <rime/dict/user_dictionary.h>
#include <rime/gear/charset_filter.h>
#include <rime/gear/poet.h>
#include <rime/gear/table_translator.h>
#include <rime/gear/translator_commons.h>
#include <rime/gear/unity_table_encoder.h>

namespace rime {

static const char* kUnitySymbol = " \xe2\x98\xaf ";

// WhaleIME: 「整串原样命中」词条的固定 quality 档位（见 TableTranslation::Peek）
static const double kExactWholeWordQuality = 1.95;

// WhaleIME 2026-11-14: 大小写兜底的两路变体（全大写 / 全小写）。输入侧送进来的键位
// 永远是小写字母，而词表里既有全大写的缩写（GPU / USB / PDF），也可能有全小写之外的
// 形态；两路都试一遍才能让检索与输入大小写无关（原拼写命中时不会走到这里）。

// WhaleIME 2026-11-10: 词表候选在中文方案里的单键输入（`c` / `k` / `m`）只该解释成拼音前缀。
//
// 症状：按下第一个键，词表里编码刚好等于该字母的「整词精确命中」词条拿到 1.95 的固定档位
// （见 TableTranslation::Peek 的 kExactWholeWordQuality），压过拼音单字（从/才/吃/看/吗），
// 排在第一。这类词条不只是单字母（A / B / K / M / V），还有 c# / c++ 这种编码就是 `c`
// 的符号词条 —— 按 `c` 时前两条候选是 c# / c++ 正是这个原因。
// 它们不是大小写兜底造成的（english.table.bin 里这些词条的编码本身就是小写），
// 也不能靠调 quality 解决：已经打完的整词永远比得过「还没打完」的拼音补全。
//
// 口径：单键没有可「整词」解释的东西（多字母缩写 GPU / API 至少两键），所以单键输入只保留
// 词表一侧的**补全**（词条编码比输入长），整词命中的词条整条丢掉。
// 英文方案（单字母就是正经输入结果）与笔画方案（单键 = 一/丨/丿，也是整词命中）不动。
static bool is_whole_word_match(const Candidate* cand) {
  if (!cand)
    return false;
  // Peek 给没打完的词条 type = "completion"，整词命中是 "table" / "user_table"。
  // 按 type 判断而不是 dynamic_cast<Phrase>：CharsetFilterTranslation 会把候选包成
  // ShadowCandidate，那时它已经不是 Phrase 了。
  return cand->type() != "completion";
}

// 过滤掉「整词命中」的翻译层：外层看到的是内层跳掉这些候选之后的流。
class DropWholeWordTranslation : public Translation {
 public:
  explicit DropWholeWordTranslation(an<Translation> translation)
      : translation_(std::move(translation)) {
    Advance();
  }
  bool Next() { return Advance(); }
  an<Candidate> Peek() {
    return exhausted() || !translation_ ? nullptr : translation_->Peek();
  }

 protected:
  bool Advance() {
    while (translation_ && translation_->Next()) {
      auto cand = translation_->Peek();
      if (cand && !is_whole_word_match(cand.get()))
        return true;
    }
    set_exhausted(true);
    return false;
  }
  an<Translation> translation_;
};

// TableTranslation

TableTranslation::TableTranslation(TranslatorOptions* options,
                                   const Language* language,
                                   const string& input,
                                   size_t start,
                                   size_t end,
                                   const string& preedit,
                                   DictEntryIterator&& iter,
                                   UserDictEntryIterator&& uter)
    : options_(options),
      language_(language),
      input_(input),
      start_(start),
      end_(end),
      preedit_(preedit),
      iter_(std::move(iter)),
      uter_(std::move(uter)) {
  if (options_)
    options_->preedit_formatter().Apply(&preedit_);
  CheckEmpty();
}

bool TableTranslation::Next() {
  if (exhausted())
    return false;
  if (PreferUserPhrase()) {
    uter_.Next();
    if (uter_.exhausted())
      FetchMoreUserPhrases();
  } else {
    iter_.Next();
    if (iter_.exhausted())
      FetchMoreTableEntries();
  }
  return !CheckEmpty();
}

static bool is_constructed(const DictEntry* e) {
  return UnityTableEncoder::HasPrefix(e->custom_code);
}

an<Candidate> TableTranslation::Peek() {
  if (exhausted())
    return nullptr;
  bool is_user_phrase = PreferUserPhrase();
  auto e = PreferredEntry(is_user_phrase);
  string comment(is_constructed(e.get()) ? kUnitySymbol : e->comment);
  if (options_) {
    options_->comment_formatter().Apply(&comment);
  }
  bool incomplete = e->remaining_code_length != 0;
  auto type = incomplete       ? "completion"
              : is_user_phrase ? "user_table"
                               : "table";
  auto phrase = New<Phrase>(language_, type, start_, end_, e);
  if (phrase) {
    phrase->set_comment(comment);
    phrase->set_preedit(preedit_);
    double quality = std::exp(e->weight) + options_->initial_quality() +
                     (incomplete ? -1 : 0) + (is_user_phrase ? 0.5 : 0);
    // WhaleIME 2026-11-09: 「整串原样命中」的英文词条给一个固定档位，而不是继续按
    // exp(权重) + 1.1 去和拼音比：
    //   拼音候选 quality = 1.2 + 全拼覆盖率（0~1），纯拼音缩写/纠错被压到 1.05；
    //   英文词条 quality = 1.1 + exp(权重)（补全再 -1）。
    // 于是 gpu → 光谱（guang'pu 的「pu」是全拼，1.87）/ cpu → 菜谱 这类「缩写 + 部分
    // 全拼」的解释总能压过原样命中的缩写词，用户敲 gpu / usb / pdf 永远看不到结果。
    // 1.95 的取法：高于「缩写 + 部分全拼」（实测最高 1.87），低于「整串全拼」
    // （2.2，ni → 你 / name → 那么 仍排在英文之前）。同一编码的多条词条（id / ID /
    // I'd）quality 相同，稳定次序由词库权重决定，hence 仍按词频排。
    if (!incomplete && !is_user_phrase)
      quality = kExactWholeWordQuality;
    phrase->set_quality(quality);
  }
  return phrase;
}

bool TableTranslation::CheckEmpty() {
  bool is_empty = iter_.exhausted() && uter_.exhausted();
  set_exhausted(is_empty);
  return is_empty;
}

bool TableTranslation::PreferUserPhrase() {
  if (uter_.exhausted())
    return false;
  if (iter_.exhausted())
    return true;
  if (iter_.Peek()->remaining_code_length == 0 &&
      (uter_.Peek()->remaining_code_length != 0 ||
       is_constructed(uter_.Peek().get())))
    return false;
  else
    return true;
}

// LazyTableTranslation

class LazyTableTranslation : public TableTranslation {
 public:
  static const size_t kInitialSearchLimit = 10;
  static const size_t kExpandingFactor = 10;

  LazyTableTranslation(TableTranslator* translator,
                       const string& input,
                       size_t start,
                       size_t end,
                       const string& preedit,
                       bool enable_user_dict);
  bool FetchUserPhrases(TableTranslator* translator);
  virtual bool FetchMoreUserPhrases();
  virtual bool FetchMoreTableEntries();

 private:
  Dictionary* dict_;
  const hash_set<string>* blacklist_;
  UserDictionary* user_dict_;
  size_t limit_;
  size_t user_dict_limit_;
  string user_dict_key_;
  // WhaleIME 2026-11-09: 大小写兜底 —— 实际查询用的代码（可能已换成全大写 / 全小写）与
  // 是否试过兜底（见 FetchMoreTableEntries）
  string lookup_code_;
  bool tried_case_fallback_ = false;
};

LazyTableTranslation::LazyTableTranslation(TableTranslator* translator,
                                           const string& input,
                                           size_t start,
                                           size_t end,
                                           const string& preedit,
                                           bool enable_user_dict)
    : TableTranslation(translator,
                       translator->language(),
                       input,
                       start,
                       end,
                       preedit),
      dict_(translator->dict()),
      blacklist_(&translator->blacklist()),
      user_dict_(enable_user_dict ? translator->user_dict() : NULL),
      limit_(kInitialSearchLimit),
      user_dict_limit_(kInitialSearchLimit),
      lookup_code_(input) {
  FetchUserPhrases(translator) || FetchMoreUserPhrases();
  FetchMoreTableEntries();
  CheckEmpty();
}

bool LazyTableTranslation::FetchUserPhrases(TableTranslator* translator) {
  if (!user_dict_)
    return false;
  // fetch all exact match entries
  user_dict_->LookupWords(&uter_, input_, false, 0, &user_dict_key_);
  auto encoder = translator->encoder();
  if (encoder && encoder->loaded()) {
    encoder->LookupPhrases(&uter_, input_, false);
  }
  return !uter_.exhausted();
}

bool LazyTableTranslation::FetchMoreUserPhrases() {
  if (!user_dict_ || user_dict_limit_ == 0)
    return false;
  size_t count = user_dict_->LookupWords(&uter_, input_, true, user_dict_limit_,
                                         &user_dict_key_);
  if (count < user_dict_limit_) {
    DLOG(INFO) << "all user dict entries obtained.";
    user_dict_limit_ = 0;  // no more try
  } else {
    user_dict_limit_ *= kExpandingFactor;
  }
  return !uter_.exhausted();
}

bool LazyTableTranslation::FetchMoreTableEntries() {
  if (!dict_ || limit_ == 0)
    return false;
  size_t previous_entry_count = iter_.entry_count();
  DLOG(INFO) << "fetching more table entries: limit = " << limit_
             << ", count = " << previous_entry_count;
  DictEntryIterator more;
  bool finished =
      dict_->LookupWords(&more, lookup_code_, true, limit_, blacklist_) < limit_;
  // WhaleIME 2026-11-14: 大小写兜底扩成两路变体（全大写 / 全小写）。
  // 词表里的缩写词按大写存（GPU / USB / TV / PDF / HTTP …），用户敲的却一定是小写；
  // 反过来，物理键盘/大写输入也要能命中只按小写存的词条。原码**没有整词命中**时
  // （什么都没有，或只匹配到 tvOS 这种补全），依次改用全大写、全小写再查一遍并换用它；
  // 文本保持词库原样，客户端不再改写候选大小写（RimeEngine 中文形态直接用词库原文）。
  // 两路变体覆盖不到混合大小写词条（iPhone / tvOS），那类由 english 词典源在生成时
  // 为含大写的 code 派生一条小写码（docs/prototype/precompile-dict.ps1）解决。
  //
  // WhaleIME 2026-11-09: 词表里的缩写词是按大写存的（GPU / USB / TV / PDF / HTTP …，
  // 小写形态通常没有词条），用户敲的却一定是小写，于是这些简写在候选里完全看不见
  // （`english.table.bin` 里只有 "GPU"，没有 "gpu"）。
  //
  // WhaleIME 2026-11-10: **单键输入不走兜底**。词表里还有大量单字母大写词条
  // （A / B / C / M / I …），它们的编码就是那一个字母，于是敲中文时按下的第一个键
  // （`m` / `b` / `c`）会兜底查到大写字母词条 `M` / `B` / `C`，并以「整词命中」的身份
  // 排到 吗/们/被/从 前面 —— 用户要的是拼音前缀对应的单字，不是词表里的字母本身。
  // 真实缩写至少两个字母（GPU / API / TV），单键没有可缩写的东西，所以只在
  // 编码长度 > 1 时才兜底。
  if (!tried_case_fallback_ && lookup_code_.length() > 1) {
    bool has_complete = !more.exhausted() && more.Peek()->remaining_code_length == 0;
    if (!has_complete) {
      // 变体只试一轮：选中哪一路，后续增量补全都沿用它
      tried_case_fallback_ = true;
      string upper_code = lookup_code_;
      boost::to_upper(upper_code);
      string lower_code = lookup_code_;
      boost::to_lower(lower_code);
      const string* variants[] = {&upper_code, &lower_code};
      for (const string* variant : variants) {
        // 与原码相同的一路没有意义（全小写输入不必再查小写）
        if (*variant == lookup_code_)
          continue;
        DictEntryIterator case_more;
        bool case_finished = dict_->LookupWords(&case_more, *variant, true, limit_,
                                                blacklist_) < limit_;
        if (!case_more.exhausted()) {
          DLOG(INFO) << "case fallback: '" << lookup_code_ << "' -> '" << *variant
                     << "'";
          lookup_code_ = *variant;
          more = std::move(case_more);
          finished = case_finished;
          break;
        }
      }
    }
  }
  if (finished) {
    DLOG(INFO) << "all table entries obtained.";
    limit_ = 0;  // no more try
  } else {
    limit_ *= kExpandingFactor;
  }
  if (more.entry_count() > previous_entry_count) {
    more.Skip(previous_entry_count);
    iter_ = std::move(more);
  }
  return true;
}

// TableTranslator

TableTranslator::TableTranslator(const Ticket& ticket)
    : Translator(ticket), Memory(ticket), TranslatorOptions(ticket) {
  if (!engine_)
    return;
  if (Config* config = engine_->schema()->config()) {
    config->GetBool(name_space_ + "/enable_charset_filter",
                    &enable_charset_filter_);
    config->GetBool(name_space_ + "/enable_sentence", &enable_sentence_);
    config->GetBool(name_space_ + "/sentence_over_completion",
                    &sentence_over_completion_);
    config->GetBool(name_space_ + "/enable_encoder", &enable_encoder_);
    config->GetBool(name_space_ + "/encode_commit_history",
                    &encode_commit_history_);
    config->GetInt(name_space_ + "/max_phrase_length", &max_phrase_length_);
    config->GetInt(name_space_ + "/max_homographs", &max_homographs_);
    if (enable_sentence_ || sentence_over_completion_ ||
        contextual_suggestions_) {
      poet_.reset(new Poet(language(), config, Poet::LeftAssociateCompare));
    }
  }
  if (enable_encoder_ && user_dict_) {
    encoder_.reset(new UnityTableEncoder(user_dict_.get()));
    encoder_->Load(ticket);
  }
}

static bool starts_with_completion(an<Translation> translation) {
  if (!translation)
    return false;
  auto cand = translation->Peek();
  return cand && cand->type() == "completion";
}

an<Translation> TableTranslator::Query(const string& input,
                                       const Segment& segment) {
  if (!segment.HasAnyTagIn(tags_))
    return nullptr;
  DLOG(INFO) << "input = '" << input << "', [" << segment.start << ", "
             << segment.end << ")";

  FinishSession();

  bool enable_user_dict =
      user_dict_ && user_dict_->loaded() && !IsUserDictDisabledFor(input);

  const string& preedit(input);
  string code = input;
  boost::trim_right_if(code, boost::is_any_of(delimiters_));

  an<Translation> translation;
  if (enable_completion_) {
    translation = Cached<LazyTableTranslation>(this, code, segment.start,
                                               segment.start + input.length(),
                                               preedit, enable_user_dict);
  } else {
    DictEntryIterator iter;
    if (dict_ && dict_->loaded()) {
      dict_->LookupWords(&iter, code, false, 0, &blacklist());
    }
    UserDictEntryIterator uter;
    if (enable_user_dict) {
      user_dict_->LookupWords(&uter, code, false);
      if (encoder_ && encoder_->loaded()) {
        encoder_->LookupPhrases(&uter, code, false);
      }
    }
    if (!iter.exhausted() || !uter.exhausted())
      translation = Cached<TableTranslation>(
          this, language(), code, segment.start, segment.start + input.length(),
          preedit, std::move(iter), std::move(uter));
  }
  if (translation) {
    bool filter_by_charset =
        enable_charset_filter_ &&
        !engine_->context()->get_option("extended_charset");
    if (filter_by_charset) {
      translation = New<CharsetFilterTranslation>(translation);
    }
  }
  // WhaleIME 2026-11-10: 单键输入（`c` / `k` / `m`）在中文方案里只解释成拼音前缀，
  // 词表里的整词命中词条整条丢掉、只留补全（见 is_whole_word_match 的说明）。
  // 英文方案（单字母就是输入结果）与笔画方案（单键 = 一/丨/丿）保留原样。
  if (translation && input.length() <= 1 && engine_ && engine_->schema()) {
    const string& schema_id = engine_->schema()->schema_id();
    if (schema_id != "english" && schema_id != "stroke") {
      translation = New<DropWholeWordTranslation>(translation);
    }
  }
  if (translation && translation->exhausted()) {
    translation.reset();  // discard futile translation
  }
  if (enable_sentence_ && !translation) {
    translation = MakeSentence(input, segment.start,
                               /* include_prefix_phrases = */ true);
  } else if (sentence_over_completion_ && starts_with_completion(translation)) {
    if (auto sentence = MakeSentence(input, segment.start)) {
      translation = sentence + translation;
    }
  }
  if (translation && translation->exhausted()) {
    return nullptr;
  }
  translation = New<DistinctTranslation>(translation);
  if (contextual_suggestions_) {
    return poet_->ContextualWeighted(translation, input, segment.start, this);
  }
  return translation;
}

bool TableTranslator::Memorize(const CommitEntry& commit_entry) {
  if (!user_dict_)
    return false;
  for (const DictEntry* e : commit_entry.elements) {
    if (is_constructed(e)) {
      DictEntry blessed(*e);
      UnityTableEncoder::RemovePrefix(&blessed.custom_code);
      user_dict_->UpdateEntry(blessed, 1);
    } else {
      user_dict_->UpdateEntry(*e, 1);
    }
  }
  if (encoder_ && encoder_->loaded()) {
    if (commit_entry.elements.size() > 1) {
      encoder_->EncodePhrase(commit_entry.text, "1");
    }
    if (encode_commit_history_) {
      const auto& history(engine_->context()->commit_history());
      if (!history.empty()) {
        DLOG(INFO) << "history: " << history.repr();
        auto it = history.rbegin();
        if (it->type == "punct") {  // ending with punctuation
          ++it;
        }
        string phrase;
        for (; it != history.rend(); ++it) {
          if (it->type != "table" && it->type != "user_table" &&
              it->type != "sentence" && it->type != "uniquified")
            break;
          if (phrase.empty()) {
            phrase = it->text;  // last word
            continue;
          }
          phrase = it->text + phrase;  // prepend another word
          size_t phrase_length = utf8::unchecked::distance(
              phrase.c_str(), phrase.c_str() + phrase.length());
          if (static_cast<int>(phrase_length) > max_phrase_length_)
            break;
          DLOG(INFO) << "phrase: " << phrase;
          encoder_->EncodePhrase(phrase, "0");
        }
      }
    }
  }
  return true;
}

string TableTranslator::GetPrecedingText(size_t start) const {
  return !contextual_suggestions_ ? string()
         : start > 0 ? engine_->context()->composition().GetTextBefore(start)
                     : engine_->context()->commit_history().latest_text();
}

// SentenceSyllabifier

class SentenceSyllabifier : public PhraseSyllabifier {
 public:
  virtual Spans Syllabify(const Phrase* phrase);
};

Spans SentenceSyllabifier::Syllabify(const Phrase* phrase) {
  Spans result;
  if (auto sentence = dynamic_cast<const Sentence*>(phrase)) {
    size_t stop = sentence->start();
    result.AddVertex(stop);
    for (size_t len : sentence->word_lengths()) {
      stop += len;
      result.AddVertex(stop);
    }
  }
  return result;
}

// SentenceTranslation

class SentenceTranslation : public Translation {
 public:
  SentenceTranslation(TableTranslator* translator,
                      an<Sentence>&& sentence,
                      DictEntryCollector&& collector,
                      UserDictEntryCollector&& ucollector,
                      const string& input,
                      size_t start);
  virtual bool Next();
  virtual an<Candidate> Peek();

 protected:
  void PrepareSentence();
  bool CheckEmpty();
  bool PreferUserPhrase() const;

  TableTranslator* translator_;
  an<Sentence> sentence_;
  DictEntryCollector collector_;
  UserDictEntryCollector user_phrase_collector_;
  string input_;
  size_t start_;
};

SentenceTranslation::SentenceTranslation(TableTranslator* translator,
                                         an<Sentence>&& sentence,
                                         DictEntryCollector&& collector,
                                         UserDictEntryCollector&& ucollector,
                                         const string& input,
                                         size_t start)
    : translator_(translator),
      sentence_(std::move(sentence)),
      collector_(std::move(collector)),
      user_phrase_collector_(std::move(ucollector)),
      input_(input),
      start_(start) {
  PrepareSentence();
  CheckEmpty();
}

bool SentenceTranslation::Next() {
  if (sentence_) {
    sentence_.reset();
    return !CheckEmpty();
  }
  if (PreferUserPhrase()) {
    auto r = user_phrase_collector_.rbegin();
    if (!r->second.Next()) {
      user_phrase_collector_.erase(r->first);
    }
  } else {
    auto r = collector_.rbegin();
    if (!r->second.Next()) {
      collector_.erase(r->first);
    }
  }
  return !CheckEmpty();
}

an<Candidate> SentenceTranslation::Peek() {
  if (exhausted())
    return nullptr;
  if (sentence_) {
    return sentence_;
  }
  size_t code_length = 0;
  an<DictEntry> entry;
  bool is_user_phrase = PreferUserPhrase();
  if (is_user_phrase) {
    auto r = user_phrase_collector_.rbegin();
    code_length = r->first;
    entry = r->second.Peek();
  } else {
    auto r = collector_.rbegin();
    code_length = r->first;
    entry = r->second.Peek();
  }
  auto result = New<Phrase>(translator_ ? translator_->language() : NULL,
                            is_user_phrase ? "user_table" : "table", start_,
                            start_ + code_length, entry);
  if (translator_) {
    string preedit = input_.substr(0, code_length);
    translator_->preedit_formatter().Apply(&preedit);
    result->set_preedit(preedit);
  }
  return result;
}

void SentenceTranslation::PrepareSentence() {
  if (!sentence_)
    return;
  sentence_->Offset(start_);
  sentence_->set_comment(kUnitySymbol);
  sentence_->set_syllabifier(New<SentenceSyllabifier>());

  if (!translator_)
    return;
  string preedit = input_;
  const string& delimiters(translator_->delimiters());
  // split syllables
  size_t pos = 0;
  for (int len : sentence_->word_lengths()) {
    if (pos > 0 && delimiters.find(preedit[pos - 1]) == string::npos) {
      preedit.insert(pos, 1, ' ');
      ++pos;
    }
    pos += len;
  }
  translator_->preedit_formatter().Apply(&preedit);
  sentence_->set_preedit(preedit);
}

bool SentenceTranslation::CheckEmpty() {
  set_exhausted(!sentence_ && collector_.empty() &&
                user_phrase_collector_.empty());
  return exhausted();
}

bool SentenceTranslation::PreferUserPhrase() const {
  // compare code length
  int user_phrase_code_length = 0;
  int table_code_length = 0;
  if (!user_phrase_collector_.empty()) {
    user_phrase_code_length = user_phrase_collector_.rbegin()->first;
  }
  if (!collector_.empty()) {
    table_code_length = collector_.rbegin()->first;
  }
  if (user_phrase_code_length > 0 &&
      user_phrase_code_length >= table_code_length) {
    return true;
  }
  return false;
}

inline static size_t consume_trailing_delimiters(size_t pos,
                                                 const string& input,
                                                 const string& delimiters) {
  while (pos < input.length() && delimiters.find(input[pos]) != string::npos) {
    ++pos;
  }
  return pos;
}

template <class Iter>
inline static void collect_entries(DictEntryList& entries,
                                   Iter& iter,
                                   int max_entries) {
  if (entries.size() < max_entries && !iter.exhausted()) {
    entries.push_back(iter.Peek());
    // alters iter if collecting more than 1 entries
    while (entries.size() < max_entries && iter.Next()) {
      entries.push_back(iter.Peek());
    }
  }
}

an<Translation> TableTranslator::MakeSentence(const string& input,
                                              size_t start,
                                              bool include_prefix_phrases) {
  bool filter_by_charset = enable_charset_filter_ &&
                           !engine_->context()->get_option("extended_charset");
  DictEntryCollector collector;
  UserDictEntryCollector user_phrase_collector;
  WordGraph graph;
  hash_set<int> vertices = {0};
  for (size_t start_pos = 0; start_pos < input.length(); ++start_pos) {
    // find next reachable vertex in word graph
    if (vertices.find(start_pos) == vertices.end())
      continue;
    string active_input = input.substr(start_pos);
    string active_key = active_input + ' ';
    auto& same_start_pos = graph[start_pos];
    // lookup dictionaries
    if (user_dict_ && user_dict_->loaded()) {
      for (size_t len = 1; len <= active_input.length(); ++len) {
        size_t consumed_length =
            consume_trailing_delimiters(len, active_input, delimiters_);
        size_t end_pos = start_pos + consumed_length;
        auto& homographs = same_start_pos[end_pos];
        if (homographs.size() >= max_homographs_)
          continue;
        DLOG(INFO) << "active input: " << active_input << "[0, " << len << ")";
        UserDictEntryIterator uter;
        string resume_key;
        string key = active_input.substr(0, len);
        user_dict_->LookupWords(&uter, key, false, 0, &resume_key);
        if (filter_by_charset) {
          uter.AddFilter(CharsetFilter::FilterDictEntry);
        }
        if (!uter.exhausted()) {
          vertices.insert(end_pos);
          if (start_pos == 0 && max_homographs_ > 1) {
            UserDictEntryIterator uter_copy(uter);
            collect_entries(homographs, uter_copy, max_homographs_);
          } else {
            collect_entries(homographs, uter, max_homographs_);
          }
          if (include_prefix_phrases && start_pos == 0) {
            // also provide words for manual composition
            // uter must not be consumed
            user_phrase_collector[consumed_length] = std::move(uter);
            DLOG(INFO) << "user phrase[" << consumed_length << "] cached: "
                       << user_phrase_collector[consumed_length].cache_size();
          }
        }
        if (resume_key > active_key &&
            !boost::starts_with(resume_key, active_key))
          break;
      }
    }
    if (encoder_ && encoder_->loaded()) {
      UnityTableEncoder::AddPrefix(&active_key);
      for (size_t len = 1; len <= active_input.length(); ++len) {
        size_t consumed_length =
            consume_trailing_delimiters(len, active_input, delimiters_);
        size_t end_pos = start_pos + consumed_length;
        auto& homographs = same_start_pos[end_pos];
        if (!homographs.empty())
          continue;
        DLOG(INFO) << "active input: " << active_input << "[0, " << len << ")";
        UserDictEntryIterator uter;
        string resume_key;
        string key = active_input.substr(0, len);
        encoder_->LookupPhrases(&uter, key, false, 0, &resume_key);
        if (filter_by_charset) {
          uter.AddFilter(CharsetFilter::FilterDictEntry);
        }
        if (!uter.exhausted()) {
          vertices.insert(end_pos);
          if (start_pos == 0 && max_homographs_ > 1) {
            UserDictEntryIterator uter_copy(uter);
            collect_entries(homographs, uter_copy, max_homographs_);
          } else {
            collect_entries(homographs, uter, max_homographs_);
          }
          if (include_prefix_phrases && start_pos == 0) {
            // also provide words for manual composition
            // uter must not be consumed
            user_phrase_collector[consumed_length] = std::move(uter);
            DLOG(INFO) << "unity phrase[" << consumed_length << "] cached: "
                       << user_phrase_collector[consumed_length].cache_size();
          }
        }
        if (resume_key > active_key &&
            !boost::starts_with(resume_key, active_key))
          break;
      }
    }
    if (dict_ && dict_->loaded()) {
      vector<Prism::Match> matches;
      dict_->prism()->CommonPrefixSearch(input.substr(start_pos), &matches);
      if (matches.empty())
        continue;
      for (const auto& m : boost::adaptors::reverse(matches)) {
        if (m.length == 0)
          continue;
        size_t consumed_length =
            consume_trailing_delimiters(m.length, active_input, delimiters_);
        size_t end_pos = start_pos + consumed_length;
        auto& homographs = same_start_pos[end_pos];
        if (homographs.size() >= max_homographs_)
          continue;
        DictEntryIterator iter;
        dict_->LookupWords(&iter, active_input.substr(0, m.length), false, 0,
                           &blacklist());
        if (filter_by_charset) {
          iter.AddFilter(CharsetFilter::FilterDictEntry);
        }
        if (!iter.exhausted()) {
          vertices.insert(end_pos);
          if (start_pos == 0 && max_homographs_ - homographs.size() > 1) {
            DictEntryIterator iter_copy = iter;
            collect_entries(homographs, iter_copy, max_homographs_);
          } else {
            collect_entries(homographs, iter, max_homographs_);
          }
          if (include_prefix_phrases && start_pos == 0) {
            // also provide words for manual composition
            // iter must not be consumed
            collector[consumed_length] = std::move(iter);
            DLOG(INFO) << "table[" << consumed_length
                       << "]: " << collector[consumed_length].entry_count();
          }
        }
      }
    }
  }
  if (auto sentence =
          poet_->MakeSentence(graph, input.length(), GetPrecedingText(start))) {
    auto result = Cached<SentenceTranslation>(
        this, std::move(sentence), std::move(collector),
        std::move(user_phrase_collector), input, start);
    if (result && filter_by_charset) {
      return New<CharsetFilterTranslation>(result);
    }
    return result;
  }
  return nullptr;
}

}  // namespace rime
