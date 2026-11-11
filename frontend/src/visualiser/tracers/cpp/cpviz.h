// The code visualiser's C++ runtime.
//
// The visualiser rewrites a student's program (see instrument.ts) so that every function opens
// a cpviz::Frame, every variable is registered with a cpviz::Var as it comes into scope, and
// cpviz::step(line) runs before each statement. At each step this header writes one JSON line
// to stderr, prefixed by \x1e, describing the whole stack and every heap object reachable from
// it, in the shape of the `Step` type in frontend/src/visualiser/trace.ts.
//
// Nothing here reads memory it cannot vouch for: a pointer is only followed when it points into
// a registered variable or into a block that `new` handed out and `delete` has not taken back,
// so a dangling or uninitialised pointer is drawn as one, never dereferenced.
#pragma once
#include <algorithm>
#include <array>
#include <bitset>
#include <charconv>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <forward_list>
#include <iostream>
#include <list>
#include <map>
#include <memory>
#include <new>
#include <optional>
#include <queue>
#include <set>
#include <stack>
#include <string>
#include <string_view>
#include <tuple>
#include <type_traits>
#include <unordered_map>
#include <unordered_set>
#include <utility>
#include <vector>

#ifndef CPVIZ_MAX_STEPS
#define CPVIZ_MAX_STEPS 3000
#endif

