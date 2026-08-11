# Home Assignment 3: HTTP File Server

Stores binary blobs on the filesystem along with a subset of their request headers, and
serves them back. All three levels: no framework, no dependencies, no build tool.

```bash
javac BlobServer.java
java BlobServer
```

Listens on port 8000. Storage lives under `storage/`.

## API

| Method | Path | Success | Notes |
|---|---|---|---|
| `POST` | `/blobs/{id}` | 200 | Upsert. 200 rather than 201, which would claim creation on every overwrite |
| `GET` | `/blobs/{id}` | 200 | Returns the payload and the stored headers |
| `DELETE` | `/blobs/{id}` | 204 | Idempotent; the spec excuses 404 here |

Stored headers are `Content-Type` and anything starting with `x-rebase-`, matched
case-insensitively.

### Errors

| Condition | Status | Why that code |
|---|---|---|
| Missing `Content-Length` | 411 | Length Required is defined for exactly this |
| Payload over `MAX_PAYLOAD_LENGTH` | 413 | Content Too Large |
| Header name / value too long, or too many stored headers | 431 | Request Header Fields Too Large |
| Invalid or oversized id | 400 | |
| Disk quota or blob count exhausted | 507 | Insufficient Storage |
| Unknown blob on `GET` | 404 | |
| Method other than the three above | 405 | |

## How it works

Think of it as a cloakroom. You hand over some bytes with a ticket name, and you get them
back — along with the labels you attached — when you show the ticket again.

### What happens during a POST

```
request  →  route  →  check the id  →  check the size  →  collect the labels
                                                                  ↓
response  ←  reserve space  →  write to storage/tmp/  →  move into place
```

1. **Route** — the method decides which handler runs.
2. **Check the id** — is the ticket name legal, and not something dangerous?
3. **Check the size** — is `Content-Length` present, and under the limit?
4. **Collect the labels** — pick out `Content-Type` and `x-rebase-*`, check their sizes.
5. **Reserve space** — claim room in the quota *before* reading a single byte.
6. **Write** — stream the body into a temp file, labels first, then payload.
7. **Move into place** — one rename, and the blob becomes visible.

Steps 2–4 all happen *before* anything is written, so a rejected request never leaves a file
behind. Step 5 happens before step 6 so that two uploads racing each other cannot both be
told there is room.

### What happens during a GET

```
check the id  →  does the file exist?  →  read 9 bytes  →  read the labels  →  stream the rest
```

The 9-byte read tells the server how long the label block is, so it knows exactly where the
payload starts. It never has to search for it.

### The parts

| Part | Takes in | Gives out |
|---|---|---|
| **Warm-up** | the storage folder | a starting count of blobs and bytes |
| **Server** | a network connection | a request handed to the right handler |
| **Id checker** | the URL path | a safe blob id, or a rejection |
| **Size checker** | the request headers | the declared payload size, or a rejection |
| **Label collector** | the request headers | the labels worth keeping, or a rejection |
| **Bucket picker** | a blob id | the folder that id belongs in |
| **Writer** | the request body | a finished file, moved into place |
| **Quota keeper** | a size change | permission, or a refusal |
| **Reader** | a blob id | the labels and the payload |

#### Warm-up

Runs once, at startup, **before the server accepts anything**.

It throws away any leftover files in `storage/tmp/` — those are uploads that died before
finishing, so nothing refers to them. Then it walks the storage folder and counts: how many
blobs, how many bytes.

Why it must finish first: the quota keeper needs a starting number. If the server answered
requests while still counting, it could accept an upload that pushes storage over the limit.

#### Server

Listens on port 8000 and hands each request to the handler. Two settings matter.

The context is registered as `/blobs/` **with** the trailing slash, because matching is by
prefix — `/blobs` would also capture unrelated paths like `/blobsomething`.

It is given a thread pool. Without one, the server handles a single request at a time, and a
slow 10MB upload would block everyone else.

#### Id checker

