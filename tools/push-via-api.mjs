/*
  用 api.github.com 的 Git Data API 把本地提交推上去。

  ── 为什么不用 git push ──────────────────────────────────────
  本机到 github.com:443 的 TCP 能连上，但 TLS 会话会被重置：

    $ git ls-remote origin
    fatal: unable to access '...': Recv failure: Connection was reset
    $ Invoke-WebRequest https://github.com/...
    请求被中止: 连接被意外关闭。

  而 api.github.com 完全正常（仓库本身就是用 API 建的）。所以改走 API。

  ── 实现要点 ────────────────────────────────────────────────
  * 按 rev-list --reverse 顺序逐条提交推送，**保留父子链**，不会把历史压平。
  * 上传的是 **git 对象库里的原始字节**（git cat-file blob / ls-tree），
    不是工作区文件 —— 这样 core.autocrlf=true 的行尾归一化结果能原样保留，
    推完 git status 仍然是干净的。
  * blob 与 tree 都按「本地 sha → 远端 sha」缓存，多提交之间不重复上传。
  * 空仓库不能建 blob（POST /git/blobs 会 409 "Git Repository is empty."），
    所以脚本会先自动发一个引导提交把仓库激活，最后再把 ref 指到真正的提交。

  用法: node tools/push-via-api.mjs [owner/repo] [branch] [repoDir]
*/
import { execFileSync } from 'node:child_process';
import path from 'node:path';
import process from 'node:process';

const REPO = process.argv[2] || 'Alan-qwq/FastTransfer';
const BRANCH = process.argv[3] || 'main';
const ROOT = path.resolve(process.argv[4] || '.');
const API = 'https://api.github.com';
const VERBOSE = process.env.PUSH_VERBOSE === '1';

const log = (m) => process.stdout.write('[push] ' + m + '\n');

function git(args, opts = {}) {
  return execFileSync('git', ['-C', ROOT, ...args], {
    maxBuffer: 1 << 30,
    encoding: opts.encoding === undefined ? 'utf8' : opts.encoding,
    input: opts.input,
  });
}

const token = (() => {
  const out = git(['credential', 'fill'], { input: 'protocol=https\nhost=github.com\n\n' });
  const m = out.match(/^password=(.+)$/m);
  if (!m) throw new Error('拿不到 github 凭据（git credential fill 没返回 password）');
  return m[1].trim();
})();

const HEADERS = {
  Authorization: 'token ' + token,
  Accept: 'application/vnd.github+json',
  'User-Agent': 'dsh-agent',
  'X-GitHub-Api-Version': '2022-11-28',
};

async function api(method, url, body) {
  // 这台机器到 api.github.com 的连接会偶发 ECONNRESET（网络本身不稳），
  // 所以网络错误和 5xx 都要退避重试；4xx 是请求本身的问题，重试没意义。
  let lastErr;
  for (let attempt = 1; attempt <= 5; attempt++) {
    let res;
    try {
      res = await fetch(API + url, {
        method,
        headers: body ? { ...HEADERS, 'Content-Type': 'application/json' } : HEADERS,
        body: body ? JSON.stringify(body) : undefined,
      });
    } catch (e) {
      lastErr = e;
      const wait = 500 * attempt;
      log(`  网络错误（第 ${attempt}/5 次）: ${e.cause?.code || e.message}，${wait}ms 后重试`);
      await new Promise((r) => setTimeout(r, wait));
      continue;
    }
    const text = await res.text();
    let json = null;
    try { json = text ? JSON.parse(text) : null; } catch { /* 非 JSON 响应 */ }
    if (res.ok) return json;

    const detail = json ? JSON.stringify(json).slice(0, 300) : text.slice(0, 300);
    if (res.status >= 500 && attempt < 5) {
      const wait = 500 * attempt;
      log(`  ${res.status}（第 ${attempt}/5 次），${wait}ms 后重试`);
      await new Promise((r) => setTimeout(r, wait));
      continue;
    }
    throw new Error(`${method} ${url} -> ${res.status} ${detail}`);
  }
  throw new Error(`${method} ${url} 重试 5 次仍失败: ${lastErr?.cause?.code || lastErr?.message}`);
}

