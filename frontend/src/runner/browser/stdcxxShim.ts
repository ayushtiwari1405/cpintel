/**
 * A stand-in for g++'s <bits/stdc++.h>, which the browser's libc++ does not have.
 *
 * Nearly every competitive solution starts with it. This includes what solutions actually use
 * rather than all of the standard library: every header costs compile time, and in the browser
 * that is the student's wait. Heavy, rarely used ones (regex, format, filesystem, threads) are
 * left out, and a solution that needs one can still include it by name. <csetjmp> and <csignal>
 * do not exist for WebAssembly at all.
 *
 * __gcd and __lg are libstdc++ internals that solutions use as if they were standard, so they
 * are defined here with libstdc++'s meaning.
 */
export const BITS_STDCXX = `#pragma once
#include <cassert>
#include <cctype>
#include <cfloat>
#include <climits>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <algorithm>
#include <array>
#include <bit>
#include <bitset>
#include <chrono>
#include <complex>
#include <deque>
#include <functional>
#include <iomanip>
#include <iostream>
#include <iterator>
#include <limits>
#include <list>
#include <map>
#include <memory>
#include <numeric>
#include <optional>
#include <queue>
#include <random>
#include <set>
#include <sstream>
#include <stack>
#include <string>
#include <string_view>
#include <tuple>
#include <unordered_map>
#include <unordered_set>
#include <utility>
#include <vector>

namespace std {
template <class T> inline T __gcd(T a, T b) {
  while (b != 0) { T t = a % b; a = b; b = t; }
  return a;
}
inline int __lg(unsigned long long n) { return n ? 63 - __builtin_clzll(n) : -1; }
inline int __lg(long long n) { return __lg(static_cast<unsigned long long>(n)); }
inline int __lg(unsigned n) { return n ? 31 - __builtin_clz(n) : -1; }
inline int __lg(int n) { return __lg(static_cast<unsigned>(n)); }
inline int __lg(unsigned long n) { return __lg(static_cast<unsigned long long>(n)); }
inline int __lg(long n) { return __lg(static_cast<unsigned long long>(n)); }
}
`