The blob id comes from the URL, so it is untrusted input that is about to become a filename.
Four checks, cheapest first:

1. not empty
2. not longer than 200 characters
3. not exactly `.` or `..`
4. every character is `a-z`, `A-Z`, `0-9`, `.`, `_`, or `-`

Check 3 exists because `.` and `..` are not names — they mean "this folder" and "the folder
above". Without it, a request for `/blobs/..` would point the server at its own parent
directory. They need their own check because check 4 accepts dots, so `a..b` stays legal.

Check 4 does the rest of the work: with no `/` allowed, no id can point outside its folder.

#### Size checker

Reads `Content-Length`. Missing means the server cannot know how much is coming, so the
request is refused. Present but over the limit is refused too — before any of the body is
read, so a 100MB upload is rejected in milliseconds rather than after 100MB has arrived.

#### Label collector

Walks the request headers and keeps only `Content-Type` and anything starting with
`x-rebase-`, then checks each name and value against its length limit and counts the result.

The count is of **kept** labels, not received ones: a request with fifty headers is fine if
only three of them are ones we store.

#### Bucket picker

**The problem.** A million files in one folder makes the filesystem slow — every lookup wades
through a list of a million names.

**The fix.** Spread them across 4096 folders, roughly 245 files each. Which raises the real
question: which folder does a file go in, and how is it found again later?

**The library idea.** A library shelves books by the first letter of the title. "Matilda"
goes on shelf M, and you fetch it by walking straight to M rather than searching the building.

Doing that with the first letter of a blob id would not work, because names are lumpy — many
start with `a`, almost none with `q`, so some folders would overflow while others sat empty.

**So the name is scrambled first.** SHA-256 turns text into a long jumbled number:

```
"hello"    →  2cf24dba5fb0a30e26e83b2ac5b9e29e...
"cat.png"  →  b94f5ce0690d...
"report"   →  845e91831319...
"backup"   →  54d00d867758...
```

The first three characters name the folder:

```
hello      →  storage/blobs/2cf/hello
cat.png    →  storage/blobs/b94/cat.png
report     →  storage/blobs/845/report
backup     →  storage/blobs/54d/backup
```

Two properties make this work:

- **The same name always gives the same jumble.** `hello` is `2cf24dba...` on any machine,
  every time. That is what makes the folder findable again.
- **Similar names give completely different jumbles.** `hello` lands in `2cf`, `hello2` in
  `872`. One extra character, an unrelated folder. That is what spreads files evenly instead
  of clumping them.

**Where it is used.** `bucketFor` does the calculation and `pathFor` glues the result into a
full path. All three handlers call `pathFor` — POST to know where to write, GET to know where
to look, DELETE to know what to remove. One calculation, three users.

**No index anywhere.** Nothing records that `hello` lives in `2cf`. The server simply redoes
the sum: scramble `hello`, get `2cf`, look there.

That is the point of using a calculation rather than a lookup table. A table could be lost,
fall out of sync, or need updating on every write — and it would itself grow into a large
thing to search. Instead there is nothing to maintain, and locating a blob costs the same
whether five or a million are stored.

#### Writer

Streams the request body into a temp file in small chunks, so a 10MB upload costs a small
buffer of memory rather than 10MB. It writes the length prefix, then the labels, then the
payload — all into that one temp file.

When the file is complete it is **renamed** into its final folder. A rename on the same disk
is instant and indivisible, which is what guarantees a reader never sees a half-written blob.

If anything fails part way, the temp file is deleted and the reserved quota is handed back.

#### Quota keeper

Holds two running numbers: bytes used and blobs stored. Every upload asks permission first,
and every delete gives space back.

It reserves rather than checks. The difference matters: if it merely *checked*, ten uploads
could all look at a nearly-full store, all be told there is room, and all write. By claiming
the space up front, only the ones that genuinely fit are allowed to proceed.

Because the declared size is only a promise, the actual file size is compared against the
reservation before the blob is committed, and any difference is returned.