namespace cpviz {

constexpr std::size_t MAX_ITEMS = 120;
constexpr std::size_t MAX_HEAP = 300;
constexpr std::size_t MAX_STR = 300;

// ── memory the runtime itself uses, kept out of the student's heap ──────────

// True while the runtime is working, so its own allocations are not recorded as the program's.
inline bool& internal() { static bool v = false; return v; }

template <class T> struct Mallocator {
  using value_type = T;
  Mallocator() = default;
  template <class U> Mallocator(const Mallocator<U>&) {}
  T* allocate(std::size_t n) { return static_cast<T*>(std::malloc(n * sizeof(T))); }
  void deallocate(T* p, std::size_t) { std::free(p); }
  template <class U> bool operator==(const Mallocator<U>&) const { return true; }
  template <class U> bool operator!=(const Mallocator<U>&) const { return false; }
};

using AllocMap = std::map<std::uintptr_t, std::size_t, std::less<std::uintptr_t>,
                          Mallocator<std::pair<const std::uintptr_t, std::size_t>>>;
inline AllocMap& allocs() { static AllocMap* m = new (std::malloc(sizeof(AllocMap))) AllocMap(); return *m; }

inline void note_alloc(void* p, std::size_t n) { if (p && !internal()) allocs()[(std::uintptr_t)p] = n; }
inline void note_free(void* p) { if (p) allocs().erase((std::uintptr_t)p); }

// ── frames and variables ────────────────────────────────────────────────

struct W;
using DumpFn = void (*)(W&, const void*, std::size_t);

struct VarRec {
  const char* name;
  const void* addr;
  std::size_t size;   // bytes the variable occupies (0 for a reference)
  std::size_t count;  // elements, for an array registered by pointer and length
  DumpFn dump;
  bool ref;
};

struct FrameRec {
  int id;
  const char* name;
  int line;
  std::vector<VarRec, Mallocator<VarRec>> vars;
};

inline std::vector<FrameRec*, Mallocator<FrameRec*>>& stack() {
  static auto* s = new (std::malloc(sizeof(std::vector<FrameRec*, Mallocator<FrameRec*>>)))
      std::vector<FrameRec*, Mallocator<FrameRec*>>();
  return *s;
}
inline FrameRec& globals() {
  static FrameRec* g = new (std::malloc(sizeof(FrameRec))) FrameRec{0, "Global", 0, {}};
  return *g;
}
inline int& next_frame_id() { static int n = 1; return n; }
inline int& steps_taken() { static int n = 0; return n; }

// ── type names ──────────────────────────────────────────────────────────

template <class T> std::string_view raw_name() {
  std::string_view p = __PRETTY_FUNCTION__;
  auto s = p.find("T = ");
  if (s == std::string_view::npos) return "?";
  s += 4;
  auto e = p.find_first_of(";]", s);
  return p.substr(s, e - s);
}

inline std::string clean_name(std::string_view raw) {
  std::string s(raw);
  for (std::string_view junk : {"std::__1::", "std::__cxx11::", "std::", "struct ", "class "}) {
    for (std::size_t at; (at = s.find(junk)) != std::string::npos;) s.erase(at, junk.size());
  }
  // GCC spells out "long long int"; students write "long long".
  for (auto [from, to] : {std::pair<std::string_view, std::string_view>{"long long int", "long long"},
                          {"long int", "long"}, {"short int", "short"}}) {
    for (std::size_t at; (at = s.find(from)) != std::string::npos;) s.replace(at, from.size(), to);
  }
  // A local struct is named after its function ("main()::Node"); the student wrote "Node".
  if (s.find('<') == std::string::npos) {
    auto colons = s.rfind("::");
    if (colons != std::string::npos) s = s.substr(colons + 2);
  }
  return s;
}

template <class T> const std::string& type_name() {
  static const std::string* n = [] {
    bool was = internal(); internal() = true;
    auto* s = new std::string(clean_name(raw_name<T>()));
    internal() = was;
    return s;
  }();
  return *n;
}

// ── traits ──────────────────────────────────────────────────────────────

template <class T, template <class...> class Tmpl> struct is_spec : std::false_type {};
template <template <class...> class Tmpl, class... A> struct is_spec<Tmpl<A...>, Tmpl> : std::true_type {};
template <class T, template <class...> class Tmpl> constexpr bool is_spec_v = is_spec<T, Tmpl>::value;

template <class T> struct is_std_array : std::false_type {};
template <class T, std::size_t N> struct is_std_array<std::array<T, N>> : std::true_type {};
template <class T> struct is_bitset : std::false_type {};
template <std::size_t N> struct is_bitset<std::bitset<N>> : std::true_type {};

template <class T> constexpr const char* container_name() {
  if constexpr (is_spec_v<T, std::vector>) return "vector";
  else if constexpr (is_spec_v<T, std::deque>) return "deque";
  else if constexpr (is_spec_v<T, std::list>) return "list";
  else if constexpr (is_spec_v<T, std::forward_list>) return "forward_list";
  else if constexpr (is_spec_v<T, std::set>) return "set";
  else if constexpr (is_spec_v<T, std::multiset>) return "multiset";
  else if constexpr (is_spec_v<T, std::unordered_set>) return "unordered_set";
  else if constexpr (is_spec_v<T, std::unordered_multiset>) return "unordered_multiset";
  else if constexpr (is_spec_v<T, std::map>) return "map";
  else if constexpr (is_spec_v<T, std::multimap>) return "multimap";
  else if constexpr (is_spec_v<T, std::unordered_map>) return "unordered_map";
  else if constexpr (is_spec_v<T, std::unordered_multimap>) return "unordered_multimap";
  else if constexpr (is_std_array<T>::value) return "array";
  else return nullptr;
}

template <class T> constexpr bool is_char_v =
    std::is_same_v<T, char> || std::is_same_v<T, signed char> || std::is_same_v<T, unsigned char>;

template <class A> const typename A::container_type& underlying(const A& a) {
  struct Peek : A {
    static const typename A::container_type& get(const A& x) { return x.*(&Peek::c); }
  };
  return Peek::get(a);
}

// Does dump() draw T as something with parts (so it can stand alone on the heap)?
template <class T> constexpr bool is_compound() {
  using U = std::remove_cv_t<T>;
  if constexpr (std::is_arithmetic_v<U> || std::is_enum_v<U> || std::is_pointer_v<U> ||
                std::is_same_v<U, std::string> || std::is_same_v<U, std::string_view>) return false;
  else if constexpr (requires(const U& u, W& w) { u.__cpviz_dump(w); }) return true;
  else if constexpr (std::is_array_v<U>) return true;
  else if constexpr (requires(const U& u) { std::begin(u); std::end(u); }) return true;
  else if constexpr (is_spec_v<U, std::pair> || is_spec_v<U, std::tuple>) return true;
  else if constexpr (is_spec_v<U, std::stack> || is_spec_v<U, std::queue> ||
                     is_spec_v<U, std::priority_queue>) return true;
  else return false;
}

// ── the writer ──────────────────────────────────────────────────────────

struct Region {
  std::uintptr_t lo, hi;
  std::size_t elem;
  std::string id;
};

inline std::string hex(std::uintptr_t a) {
  char buf[32];
  auto r = std::to_chars(buf, buf + sizeof buf, a, 16);
  return "0x" + std::string(buf, r.ptr);
}

struct Pending {
  std::uintptr_t key;
  const void* p;
  std::size_t count;
  DumpFn fn;
};

template <class T> void dump(W& w, const T& v);

struct W {
  std::string out;
  bool collect = false;           // first pass: only learn where every variable's storage is
  std::string var;                // id of the variable being written ("var:F:N")
  std::vector<Region> regions;
  std::vector<Pending> pending;
  std::vector<std::uintptr_t> emitted;

