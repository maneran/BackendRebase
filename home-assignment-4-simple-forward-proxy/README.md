# Home Assignment 4: HTTP Forward Proxy

A proxy that fetches web pages on someone else's behalf. GET only. Both levels: no framework, no
dependencies, no build tool.

```bash
javac ProxyServer.java
java ProxyServer
```

Listens on `127.0.0.1:43210`.

```bash
curl "http://httpbin.org/uuid" -x 127.0.0.1:43210
curl "http://httpbin.org/image/png" -x 127.0.0.1:43210 -o my_image.png
```

The `-x` is what tells curl "don't go to the website yourself — ask this proxy to go for you".

## What a proxy is

Think of someone who runs errands for you.

You want something from a shop, but you cannot go yourself. So you write the shop's **full
address** on a piece of paper, hand it to your errand runner, and they walk there, buy the thing,
and bring it back to you exactly as they received it.

That is the whole job. This program is the errand runner. The shop is a website.

**Why the full address matters.** When you go to a shop yourself, you already know which shop you
are standing in — you only need to say which aisle. When you send someone else, they have no idea
where to go unless you tell them. The same is true here:

```
you go yourself     GET /uuid HTTP/1.1              Host: httpbin.org
you send the proxy  GET http://httpbin.org/uuid HTTP/1.1
```

The second line contains the whole address. That single difference is what makes a request a
proxy request, and everything else in this document follows from it.

**The errand runner does not shop for themselves.** Three rules come out of that:

1. **They go where the paper says.** The destination comes from the request, never from settings.
2. **They carry your message, not their own.** Your requests to the shop are passed along
   untouched — except for notes that were meant for the runner personally, which is a distinction
   the next section is about.
3. **They bring back what they were given.** Whatever the shop said — even "we are closed" — is
   what you get. The runner does not improve it, replace it, or add opinions.

## What happens during a request

```
you  ──"go to http://host/path"──▶  proxy  ──"give me /path"──▶  website
                                      │
     ◀── the website's answer ────────┤ ◀── the answer ──
     ◀── the goods, armful by armful ─┘ ◀── the goods ───
```

1. **Check the request** — is this something the proxy can do, and where is it going?
2. **Repack it** — copy your headers, leaving out the ones meant only for the proxy.
3. **Go and ask** — make the request to the website.
4. **Bring it back** — send the website's answer to you, a piece at a time.

### The parts

| Part | Takes in | Gives out |
|---|---|---|
| **Checker** | the request | the destination address, or a refusal |
| **Repacker** | your headers | a request for the website, minus the private notes |
| **Fetcher** | that request | the website's answer and an open pipe to its contents |
| **Relay** | that answer | the same answer to you, a piece at a time |

## Checking the request

A **header** is a line of extra information attached to a request — things like which languages
you read, or what browser you are using. They travel alongside the actual message.

Two checks happen before anything else, and each refusal uses a number that names the real
problem.

**It must be a GET.** GET means "give me this thing". This proxy does nothing else, so anything
else is refused with **501**, which means *this server does not know how to do that*. The
tempting alternative, 405, means *that website will not allow this* — and the proxy has not asked
the website, so saying 405 would be putting words in its mouth.

**There must be a full address.** If the request only has a path (`/nope`) and no website name,
someone has pointed an ordinary browser straight at the proxy. There is no shop on the paper, so
there is nowhere to go: **400**, meaning *the request itself is wrong*.

An `https://` address is refused with 501 too. Secure websites work in a completely different way
through a proxy — the runner carries a sealed box they cannot open — and that is not built here.

## Which headers get passed on

Almost all of them. Your headers are addressed to the website, so the proxy carries them over
untouched.

Eight are left behind:

```
connection   keep-alive   proxy-authenticate   proxy-authorization
te           trailer      transfer-encoding    upgrade
```

**Why leave any behind?** Because some notes are meant for the errand runner personally, not for
the shop.

If you tell your runner *"wait for me at the door when you get back"*, that instruction is
between you and them. Repeating it to the shopkeeper would be nonsense — and worse, it would be
making a promise to the shop on someone else's behalf.