#### Reader

Opens the file, reads 9 bytes to learn the label block's length, reads the labels, and then
copies the remaining bytes straight to the client in small chunks.

Nothing is held in memory, so many large downloads can run at once on a small heap.

### In, out, and on disk

- **In:** an HTTP request — a method, a ticket name, headers, and bytes.
- **Out:** an HTTP response — a status code, the stored labels, and the bytes.
- **On disk:** one file per blob at `storage/blobs/<folder>/<name>`, plus `storage/tmp/`
  for uploads still in progress.

## Storage

One file per blob, self-describing, in a folder derived from the id:

```
storage/blobs/fd8/hdr          89 bytes
storage/tmp/                   uploads in flight

00000066\n                     header block length, 8 ASCII digits
Content-type: text/plain
X-rebase-owner: ran
X-rebase-project: a3
hello-from-Ran                 payload
```

**One file, not two.** The first design kept payload and metadata in sibling trees, which
made the quota trivially the size of the data tree. It also meant a POST committed with two
renames, and a crash between them left a blob whose headers and payload disagreed. Merging
them makes the commit a single `Files.move(..., ATOMIC_MOVE)`, so headers and payload become
visible in the same instant because they are the same bytes. The cost is that the quota now
counts header bytes too - which is arguably the more faithful reading of "overall disk
space", and keeps warm-up to one `stat` per blob rather than opening every file to subtract
its header.

**The length prefix is fixed width** so the payload's offset is known after one 9-byte read.
A delimiter would have to be scanned for, and a header value could contain it. GET reads the
prefix, reads that many header bytes, and the stream is then sitting on the first payload
byte - `transferTo` does the rest with no buffering and no seeking.

## Bucketing (level 3)

Three hex characters of the id's SHA-256 name its folder: 4096 folders, so a full store of
1,000,000 blobs averages 245 each, against the cap of 1000.

The folder is a pure function of the id, so GET and DELETE recompute it with no index to
consult and nothing to keep in sync.

**Why a digest and not `String.hashCode`.** Measured over 1,000,000 ids in four shapes:

| id shape | `hashCode` high 12 bits | `hashCode` low 12 bits | SHA-256 first 12 bits |
|---|---|---|---|
| `blob{n}` | **max 14638**, 4001 folders empty | max 450 | max 297 |
| `file{n:08}.dat` | max 269 | max 444 | max 307 |
| hash-like | max 302 | max 293 | max 311 |
| common prefix | **max 14400**, 3999 folders empty | max 445 | max 317 |

The upper bits collapse on sequential and common-prefix ids, which are the ids real clients
generate. The lower bits behave, but colliding `hashCode` inputs are trivial to construct, so
a client could aim at one folder deliberately - and folder occupancy is a hard cap, not a
preference. A digest costs a few microseconds against a file write that costs more.

Loading 5000 blobs over a live connection put them in 2883 folders, 7 in the fullest.

## Streaming (level 2)

Neither direction holds a payload in memory. POST copies the request body to a temp file in
fixed-size chunks; GET positions the file stream past the header block and copies straight to
the response.

Verified against a variant identical except for a buffering `readAllBytes` in GET. Eight
concurrent 10MB transfers, 32MB heap:

| | uploads | downloads | integrity | OutOfMemoryError |
|---|---|---|---|---|
| streaming | 8 × 200 | 8 × 200 | 8/8 byte-identical | 0 |
| buffering | 8 × 200 | 2 × 200, **6 × dropped** | — | **8** |

`BoundedInputStream` enforces `MAX_PAYLOAD_LENGTH` against the bytes that actually arrive,
not the declared length. It cannot currently fire, since `HttpServer` caps the body stream at
`Content-Length` and requests without that header are already rejected with 411 - it is
defence in depth, not a hole being closed.

## Consistency (level 2)

**A blob is never partially visible.** Uploads are written to `storage/tmp/` and committed
with one atomic rename. A reader sees the previous blob or the new one, never a mixture.