  void raw(std::string_view s) { if (!collect) out += s; }

  void str_body(std::string_view s) {
    std::size_t n = std::min(s.size(), MAX_STR);
    for (std::size_t i = 0; i < n; i++) {
      unsigned char c = (unsigned char)s[i];
      switch (c) {
        case '"': out += "\\\""; break;
        case '\\': out += "\\\\"; break;
        case '\n': out += "\\n"; break;
        case '\t': out += "\\t"; break;
        case '\r': out += "\\r"; break;
        default:
          if (c < 0x20 || c == 0x7f) {
            char buf[8];
            std::snprintf(buf, sizeof buf, "\\u%04x", c);
            out += buf;
          } else {
            out += (char)c;
          }
      }
    }
    if (s.size() > MAX_STR) out += "\xe2\x80\xa6";
  }
  void quoted(std::string_view s) { if (collect) return; out += '"'; str_body(s); out += '"'; }

  template <class N> void num(N n) {
    if (collect) return;
    char buf[64];
    std::to_chars_result r;
    if constexpr (std::is_floating_point_v<N>) r = std::to_chars(buf, buf + sizeof buf, (double)n);
    else r = std::to_chars(buf, buf + sizeof buf, n);
    out += R"({"k":"num","v":")";
    out.append(buf, r.ptr);
    out += R"("})";
  }
  void boolean(bool b) { raw(b ? R"({"k":"bool","v":"true"})" : R"({"k":"bool","v":"false"})"); }
  void chr(char c) {
    if (collect) return;
    out += R"({"k":"char","v":")";
    if (c == 0) out += "\\\\0"; else str_body(std::string_view(&c, 1));
    out += R"("})";
  }
  void string(std::string_view s) {
    if (collect) return;
    out += R"({"k":"str","v":)";
    quoted(s);
    out += '}';
  }
  void opaque(std::string_view t, std::string_view v) {
    if (collect) return;
    out += R"({"k":"opaque","t":)"; quoted(t); out += R"(,"v":)"; quoted(v); out += '}';
  }

  // Storage the next pointers may land in, most specific wins.
  void region(const void* lo, std::size_t bytes, std::size_t elem) {
    if (!collect || var.empty() || bytes == 0) return;
    regions.push_back({(std::uintptr_t)lo, (std::uintptr_t)lo + bytes, elem, var});
  }
  const Region* find_region(std::uintptr_t a) const {
    const Region* best = nullptr;
    for (auto& r : regions) {
      // Ties go to the later one: an array registers its elements after the variable itself.
      if (a >= r.lo && a < r.hi && (!best || r.hi - r.lo <= best->hi - best->lo)) best = &r;
    }
    return best;
  }

  // field("x", v) inside an object's __cpviz_dump
  bool first_field = true;
  template <class T> void field(const char* name, const T& v) {
    if (!collect) {
      if (!first_field) out += ',';
      first_field = false;
      out += '['; quoted(name); out += ',';
    }
    dump(*this, v);
    raw("]");
  }

  template <class E> void pointer(const E* p, std::string_view tname, bool is_ref = false) {
    if (collect) return;
    auto a = (std::uintptr_t)p;
    out += R"({"k":"ptr","t":)"; quoted(std::string(tname) + (is_ref ? "&" : "*"));
    out += R"(,"addr":")"; out += hex(a); out += R"(","to":)";
    if (!p) { out += "null}"; return; }
    if (auto r = find_region(a)) {
      out += '"'; out += r->id; out += '"';
      if (r->elem && r->hi - r->lo > r->elem) {
        out += R"(,"idx":)"; out += std::to_string((a - r->lo) / r->elem);
      }
      out += '}';
      return;
    }
    if constexpr (!std::is_void_v<E> && !std::is_function_v<E>) {
      auto& m = allocs();
      auto it = m.upper_bound(a);
      if (it != m.begin()) {
        --it;
        std::uintptr_t base = it->first;
        std::size_t size = it->second;
        if (a < base + size) {
          constexpr std::size_t sz = sizeof(E);
          bool array = size >= 2 * sz && (a - base) % sz == 0;
          std::uintptr_t key = array ? base : a;
          std::size_t count = array ? size / sz : 1;
          if (!array && a + sz > base + size) { out += "null}"; return; }
          out += '"'; out += hex(key); out += '"';
          if (array) { out += R"(,"idx":)"; out += std::to_string((a - base) / sz); }
          out += '}';
          pending.push_back({key, (const void*)key, count, [](W& w, const void* q, std::size_t n) {
            const E* e = static_cast<const E*>(q);
            if (n == 1 && is_compound<E>()) { dump(w, *e); return; }
            w.out += R"({"k":"seq","t":)"; w.quoted(type_name<E>() + (n > 1 ? "[]" : ""));
            w.out += R"(,"items":[)";
            std::size_t shown = std::min(n, MAX_ITEMS);
            for (std::size_t i = 0; i < shown; i++) { if (i) w.out += ','; dump(w, e[i]); }
            w.out += R"(],"more":)"; w.out += std::to_string(n - shown); w.out += '}';
          }});
          return;
        }
      }
    }
    out += "null}";
  }

  template <class It> void seq(std::string_view t, It first, std::size_t n, std::size_t total) {
    raw(R"({"k":"seq","t":)"); quoted(t); raw(R"(,"items":[)");
    std::size_t i = 0;
    for (; i < n; ++i, ++first) {
      if (i) raw(",");
      using V = std::remove_cvref_t<decltype(*first)>;
      if constexpr (std::is_class_v<V> && std::is_convertible_v<V, bool> && !is_compound<V>() &&
                    !requires { &V::operator(); }) {
        boolean(static_cast<bool>(*first));  // vector<bool>'s bit references
      } else {
        dump(*this, *first);
      }
    }
    raw(R"(],"more":)"); if (!collect) out += std::to_string(total - n); raw("}");
  }
};