That is exactly what these eight headers are. `Connection: keep-alive` means "let's keep our own
line open" — an agreement between you and the proxy about your own connection. The website is not
part of that conversation. The technical name for such a header is **hop-by-hop**: it belongs to
one leg of the journey only. Headers like `Accept` or `Cookie` are the opposite — they are meant
for the far end and travel the whole way.

Two more are left behind, beyond the eight the assignment lists:

- **`Proxy-Connection`** — not an official header, but curl sends it every single time you use
  `-x`. It is a note for the proxy by its very name, so it stays behind for the same reason as
  `Connection`.
- **`Content-Length`** — this says how many bytes the message body is. Requests here never have a
  body, so there is nothing to measure. Coming back the other way it is left out for a different
  reason: the proxy states the length through a separate mechanism (see *Saying how long the
  answer is*), so keeping the header too would state it twice.

**Checked, not assumed.** The proxy was pointed at a fake website that prints the exact bytes it
receives:

```
curl -x 127.0.0.1:43210 -H "X-Custom: forward-me" -H "Cookie: a=1" -H "Cookie: b=2" \
     -H "Connection: keep-alive" -H "TE: trailers" -H "Trailer: X-T" -H "Upgrade: h2c" \
     -H "Proxy-Authorization: secret" -H "Keep-Alive: timeout=5" -H "Accept-Language: he-IL" \
     "http://127.0.0.1:43220/some/path?q=1"
```

What the fake website actually got:

```
GET /some/path?q=1 HTTP/1.1        ← just the path now: the full address was only for the proxy
Accept: */*
Accept-language: he-IL
Host: 127.0.0.1:43220
User-agent: curl/7.81.0
X-custom: forward-me
Cookie: a=1; b=2
```

All the private notes gone, everything else carried across, and nothing invented along the way.

## Going and asking

One connection-handler is shared by the whole proxy rather than made fresh each time. Making a
new one per request would mean dialling the website from scratch every time instead of reusing a
line that is already open.

Three settings are deliberate.

**Speak the older, simpler version of HTTP.** Left to itself, Java's web client adds a note to
every request offering to switch to a newer protocol version. That note is an `Upgrade` header —
and `Upgrade` is on the list of headers this proxy is specifically told never to send. It would
have been inventing the exact thing it was told to remove.

**Do not chase redirects.** A **redirect** is a website saying "what you want has moved, try over
there". A good errand runner brings that message back and lets you decide. Chasing it would mean
returning goods from a shop you never named, while the receipt says otherwise.

**Only limit how long to wait for the shop to open the door** — ten seconds — not how long the
shopping takes. A time limit on the whole errand would abandon a large, perfectly healthy
download halfway through.

### Passing on the `Host` header

`Host` is the header naming which website you want. Java's web client normally **refuses** to let
a program set it, because a normal program has no business claiming to be a different website.
A proxy does have that business — passing your headers on is its entire purpose — so that refusal
is lifted for this one header:

```java
System.setProperty("jdk.httpclient.allowRestrictedHeaders", "host");
```

**This has to be the very first line of the program.** Java reads that setting once, when the web
client machinery first wakes up, and never looks again. Put it lower down and it is simply
ignored — which is why it sits alone at the top instead of next to the client it configures.

## When the website cannot be reached

If the errand runner cannot find the shop, that is not the runner's fault, and the message back
should say so. These numbers do:

| What went wrong | Number | What it means |
|---|---|---|
| The website's name does not exist | 502 | *I could not get a good answer from them* |
| The website refused the connection | 502 | same |
| Anything else went wrong on the way | 502 | same |
| The website never answered the door | 504 | *they took too long* |

The wrong choice here would be 500, which means *I broke*. The proxy did not break; the
destination did, and blaming the wrong machine sends whoever is debugging to the wrong place.

**Finding the real reason takes digging.** When a website's name cannot be looked up, Java does
not report that plainly. It reports "could not connect", with the true reason buried two layers
underneath:

```
java.net.ConnectException : null
java.net.ConnectException : null
java.nio.channels.UnresolvedAddressException : null      ← the actual reason
```

So the proxy digs down through the layers instead of looking only at the top one. It also checks
for *two* different names for this failure, because Java uses different ones depending on how it
looked up the address. The first version of this code checked only the better-known name, found
nothing, and reported every unknown website as "connection refused" — pointing debugging at the
wrong problem entirely.

## Bringing the answer back

The website's answer — its number, its headers, its contents — goes straight back to you, with
the same eight private notes removed.

### Saying how long the answer is

Before sending anything, the proxy must say how much is coming. Java takes this as a single
number, and the three possible values are not really lengths at all — they are three different
modes:

| Value | What it actually means |
|---|---|
| a positive number | "expect exactly this many bytes" |
| `0` | "I don't know yet — I'll tell you when it ends" |
| `-1` | "there is no content at all" |

The trap is `0`. It reads like "empty" and means very nearly the opposite.

So: if the website said how big its answer is, that size is passed straight through. If it did
not, the proxy uses the "I'll tell you when it ends" mode. **What it never does is read the whole
answer first just to measure it** — which would throw away the entire point of level 2.

Three kinds of answer carry no content by definition, including "nothing has changed since last
time" (304). Those get `-1`. Promising content that never arrives would leave you waiting forever.

## Streaming (level 2)

**The problem.** The simple way to be an errand runner is to collect the entire shop order, load
it all into your arms, walk back, and only then hand any of it over. That works for a loaf of
bread. It falls apart for a fridge — you cannot hold it, and the person waiting sees nothing at
all until you arrive.

**What this does instead.** It carries the answer back in armfuls of 16KB. Read a piece, hand it
over, push it out the door, go back for the next piece. The proxy never holds more than one
armful, and you start receiving things while the website is still handing them over.

**Pushing it out the door is the part that matters.** Writing a piece is not the same as sending
it — left alone, the pieces pile up in a holding area and go out in big batches. That is the very
buffering this level exists to avoid, just hidden one layer deeper. So each piece is explicitly
flushed out before the next is fetched.

Both claims were measured against an otherwise identical copy of the proxy, changed in exactly
one way: it collects the whole answer before replying.

**Does it really start early?** A fake website that sends its answer in 10 slow pieces, dribbled
out over about two seconds:

```
straight to the website   first byte 0.075s   total 1.880s   1024000 bytes
through the proxy         first byte 0.006s   total 1.813s   1024000 bytes
```

The first byte arrives in 6 thousandths of a second, on a delivery that keeps going for another
1.8 seconds. A proxy that collected everything first could not possibly produce that number — its
first byte cannot arrive before the website's last one.

**Does it really stay small?** A 100MB answer, through a proxy deliberately given only 32MB of
memory to work with:

| | result | delivered | ran out of memory |
|---|---|---|---|
| this proxy (armfuls) | worked | all 104,857,600 bytes | never |
| the collecting copy | failed | 0 bytes | twice |

It carried something three times larger than all the memory it had, because it never held more
than 16KB of it at once.

**Do the goods arrive undamaged?** Both test images, fetched directly and through the proxy, are
identical byte for byte and still open as images:

```
IDENTICAL  496997 bytes  pngs-img-arena.png    PNG image data, 736 x 2026, 8-bit/color RGB, interlaced
IDENTICAL    5783 bytes  Logo_JPEG_Jubilaeum_33_small.png
```

Eight of those downloads running at the same time: 8 out of 8 identical.

## When things go wrong mid-delivery

People change their minds. Someone presses Ctrl-C, or a time limit runs out, and they walk away
while the download is still going. **This is completely normal**, so it costs exactly one line:

```
relay stopped http://127.0.0.1:43221/ - after 524204 bytes, client or destination went away (IOException: Broken pipe)
```

The first version treated it as a crash and printed twenty lines of error trace for every
abandoned download — noise that would bury a genuine problem.

It gets its own error type for a specific reason: by the time it happens, the answer's headers
have already been sent, so there is no status code left to send. There is nothing to fix and
nothing to tell anyone. All that remains is the log line, which is precisely why it must not look
like a disaster.