Tested with 200 overwrites of a 1MB blob, alternating two versions with different headers and
different payloads, while three readers hammered it:

```
writer: 200 overwrites
reader 1: 250 good reads, 0 TORN
reader 2: 250 good reads, 0 TORN
reader 3: 250 good reads, 0 TORN
```

The detector was then checked against a deliberately mismatched blob - headers claiming
version A over a B payload - and reported `TORN`, so the zero above is a real zero rather
than a test that cannot fail.

**Warm-up.** Before the socket is created, the server discards uncommitted uploads, prunes
emptied bucket folders, and totals what remains. Nothing is served while the figures are
unknown, and the totals describe storage after the wreckage has been swept up:

```
Discarded 1 upload(s) that never committed
Warm-up complete: 6 blobs, 1048851 bytes used of 1073741824
```

There is no half-written blob to find and no orphaned metadata to reconcile - both were
possible only under the two-file design.

## Quota

Two running totals, seeded by the warm-up scan and updated on every POST and DELETE. Both are
`synchronized`: the spec rules out concurrent requests to the same id, but not concurrent
requests overall, and these totals are shared by all of them.

Space is **reserved before the body is read**, not checked and then written. A plain
check-then-write lets several POSTs each see room and together exceed the limit. Against a
100-byte quota, 40 simultaneous 10-byte POSTs:

```
10 × 200
30 × 507
bytes on disk: 100     exactly the limit, no overshoot
```

The declared `Content-Length` is only a reservation; the committed file size is the truth, and
the difference is handed back before the rename. A failed upload returns its whole
reservation, so a broken request cannot leak quota.

Overwrites charge only the difference in file size, and deletes give it back. Boundary tested
in both directions - shrinking a blob frees space for the next one, and an overwrite that
would exceed the quota is rejected while the existing blob survives.

## Testing with curl

```bash
curl -i -X POST --data-binary "hello-from-Ran" localhost:8000/blobs/test2
```

**`-i`** shows the response headers, which is the only way to see the stored ones.

**`--data-binary`**, never `-d`. Plain `-d` strips carriage returns and newlines, a leftover
from HTML form submissions, and silently corrupts binary payloads. `@file` reads the body
from a file, `@-` from stdin.

**`-X POST` is redundant** here - `--data-binary` already implies POST. Reserve `-X` for
bodyless methods like `DELETE`.

### curl rewrites requests

Three of its defaults have changed a test result in this project.

**It normalises the path.** `.` and `..` are resolved before a byte goes out, so
`/blobs/..` is transmitted as `/`:

```
curl localhost:8000/blobs/..                 →  GET / HTTP/1.1
curl --path-as-is localhost:8000/blobs/..    →  GET /blobs/.. HTTP/1.1
```

Without the flag the request never reaches the handler - it lands on `/`, where no context is
registered, and `HttpServer` answers with its own page. The `text/html` body is the tell,
since this server never sends that. **A 404 obtained this way is not evidence the server
rejected anything.** Either pass `--path-as-is` or percent-encode so there is nothing left to
normalise: `%2F` for `/`, `%2E` for `.`.

**It adds `Content-Length`**, computed from the payload. That is why an ordinary POST never
exercises the 411 path; reaching it needs chunked encoding.

**It adds `Content-Type: application/x-www-form-urlencoded`** to every `--data*` request. The
server stores that verbatim, so a blob posted without an explicit type is recorded as
form-encoded no matter what the bytes are. Set it when the stored value matters.

To see what was actually sent:

```bash
nc -l -p 8124 &
curl -s -m 2 localhost:8124/blobs/..
```

### Probe suite