// ── dumping a value ─────────────────────────────────────────────────────

template <class T> void dump(W& w, const T& v) {
  using U = std::remove_cv_t<T>;
  if constexpr (std::is_same_v<U, bool>) {
    w.boolean(v);
  } else if constexpr (is_char_v<U>) {
    w.chr((char)v);
  } else if constexpr (std::is_arithmetic_v<U>) {
    w.num(v);
  } else if constexpr (std::is_enum_v<U>) {
    w.num((long long)v);
  } else if constexpr (std::is_same_v<U, std::nullptr_t>) {
    w.pointer((const void*)nullptr, "nullptr_t");
  } else if constexpr (std::is_same_v<U, std::string> || std::is_same_v<U, std::string_view>) {
    w.region(v.data(), v.size(), 1);
    w.string(v);
  } else if constexpr (std::is_pointer_v<U>) {
    using E = std::remove_pointer_t<U>;
    if constexpr (std::is_function_v<E>) w.opaque("function", "ƒ");
    else w.pointer(static_cast<const std::remove_cv_t<E>*>(v), type_name<std::remove_cv_t<E>>());
  } else if constexpr (std::is_array_v<U>) {
    using E = std::remove_extent_t<U>;
    constexpr std::size_t N = std::extent_v<U>;
    w.region(&v[0], sizeof(U), sizeof(E));
    std::size_t n = N;
    if constexpr (is_char_v<E>) {  // a C string: up to and including its terminator
      std::size_t len = 0;
      while (len < N && v[len]) len++;
      n = std::min(N, len + 1);
    }
    std::size_t shown = std::min(n, MAX_ITEMS);
    w.seq(type_name<E>() + "[" + std::to_string(N) + "]", &v[0], shown, n);
  } else if constexpr (is_spec_v<U, std::unique_ptr> || is_spec_v<U, std::shared_ptr> ||
                       is_spec_v<U, std::weak_ptr>) {
    using E = typename U::element_type;
    if constexpr (is_spec_v<U, std::weak_ptr>) {
      auto s = v.lock();
      w.pointer(static_cast<const std::remove_cv_t<E>*>(s.get()), type_name<E>());
    } else {
      w.pointer(static_cast<const std::remove_cv_t<E>*>(v.get()), type_name<E>());
    }
  } else if constexpr (is_spec_v<U, std::optional>) {
    if (v) dump(w, *v); else w.opaque("optional", "nullopt");
  } else if constexpr (is_bitset<U>::value) {
    w.string(v.to_string());
  } else if constexpr (is_spec_v<U, std::pair>) {
    w.raw(R"({"k":"seq","t":"pair","items":[)");
    dump(w, v.first); w.raw(","); dump(w, v.second);
    w.raw(R"(],"more":0})");
  } else if constexpr (is_spec_v<U, std::tuple>) {
    w.raw(R"({"k":"seq","t":"tuple","items":[)");
    std::apply([&](const auto&... xs) {
      bool first = true;
      ((w.raw(first ? "" : ","), first = false, dump(w, xs)), ...);
    }, v);
    w.raw(R"(],"more":0})");
  } else if constexpr (is_spec_v<U, std::stack> || is_spec_v<U, std::queue> ||
                       is_spec_v<U, std::priority_queue>) {
    const auto& c = underlying(v);
    const char* t = is_spec_v<U, std::stack> ? "stack" : is_spec_v<U, std::queue> ? "queue"
                                                                                  : "priority_queue";
    std::size_t n = c.size(), shown = std::min(n, MAX_ITEMS);
    w.seq(t, c.begin(), shown, n);
  } else if constexpr (requires(const U& u, W& x) { u.__cpviz_dump(x); }) {
    w.region(&v, sizeof(U), sizeof(U));
    if (!w.collect) { w.out += R"({"k":"obj","t":)"; w.quoted(type_name<U>()); w.out += R"(,"fields":[)"; }
    bool saved = w.first_field;
    w.first_field = true;
    v.__cpviz_dump(w);
    w.first_field = saved;
    w.raw("]}");
  } else if constexpr (requires { typename U::key_type; typename U::mapped_type; }) {
    const char* t = container_name<U>();
    w.raw(R"({"k":"map","t":)"); w.quoted(t ? t : "map"); w.raw(R"(,"entries":[)");
    std::size_t i = 0, n = v.size();
    for (auto it = v.begin(); it != v.end() && i < MAX_ITEMS; ++it, ++i) {
      if (i) w.raw(",");
      w.raw("["); dump(w, it->first); w.raw(","); dump(w, it->second); w.raw("]");
    }
    w.raw(R"(],"more":)"); if (!w.collect) w.out += std::to_string(n - i); w.raw("}");
  } else if constexpr (requires(const U& u) { std::begin(u); std::end(u); }) {
    using V = std::remove_cvref_t<decltype(*std::begin(v))>;
    if constexpr (requires(const U& u) { u.data(); u.size(); }) {
      if constexpr (!std::is_class_v<V> || !std::is_convertible_v<V, bool>) {
        w.region(v.data(), v.size() * sizeof(V), sizeof(V));
      }
    }
    std::size_t n = (std::size_t)std::distance(std::begin(v), std::end(v));
    const char* t = container_name<U>();
    w.seq(t ? std::string_view(t) : std::string_view(type_name<U>()), std::begin(v),
          std::min(n, MAX_ITEMS), n);
  } else if constexpr (std::is_class_v<U> && requires { &U::operator(); }) {
    w.opaque("function", "λ");  // a lambda or function object
  } else {
    w.opaque(type_name<U>(), type_name<U>());
  }
}