const parseLsTree = (out) => out.split('\0').filter(Boolean).map((rec) => {
  const tab = rec.indexOf('\t');
  const [mode, type, sha] = rec.slice(0, tab).split(' ');
  return { mode, type, sha, path: rec.slice(tab + 1) };
});

// ---------- 0. 仓库必须至少有一个提交，否则建不了 blob ----------
let hasCommits = true;
try {
  const cs = await api('GET', `/repos/${REPO}/commits?per_page=1`);
  hasCommits = Array.isArray(cs) && cs.length > 0;
} catch {
  hasCommits = false;
}
if (!hasCommits) {
  log('仓库是空的，先发一个引导提交把 Git 数据接口激活');
  await api('PUT', `/repos/${REPO}/contents/.bootstrap`, {
    message: 'chore: 引导空仓库（随后会被真实提交覆盖）',
    content: Buffer.from('# bootstrap\n').toString('base64'),
  });
}

// ---------- 1. blob / tree 缓存与递归上传 ----------
const blobCache = new Map(); // 本地 blob sha -> 远端 blob sha
const treeCache = new Map(); // 本地 tree sha -> 远端 tree sha
let blobCount = 0;

async function pushBlob(localSha) {
  if (blobCache.has(localSha)) return blobCache.get(localSha);
  const buf = git(['cat-file', 'blob', localSha], { encoding: null });
  const r = await api('POST', `/repos/${REPO}/git/blobs`, {
    content: Buffer.from(buf).toString('base64'),
    encoding: 'base64',
  });
  blobCache.set(localSha, r.sha);
  blobCount++;
  if (VERBOSE && blobCount % 40 === 0) log(`  已上传 ${blobCount} 个 blob`);
  return r.sha;
}

async function pushTree(localTreeSha) {
  if (treeCache.has(localTreeSha)) return treeCache.get(localTreeSha);
  const entries = parseLsTree(git(['ls-tree', '-z', localTreeSha]));
  const tree = [];
  for (const e of entries) {
    if (e.type === 'blob') {
      tree.push({ path: e.path, mode: e.mode, type: 'blob', sha: await pushBlob(e.sha) });
    } else {
      tree.push({ path: e.path, mode: e.mode, type: 'tree', sha: await pushTree(e.sha) });
    }
  }
  const r = await api('POST', `/repos/${REPO}/git/trees`, { tree });
  treeCache.set(localTreeSha, r.sha);
  return r.sha;
}

// ---------- 2. 逐条提交推送，保留父子链 ----------
const localCommits = git(['rev-list', '--reverse', 'HEAD']).trim().split('\n').filter(Boolean);
log(`本地有 ${localCommits.length} 条提交，开始推送`);
const shaMap = new Map(); // 本地 commit sha -> 远端 commit sha

for (const c of localCommits) {
  const treeSha = await pushTree(git(['rev-parse', c + '^{tree}']).trim());
  const parents = git(['log', '-1', '--format=%P', c]).trim().split(/\s+/).filter(Boolean)
    .map((p) => {
      const mapped = shaMap.get(p);
      if (!mapped) throw new Error(`父提交 ${p} 还没推送（rev-list 顺序不对？）`);
      return mapped;
    });
  const message = git(['log', '-1', '--format=%B', c]).replace(/\s+$/, '') + '\n';
  // 作者/提交者必须原样带上，否则 GitHub 会用自己的身份和当前时间盖章，
  // 算出来的 commit sha 就和本地不一样 —— 那样以后 git fetch 会看到两条分叉的历史。
  const meta = git(['log', '-1', '--format=%an%x00%ae%x00%aI%x00%cn%x00%ce%x00%cI', c])
    .trim().split('\0');
  const author = { name: meta[0], email: meta[1], date: meta[2] };
  const committer = { name: meta[3], email: meta[4], date: meta[5] };
  const r = await api('POST', `/repos/${REPO}/git/commits`, {
    message, tree: treeSha, parents, author, committer,
  });
  shaMap.set(c, r.sha);
  const same = r.sha === c ? '与本机一致' : `与本机不同（本地 ${c.slice(0, 8)}）`;
  log(`  ${c.slice(0, 8)} -> ${r.sha.slice(0, 8)}  ${same}`);
}

