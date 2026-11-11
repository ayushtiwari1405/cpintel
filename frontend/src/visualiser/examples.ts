import type { VizLanguage } from './trace'

export interface Example { id: string; title: string; language: VizLanguage; code: string; stdin: string }

/** Starting points for a lecture: each one shows off a different view. */
export const EXAMPLES: Example[] = [
  {
    id: 'py-fib', title: 'Recursion: Fibonacci', language: 'python', stdin: '',
    code: `def fib(n):
    if n < 2:
        return n
    return fib(n - 1) + fib(n - 2)

print(fib(5))
`,
  },
  {
    id: 'py-bubble', title: 'Bubble sort', language: 'python', stdin: '',
    code: `a = [5, 2, 9, 1, 6, 3]
n = len(a)
for i in range(n):
    for j in range(n - 1 - i):
        if a[j] > a[j + 1]:
            a[j], a[j + 1] = a[j + 1], a[j]
print(a)
`,
  },
  {
    id: 'py-linked', title: 'Linked list: reverse', language: 'python', stdin: '',
    code: `class Node:
    def __init__(self, val, next=None):
        self.val = val
        self.next = next

head = Node(1, Node(2, Node(3)))

prev = None
cur = head
while cur:
    nxt = cur.next
    cur.next = prev
    prev = cur
    cur = nxt
head = prev
`,
  },
  {
    id: 'py-bfs', title: 'Graph: BFS', language: 'python', stdin: '',
    code: `from collections import deque

adj = [[1, 2], [0, 3], [0, 3, 4], [1, 2], [2]]
dist = [-1] * len(adj)
dist[0] = 0
q = deque([0])
while q:
    u = q.popleft()
    for v in adj[u]:
        if dist[v] == -1:
            dist[v] = dist[u] + 1
            q.append(v)
print(dist)
`,
  },
  {
    id: 'py-lcs', title: 'DP table: LCS', language: 'python', stdin: '',
    code: `s, t = "abcb", "bcab"
dp = [[0] * (len(t) + 1) for _ in range(len(s) + 1)]
for i in range(1, len(s) + 1):
    for j in range(1, len(t) + 1):
        if s[i - 1] == t[j - 1]:
            dp[i][j] = dp[i - 1][j - 1] + 1
        else:
            dp[i][j] = max(dp[i - 1][j], dp[i][j - 1])
print(dp[len(s)][len(t)])
`,
  },
  {
    id: 'py-bst', title: 'Binary search tree', language: 'python', stdin: '',
    code: `class Node:
    def __init__(self, key):
        self.key = key
        self.left = None
        self.right = None

def insert(root, key):
    if root is None:
        return Node(key)
    if key < root.key:
        root.left = insert(root.left, key)
    else:
        root.right = insert(root.right, key)
    return root

root = None
for k in [5, 3, 8, 1, 4, 9]:
    root = insert(root, k)
`,
  },
  {
    id: 'cpp-binsearch', title: 'Binary search', language: 'cpp', stdin: '7\n',
    code: `#include <bits/stdc++.h>
using namespace std;

int main() {
    vector<int> a = {1, 3, 4, 7, 9, 12, 15};
    int x;
    cin >> x;
    int lo = 0, hi = (int)a.size() - 1, ans = -1;
    while (lo <= hi) {
        int mid = (lo + hi) / 2;
        if (a[mid] == x) { ans = mid; break; }
        if (a[mid] < x) lo = mid + 1;
        else hi = mid - 1;
    }
    cout << ans << "\\n";
}
`,
  },
  {
    id: 'cpp-linked', title: 'Pointers: linked list', language: 'cpp', stdin: '',
    code: `#include <bits/stdc++.h>
using namespace std;

struct Node {
    int val;
    Node* next;
};

int main() {
    Node* head = nullptr;
    for (int i = 3; i >= 1; i--) {
        Node* n = new Node{i, head};
        head = n;
    }
    int sum = 0;
    for (Node* p = head; p != nullptr; p = p->next) {
        sum += p->val;
    }
    cout << sum << "\\n";
}
`,
  },
  {
    id: 'cpp-fact', title: 'Recursion and references', language: 'cpp', stdin: '',
    code: `#include <bits/stdc++.h>
using namespace std;

long long fact(int n) {
    if (n <= 1) return 1;
    return n * fact(n - 1);
}

void addOne(vector<int>& v) {
    for (int& x : v) x++;
}

int main() {
    vector<int> v = {1, 2, 3};
    addOne(v);
    cout << fact(5) << "\\n";
}
`,
  },
  {
    id: 'cpp-dfs', title: 'Graph: DFS', language: 'cpp', stdin: '',
    code: `#include <bits/stdc++.h>
using namespace std;

vector<vector<int>> adj = {{1, 2}, {0, 3}, {0}, {1}};
vector<bool> vis(4, false);

void dfs(int u) {
    vis[u] = true;
    for (int v : adj[u]) {
        if (!vis[v]) dfs(v);
    }
}

int main() {
    dfs(0);
    return 0;
}
`,
  },
]

export const STARTER: Record<VizLanguage, string> = {
  python: EXAMPLES[0].code,
  cpp: EXAMPLES.find(e => e.id === 'cpp-binsearch')!.code,
}