// ── steps ───────────────────────────────────────────────────────────────

struct Frame;
inline void emit(const char* event, int line, const std::string* ret = nullptr);

template <class T> void dump_var(W& w, const void* p, std::size_t) { dump(w, *static_cast<const T*>(p)); }

template <class E> void dump_span(W& w, const void* p, std::size_t n) {
  const E* e = static_cast<const E*>(p);
  w.region(e, n * sizeof(E), sizeof(E));
  w.seq(type_name<E>() + "[" + std::to_string(n) + "]", e, std::min(n, MAX_ITEMS), n);
}

struct by_ref_t {};
inline constexpr by_ref_t by_ref{};

template <class T> void dump_ref(W& w, const void* p, std::size_t) {
  const T& v = *static_cast<const T*>(p);
  if (w.collect) return;  // a reference owns no storage; what it names is registered elsewhere
  if (w.find_region((std::uintptr_t)p)) {
    w.pointer(&v, type_name<T>(), true);  // an arrow to what it refers to
  } else {
    dump(w, v);
  }
}

// Registers a variable with the frame running now, or with the globals before main.
struct Var {
  FrameRec* frame;
  template <class T> Var(const char* name, const T& v) {
    reg({name, &v, sizeof(T), 1, &dump_var<T>, false});
  }
  template <class T> Var(const char* name, const T& v, by_ref_t) {
    reg({name, &v, 0, 1, &dump_ref<T>, true});
  }
  template <class E> Var(const char* name, E* p, std::size_t n) {
    reg({name, p, n * sizeof(E), n, &dump_span<std::remove_cv_t<E>>, false});
  }
  void reg(VarRec r) {
    bool was = internal(); internal() = true;
    frame = stack().empty() ? &globals() : stack().back();
    frame->vars.push_back(r);
    internal() = was;
  }
  ~Var() { if (!frame->vars.empty()) frame->vars.pop_back(); }
  Var(const Var&) = delete;
  Var& operator=(const Var&) = delete;
};

