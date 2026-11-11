import type { LessonNote } from './lessons'
import type { VizLanguage } from './trace'

export interface Example {
  id: string
  title: string
  topic: string
  language: VizLanguage
  code: string
  stdin: string
  /** Notes written for the lesson, placed on their steps once the program is traced. */
  lesson?: LessonNote[]
  /** An example worth running beside this one in Compare. */
  compareWith?: string
}

/** Starting points for a lecture: each one shows off a different view. */
export const EXAMPLES: Example[] = [
  {
    id: 'py-fib', title: 'Recursion: Fibonacci', topic: 'Recursion', language: 'python', stdin: '',
    code: `def fib(n):
    if n < 2:
        return n
    return fib(n - 1) + fib(n - 2)

print(fib(5))
`,
    lesson: [
      { line: 6, text: "We call fib(5). Open the Calls tab: every call waits for the two calls below it to finish." },
      { line: 3, text: "A base case: fib(1) returns straight away, with no calls below it." },
      { line: 4, on: 'return', text: "fib(2) is done: 1 + 0 = 1. Its frame leaves the stack and the caller carries on." },
      { line: 4, nth: 3, text: "This is fib(3). Count how often fib(3) is computed in the whole tree: that repeated work is what memoisation removes." },
    ],
  },
  {
    id: 'py-bubble', title: 'Bubble sort', topic: 'Sorting', language: 'python', stdin: '',
    code: `a = [5, 2, 9, 1, 6, 3]
n = len(a)
for i in range(n):
    for j in range(n - 1 - i):
        if a[j] > a[j + 1]:
            a[j], a[j + 1] = a[j + 1], a[j]
print(a)
`,
    lesson: [
      { line: 5, text: "Compare the neighbours a[j] and a[j + 1] (ringed in Structures; tick Bars to see their heights)." },
      { line: 6, text: "Out of order, so swap them: the larger value moves one place right." },
      { line: 3, nth: 2, text: "One pass done: the largest value has bubbled to the end and will not move again." },
      { line: 7, text: "Sorted. Bubble sort makes about n²/2 comparisons whatever the input." },
    ],
    compareWith: 'py-insertion',
  },
  {
    id: 'py-insertion', title: 'Insertion sort', topic: 'Sorting', language: 'python', stdin: '',
    code: `a = [5, 2, 9, 1, 6, 3]
for i in range(1, len(a)):
    key = a[i]
    j = i - 1
    while j >= 0 and a[j] > key:
        a[j + 1] = a[j]
        j -= 1
    a[j + 1] = key
print(a)
`,
    lesson: [
      { line: 3, text: "Take the next element out. Everything left of i is already sorted." },
      { line: 6, text: "Shift the larger element one place right, opening a gap for key." },
      { line: 8, text: "Drop key into the gap. Compare with bubble sort: on nearly sorted input this does far less work." },
    ],
    compareWith: 'py-bubble',
  },
  {
    id: 'py-linked', title: 'Linked list: reverse', topic: 'Linked lists', language: 'python', stdin: '',
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
    lesson: [
      { line: 11, text: "Save the rest of the list first: the next line cuts this node's link to it." },
      { line: 12, text: "Point this node backwards. Watch its arrow flip in the Memory tab." },
      { line: 15, text: "cur ran off the end, so prev is the old tail, which is now the head." },
    ],
  },
  {
    id: 'py-bfs', title: 'Graph: BFS', topic: 'Graphs', language: 'python', stdin: '',
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
    lesson: [
      { line: 8, text: "Take the oldest node in the queue: BFS finishes one distance before starting the next." },
      { line: 11, text: "The first time we reach v is by a shortest path, so this is its distance. Structures shows dist beside each node." },
      { line: 13, text: "Every node's distance from node 0, counted in edges." },
    ],
  },
  {
    id: 'py-lcs', title: 'DP table: LCS', topic: 'Dynamic programming', language: 'python', stdin: '',
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
    lesson: [
      { line: 5, text: "dp[i][j] is the LCS of the first i letters of s and the first j of t. Compare the last letters." },
      { line: 6, text: "They match: one more than the answer without both of them (the diagonal)." },
      { line: 8, text: "No match: the better of dropping a letter from s (above) or from t (left). The ringed cells are the ones read." },
    ],
  },
  {
    id: 'py-bst', title: 'Binary search tree', topic: 'Trees', language: 'python', stdin: '',
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
    lesson: [
      { line: 10, text: "Smaller keys go left and larger go right, so every insert walks one path down from the root." },
      { line: 9, text: "An empty spot: the new node goes here." },
    ],
  },
  {
    id: 'cpp-binsearch', title: 'Binary search', topic: 'Searching', language: 'cpp', stdin: '9\n',
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
    lesson: [
      { line: 10, text: "Look at the middle of what is left, [lo, hi]." },
      { line: 12, text: "a[mid] is too small, so x can only be to its right: drop the left half." },
      { line: 15, text: "Each step halved the range, so this took about log₂(n) steps." },
    ],
  },
  {
    id: 'cpp-linked', title: 'Pointers: linked list', topic: 'Pointers and memory', language: 'cpp', stdin: '',
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
    lesson: [
      { line: 12, text: "new puts a Node on the heap, and n points to it." },
      { line: 13, text: "The new node becomes the head: this list grows at the front." },
      { line: 17, text: "Walk the list by following next pointers until nullptr." },
    ],
  },
  {
    id: 'cpp-fact', title: 'Recursion and references', topic: 'Recursion', language: 'cpp', stdin: '',
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
    lesson: [
      { line: 10, text: "x is a reference: changing it changes main's vector (follow the violet arrow)." },
      { line: 5, text: "The base case: fact(1) is 1. Now the waiting calls multiply on the way back up." },
    ],
  },
  {
    id: 'cpp-dfs', title: 'Graph: DFS', topic: 'Graphs', language: 'cpp', stdin: '',
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
    lesson: [
      { line: 8, text: "Mark u visited before exploring from it, so a cycle cannot bring us back." },
      { line: 10, text: "Go deep: dfs(v) runs to completion before the next neighbour of u is tried." },
    ],
  },
  {
    id: 'c-malloc', title: 'malloc: a linked list', topic: 'Pointers and memory', language: 'c', stdin: '',
    code: `#include <stdio.h>
#include <stdlib.h>

typedef struct Node {
    int value;
    struct Node *next;
} Node;

Node *push(Node *head, int value) {
    Node *n = malloc(sizeof(Node));
    n->value = value;
    n->next = head;
    return n;
}

int main(void) {
    Node *head = NULL;
    for (int i = 1; i <= 3; i++) {
        head = push(head, i * 10);
    }
    for (Node *p = head; p != NULL; p = p->next) {
        printf("%d ", p->value);
    }
    printf("\\n");
    return 0;
}
`,
    lesson: [
      { line: 10, text: "malloc gives an unnamed block on the heap; n holds its address." },
      { line: 12, text: "Link the new node in front of the old head." },
    ],
  },
  {
    id: 'c-array', title: 'Arrays and pointers', topic: 'Pointers and memory', language: 'c', stdin: '',
    code: `#include <stdio.h>

void reverse(int *a, int n) {
    int *l = a, *r = a + n - 1;
    while (l < r) {
        int t = *l;
        *l = *r;
        *r = t;
        l++;
        r--;
    }
}

int main(void) {
    int a[5] = {1, 2, 3, 4, 5};
    reverse(a, 5);
    for (int i = 0; i < 5; i++) printf("%d ", a[i]);
    return 0;
}
`,
    lesson: [
      { line: 4, text: "l and r are pointers into main's array: follow the arrows." },
      { line: 7, text: "*l = *r writes through the pointer, into a[] itself." },
    ],
  },
]

export const STARTER: Record<VizLanguage, string> = {
  python: EXAMPLES[0].code,
  cpp: EXAMPLES.find(e => e.id === 'cpp-binsearch')!.code,
  c: EXAMPLES.find(e => e.id === 'c-malloc')!.code,
}

export const LANGUAGE_LABELS: Record<VizLanguage, string> = { python: 'Python 3', cpp: 'C++', c: 'C' }