```bash
curl -s -m 5 -o /dev/null -w "POST new             -> %{http_code}\n" -X POST --data-binary "hello" localhost:8000/blobs/t1
curl -s -m 5 -o /dev/null -w "GET  existing        -> %{http_code}\n" localhost:8000/blobs/t1
curl -s -m 5 -o /dev/null -w "GET  missing         -> %{http_code}\n" localhost:8000/blobs/nope
curl -s -m 5 -o /dev/null -w "DELETE twice         -> %{http_code}\n" -X DELETE localhost:8000/blobs/t1
curl -s -m 5 -o /dev/null -w "PUT                  -> %{http_code}\n" -X PUT localhost:8000/blobs/t1
curl -s -m 5 -o /dev/null -w "empty id             -> %{http_code}\n" localhost:8000/blobs/
curl -s -m 5 -o /dev/null -w "/blobs/..            -> %{http_code}\n" --path-as-is localhost:8000/blobs/..
curl -s -m 5 -o /dev/null -w "traversal            -> %{http_code}\n" "localhost:8000/blobs/..%2F..%2F..%2Fsecret"
curl -s -m 5 -o /dev/null -w "201-char id          -> %{http_code}\n" "localhost:8000/blobs/$(printf 'a%.0s' $(seq 201))"
curl -s -m 5 -o /dev/null -w "hebrew id            -> %{http_code}\n" "localhost:8000/blobs/%D7%A8%D7%9F"
curl -s -m 5 -o /dev/null -w "chunked no CL        -> %{http_code}\n" -X POST -H "Transfer-Encoding: chunked" --data-binary "x" localhost:8000/blobs/c1
curl -s -m 5 -o /dev/null -w "header key 34 chars  -> %{http_code}\n" -X POST -H "x-rebase-kkkkkkkkkkkkkkkkkkkkkkkkk: v" --data-binary "x" localhost:8000/blobs/h1
```

Expected: `200 200 404 204 405 400 400 400 400 400 411 431`.

`-w "%{http_code}"` with `-o /dev/null` reduces each probe to one scannable line. A printed
`000` is not a status - it means no HTTP response arrived at all, which points at an exception
escaping the handler.

Limits are tested at the boundary in both directions: a 200-character id is accepted and a
201-character one rejected, a 400-character header value accepted and 401 rejected, 20 stored
headers accepted and 22 rejected. A request with 50 headers is accepted when only one of them
is stored, since the limit counts stored headers rather than received ones.

## Notes on the implementation

**No dependencies.** `com.sun.net.httpserver` ships with the JDK, so `javac BlobServer.java`
is the whole build. It also hands over raw streams, which is what levels 2 and 3 need. The
cost is writing the routing and status handling by hand.

**Virtual threads.** Without an executor `HttpServer` serves one request at a time. The
workload is almost entirely blocked on file IO, which is what virtual threads are for.

**Errors carry their status.** A `BlobException` holds the code the client should get, so
validation deep in the call stack rejects a request without every caller in between
forwarding an error. `handle` catches it and one line goes to the log. Anything else is a bug
in the server and gets a stack trace - keeping them apart stops a client looping on bad ids
from burying real failures.

**Ids are validated on the decoded path.** `URI.getPath()` percent-decodes before the handler
sees it, so `%2F` arrives as a real `/`. Validation runs on that value because it is what
would reach the filesystem. `.` and `..` are rejected by exact match, not by `contains` -
they are dangerous because they *are* directory names, and `a..b` is a perfectly legal id.

**ASCII ranges, not `Character.isLetterOrDigit`**, which returns true for Hebrew, accented
Latin and Arabic-Indic digits.

**Logging is `System.err`.** A real deployment would use a logging framework; that would mean
a dependency and a build tool, which is the trade-off this project deliberately avoided.

## Known limitations

- **No migration from earlier on-disk layouts.** Blobs written by the two-file version would
  be invisible. `storage/` is disposable here, but a real deployment would need a migration.
- **`MB` and `GB` are read as binary units** (1024-based). The spec does not say which.
- **A header sent more than once keeps its first value** and counts once toward the limit.
- **`Content-Length` is trusted for the reservation**, though never for the stored total.