struct Frame {
  FrameRec rec;
  int end_line;
  bool returned = false;
  Frame(const char* name, int line, int end) : rec{0, name, line, {}}, end_line(end) {
    rec.id = next_frame_id()++;
    bool was = internal(); internal() = true;
    stack().push_back(&rec);
    internal() = was;
  }
  void call() { emit("call", rec.line); }
  ~Frame() {
    if (!returned) {
      rec.line = end_line;
      emit("return", end_line);
    }
    bool was = internal(); internal() = true;
    stack().pop_back();
    internal() = was;
  }
  Frame(const Frame&) = delete;
  Frame& operator=(const Frame&) = delete;
};

inline void write_frame(W& w, FrameRec& f) {
  if (!w.collect) {
    w.out += R"({"id":)"; w.out += std::to_string(f.id);
    w.out += R"(,"name":)"; w.quoted(f.name);
    w.out += R"(,"line":)"; w.out += std::to_string(f.line);
    w.out += R"(,"vars":[)";
  }
  for (std::size_t i = 0; i < f.vars.size(); i++) {
    auto& v = f.vars[i];
    w.var = "var:" + std::to_string(f.id) + ":" + std::to_string(i);
    if (w.collect && !v.ref) {
      // The variable's own bytes; arrays and containers add their elements more precisely.
      w.regions.push_back({(std::uintptr_t)v.addr, (std::uintptr_t)v.addr + v.size, v.size, w.var});
    }
    if (!w.collect) { if (i) w.out += ','; w.out += '['; w.quoted(v.name); w.out += ','; }
    v.dump(w, v.addr, v.count);
    w.raw("]");
  }
  w.var.clear();
  w.raw("]}");
}

