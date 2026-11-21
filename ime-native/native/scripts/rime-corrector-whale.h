//
// Copyright RIME Developers
// Distributed under the BSD License
//
// Created by nameoverflow on 2018/11/14.
//
// WhaleIME: 上游的 Corrections 以「音节」为键，一个音节在同一处只能留一条解释。
// 但同一个音节在同一个起点上可能被多条编辑路径命中，**跨度不同、代价不同**：
//   hoafe 里的 hao：`ho` 后插一个 a（错 1 键、吃掉 2 字节），
//                   或 `hoa` 换位成 hao（错 2 键、吃掉 3 字节）。
// 只按音节去重会留下前者、丢掉后者，于是「hao+de」这条需要 hao 覆盖 3 字节的
// 路径永远组不出来 —— 好的（hao'de）在 hoafe 的候选里完全消失，差的（cha'de，
// cha 正好有一条 3 字节 2 键的解释）独占首位。
// 也就是说：**多音节纠错不仅要求每个音节都能被纠正，还要求每个音节能以
// 「这次的跨度」被纠正**，按音节去重直接把后一半能力砍掉了。
// 这里把键改成「音节 + 跨度」，同一音节的不同跨度各自保留（每条跨度只留
// 距离最小的那条解释），由音节图决定这些不同长度的边怎么参与竞争。
//

#ifndef RIME_CORRECTOR_H_
#define RIME_CORRECTOR_H_

#include <rime/common.h>
#include <rime/component.h>
#include <rime/algo/algebra.h>
#include <rime/algo/spelling.h>
#include <rime/dict/prism.h>
#include <rime/dict/vocabulary.h>

namespace rime {
struct Ticket;

class SymDeleteCollector {
 public:
  explicit SymDeleteCollector(const Syllabary& syllabary)
      : syllabary_(syllabary) {}

  Script Collect(size_t edit_distance);

 private:
  const Syllabary& syllabary_;
};

namespace corrector {
using Distance = size_t;
struct Correction {
  size_t distance;
  SyllableId syllable;
  size_t length;
  // WhaleIME: 该音节的拼写长度（字母数）。与 length（吃掉多少按键）的差值
  // 就是「补了几个字母 / 多打了几个字母」：差 0（换位、错键）应当排在
  // 「删掉两个键才凑出来的音」前面，见 syllabifier 里限流的取优口径。
  size_t spelling_length = 0;
};
// WhaleIME: 键 = 音节 + 消耗跨度（见文件头）。跨度不会超过输入长度，这里按
// 「高 20 位放音节、低 20 位放跨度」拼成 uint64_t，取值与平台无关。
class Corrections : public hash_map<uint64_t, Correction> {
 public:
  static uint64_t Key(SyllableId syllable, size_t length) {
    return ((uint64_t)syllable << 20) | (uint64_t)(length & 0xFFFFF);
  }
  /// Update for better correction
  /// \param syllable
  /// \param correction
  inline void Alter(SyllableId syllable, Correction correction) {
    const uint64_t key = Key(syllable, correction.length);
    auto it = find(key);
    if (it == end() || correction.distance < it->second.distance) {
      (*this)[key] = correction;
    }
  };
};
}  // namespace corrector

/**
 * The unify interface of correctors
 */
class Corrector : public Class<Corrector, const Ticket&> {
 public:
  virtual ~Corrector() = default;
  RIME_DLL virtual void ToleranceSearch(const Prism& prism,
                                        const string& key,
                                        corrector::Corrections* results,
                                        size_t tolerance) = 0;
};

class CorrectorComponent : public Corrector::Component {
 public:
  CorrectorComponent();
  ~CorrectorComponent() override = default;
  Corrector* Create(const Ticket& ticket) noexcept override;

 private:
  template <class... Cs>
  static Corrector* Combine(Cs... args);

  the<ResourceResolver> resolver_;

  class Unified : public Corrector {
   public:
    Unified() = default;
    RIME_DLL void ToleranceSearch(const Prism& prism,
                                  const string& key,
                                  corrector::Corrections* results,
                                  size_t tolerance) override;
    template <class... Cs>
    void Add(Cs... args) {
      contents = {args...};
    }

   private:
    vector<of<Corrector>> contents = {};
  };
};

class EditDistanceCorrector : public Corrector, public Prism {
 public:
  ~EditDistanceCorrector() override = default;
  RIME_DLL explicit EditDistanceCorrector(const path& file_path);

  RIME_DLL bool Build(const Syllabary& syllabary,
                      const Script* script = nullptr,
                      uint32_t dict_file_checksum = 0,
                      uint32_t schema_file_checksum = 0);

  RIME_DLL void ToleranceSearch(const Prism& prism,
                                const string& key,
                                corrector::Corrections* results,
                                size_t tolerance) override;
  corrector::Distance LevenshteinDistance(const std::string& s1,
                                          const std::string& s2);
  corrector::Distance RestrictedDistance(const std::string& s1,
                                         const std::string& s2,
                                         corrector::Distance threshold);
};

class RIME_DLL NearSearchCorrector : public Corrector {
 public:
  NearSearchCorrector() = default;
  ~NearSearchCorrector() override = default;
  void ToleranceSearch(const Prism& prism,
                       const string& key,
                       corrector::Corrections* results,
                       size_t tolerance) override;
};

template <class... Cs>
Corrector* CorrectorComponent::Combine(Cs... args) {
  auto u = new Unified();
  u->Add(args...);
  return u;
}

}  // namespace rime

#endif  // RIME_CORRECTOR_H_