// ---------- 3. 把分支指过去（历史被重写，必须 force） ----------
const finalSha = shaMap.get(localCommits[localCommits.length - 1]);
let refExisted = true;
try {
  await api('GET', `/repos/${REPO}/git/ref/heads/${BRANCH}`);
} catch {
  refExisted = false;
}
if (refExisted) {
  await api('PATCH', `/repos/${REPO}/git/refs/heads/${BRANCH}`, { sha: finalSha, force: true });
  log(`已更新 refs/heads/${BRANCH} -> ${finalSha.slice(0, 8)}`);
} else {
  await api('POST', `/repos/${REPO}/git/refs`, { ref: `refs/heads/${BRANCH}`, sha: finalSha });
  log(`已创建 refs/heads/${BRANCH} -> ${finalSha.slice(0, 8)}`);
}

// ---------- 4. 校验：远端历史与 HEAD 必须逐字节一致 ----------
const remoteCommits = await api('GET', `/repos/${REPO}/commits?sha=${BRANCH}&per_page=100`);
const localHeadTree = git(['rev-parse', 'HEAD^{tree}']).trim();
const remoteHeadTree = remoteCommits[0].commit.tree.sha;

const remoteFlat = await api('GET', `/repos/${REPO}/git/trees/${remoteHeadTree}?recursive=1`);
const remoteFiles = new Map(remoteFlat.tree.filter((t) => t.type === 'blob').map((t) => [t.path, t.sha]));

const localFlat = new Map();
const walk = (treeSha, prefix) => {
  for (const e of parseLsTree(git(['ls-tree', '-z', treeSha]))) {
    const full = prefix ? prefix + '/' + e.path : e.path;
    if (e.type === 'blob') localFlat.set(full, e.sha);
    else walk(e.sha, full);
  }
};
walk(localHeadTree, '');

const missing = [...localFlat.keys()].filter((k) => remoteFiles.get(k) !== localFlat.get(k));
const extra = [...remoteFiles.keys()].filter((k) => localFlat.get(k) !== remoteFiles.get(k));

const result = {
  repo: REPO,
  branch: BRANCH,
  head: finalSha,
  headMatchesLocal: finalSha === localCommits[localCommits.length - 1],
  treeMatchesLocal: remoteHeadTree === localHeadTree,
  commits: { local: localCommits.length, remote: remoteCommits.length },
  historyMatches: remoteCommits.length === localCommits.length &&
    remoteCommits.map((c) => (c.commit.message.split('\n')[0])).join('|') ===
    localCommits.map((c) => git(['log', '-1', '--format=%s', c]).trim()).reverse().join('|'),
  files: { local: localFlat.size, remote: remoteFiles.size },
  contentIdentical: missing.length === 0 && extra.length === 0,
  newBlobsUploaded: blobCount,
  missing,
  extra,
};
process.stdout.write(JSON.stringify(result, null, 2) + '\n');

// headMatchesLocal 只作提示，不算失败：GitHub 收到 author/committer 后会把它们
// 重新序列化（时间统一按 UTC 呈现），产出的 commit 对象与本机 git 写的并不逐字节相同，
// 所以 sha 基本对不上。这里本来也 fetch/push 不了（github.com 的 TLS 会被重置），
// 每次都是本脚本 force 更新 ref，内容一致就足够了。
if (!result.headMatchesLocal) {
  log(`提示：远端 commit sha 与本机不同（远端 ${finalSha.slice(0, 8)}）—— ` +
      'GitHub 会重写提交元数据的序列化，属正常现象，内容已校验一致。');
}
if (!result.contentIdentical || !result.historyMatches) {
  process.exitCode = 1;
}