inline void emit(const char* event, int line, const std::string* ret) {
  if (internal()) return;
  internal() = true;
  if (++steps_taken() > CPVIZ_MAX_STEPS) {
    std::fflush(stdout);
    std::cout.flush();
    std::fputs("\x1e" "CPVIZ_TRUNCATED\n", stderr);
    std::fflush(stderr);
    std::_Exit(0);
  }
  // Whatever the program printed so far reaches the page before this step does, so the page can
  // tell how much output existed at this point.
  std::cout.flush();
  std::fflush(stdout);

  if (!stack().empty()) stack().back()->line = line;
  globals().line = line;

  W w;
  w.collect = true;
  if (!globals().vars.empty()) write_frame(w, globals());
  for (auto* f : stack()) write_frame(w, *f);

  w.collect = false;
  w.out.reserve(4096);
  w.out += "\x1e{\"event\":\""; w.out += event;
  w.out += "\",\"line\":"; w.out += std::to_string(line);
  w.out += ",\"frames\":[";
  bool first = true;
  if (!globals().vars.empty()) { write_frame(w, globals()); first = false; }
  for (auto* f : stack()) { if (!first) w.out += ','; first = false; write_frame(w, *f); }
  w.out += "],\"heap\":{";
  std::size_t written = 0;
  for (std::size_t i = 0; i < w.pending.size() && written < MAX_HEAP; i++) {
    Pending p = w.pending[i];
    if (std::find(w.emitted.begin(), w.emitted.end(), p.key) != w.emitted.end()) continue;
    w.emitted.push_back(p.key);
    if (written++) w.out += ',';
    w.out += '"'; w.out += hex(p.key); w.out += "\":";
    p.fn(w, p.p, p.count);
  }
  w.out += '}';
  if (ret) { w.out += ",\"ret\":"; w.out += *ret; }
  w.out += "}\n";
  std::fwrite(w.out.data(), 1, w.out.size(), stderr);
  internal() = false;
}

inline void step(int line) { emit("line", line); }

template <class T> std::string encode_ret(const T& v) {
  bool was = internal(); internal() = true;
  W w;
  // The return value can point at the frame's own variables, which the step resolves itself.
  for (auto* f : stack()) { w.collect = true; write_frame(w, *f); }
  w.collect = false;
  w.out.clear();
  dump(w, v);
  internal() = was;
  return w.out;
}

template <class T> T&& ret(Frame& f, int line, T&& v) {
  std::string r = encode_ret(v);
  f.returned = true;
  f.rec.line = line;
  emit("return", line, &r);
  return std::forward<T>(v);
}
template <class T> void note_ret(Frame& f, int line, const T& v) {
  std::string r = encode_ret(v);
  f.returned = true;
  f.rec.line = line;
  emit("return", line, &r);
}
inline void note_ret_void(Frame& f, int line) {
  f.returned = true;
  f.rec.line = line;
  emit("return", line);
}

}  // namespace cpviz

// The program's own allocations, so pointers into the heap can be followed safely.
inline void* cpviz_new(std::size_t n) {
  void* p = std::malloc(n ? n : 1);
  if (!p) { std::fputs("out of memory\n", stderr); std::abort(); }
  cpviz::note_alloc(p, n);
  return p;
}
void* operator new(std::size_t n) { return cpviz_new(n); }
void* operator new[](std::size_t n) { return cpviz_new(n); }
void* operator new(std::size_t n, const std::nothrow_t&) noexcept { return cpviz_new(n); }
void* operator new[](std::size_t n, const std::nothrow_t&) noexcept { return cpviz_new(n); }
void operator delete(void* p) noexcept { cpviz::note_free(p); std::free(p); }
void operator delete[](void* p) noexcept { cpviz::note_free(p); std::free(p); }
void operator delete(void* p, std::size_t) noexcept { cpviz::note_free(p); std::free(p); }
void operator delete[](void* p, std::size_t) noexcept { cpviz::note_free(p); std::free(p); }

// C's allocator too, for programs written in the C style. Defined last, so nothing above (and
// no standard header, all of which are already included) is rewritten by these macros.
inline void* cpviz_malloc(std::size_t n) { void* p = std::malloc(n); cpviz::note_alloc(p, n); return p; }
inline void* cpviz_calloc(std::size_t k, std::size_t n) {
  void* p = std::calloc(k, n); cpviz::note_alloc(p, k * n); return p;
}
inline void* cpviz_realloc(void* q, std::size_t n) {
  cpviz::note_free(q); void* p = std::realloc(q, n); cpviz::note_alloc(p, n); return p;
}
inline void cpviz_free(void* p) { cpviz::note_free(p); std::free(p); }
#define malloc(n) cpviz_malloc(n)
#define calloc(k, n) cpviz_calloc(k, n)
#define realloc(p, n) cpviz_realloc(p, n)
#define free(p) cpviz_free(p)