The message names both possibilities rather than picking one, because at that point they are
genuinely indistinguishable — a failed handover looks the same whether you walked off or the shop
did. Guessing would be pretending to know.

Everything else keeps the split from assignment 3: an expected refusal carries the number you
should get and logs one line, and anything unexpected is a real bug and gets the full trace.

## Probe suite

```bash
P=127.0.0.1:43210
curl -s -m 25 -o /dev/null -w "GET png                 -> %{http_code}\n" -x $P http://sylvana.net/jpegcrop/Logo_JPEG_Jubilaeum_33_small.png
curl -s -m 40 -o /dev/null -w "GET big png             -> %{http_code}\n" -x $P http://libpng.org/pub/png/img_png/pngs-img-arena.png
curl -s -m 25 -o /dev/null -w "GET httpbin/status/404  -> %{http_code}\n" -x $P http://httpbin.org/status/404
curl -s -m 25 -o /dev/null -w "POST                    -> %{http_code}\n" -X POST -x $P http://httpbin.org/uuid
curl -s -m 25 -o /dev/null -w "no full address (no -x) -> %{http_code}\n" http://127.0.0.1:43210/nope
curl -s -m 25 -o /dev/null -w "unresolvable host       -> %{http_code}\n" -x $P http://no-such-host-xyz.invalid/
curl -s -m 25 -o /dev/null -w "connection refused      -> %{http_code}\n" -x $P http://127.0.0.1:43299/
```

Expected: `200 200 404 501 400 502 502`.

A printed `000` is not a status code — it means no answer came back at all.

**`httpbin.org` was broken while this was being tested**, returning 503 and timing out — going to
it directly, not just through the proxy. So `status/404` above may come back as `503`. That is
the proxy doing its job correctly: rule 3 is that the website's answer comes back untouched, even
when the answer is "we are having a bad day". The two image websites worked reliably throughout
and are the better things to test with.

## Notes on the implementation

**No dependencies.** Java already includes both halves — a web server for talking to you, and a
web client for talking to websites — so `javac ProxyServer.java` is the entire build. Between
them they handle the fiddly business of splitting messages up and reassembling them. Doing this
with raw network sockets would mean reassembling the website's answer by hand, because
`Transfer-Encoding`, the header that explains how the answer was chopped up, is one of the eight
that must be removed.

**Full addresses arrive intact.** Java's built-in server sorts incoming requests by path, so one
handler registered at `/` catches every possible destination, and the full address is still there
to read. This was confirmed by experiment before a single line of the proxy was written, because
the entire design depends on it being true.

**Virtual threads.** Without them the server handles one request at a time. A proxy spends nearly
all its life waiting for a website to reply, which is exactly the situation virtual threads exist
for.

**A header sent twice keeps both values, in order.** Merging them would change the meaning of a
repeated `Accept` or `Cookie`.

**Logging goes to the error stream.** A real deployment would use a proper logging library, but
that means a dependency and a build tool — the trade-off this project deliberately avoids.

## Known limitations

- **No HTTPS.** Secure websites need a completely different mechanism through a proxy, where the
  proxy relays a sealed conversation it cannot read. Out of scope here, and the obvious next step.
- **GET only.** The other request types that have no body would be straightforward to add;
  anything that sends data would need that data streamed onward too.
- **`Connection` can name extra headers, and those are not removed.** The rules say a header
  `Connection: X` also makes `X` private to that one leg of the journey. This removes the fixed
  list the assignment gives and nothing more, so an unusual client could get a header passed on
  that should have been left behind.
- **No `Via` header**, as the assignment allows.
- **Header names come back slightly re-capitalised** — `User-Agent` becomes `User-agent`. Header
  names officially ignore capitalisation, so nothing actually changes, but a website comparing
  them letter by letter would notice.
- **Some malformed requests are rejected by Java's server before this code ever sees them**, and
  those refusals come with an HTML page this proxy did not write.
- **Nothing is remembered between requests.** No cache, so every single request really does go to
  the website.
