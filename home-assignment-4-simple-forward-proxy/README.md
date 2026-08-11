# Home Assignment 4: HTTP Forward Proxy

A GET-only forward proxy. Both levels: no framework, no dependencies, no build tool.

```bash
javac ProxyServer.java
java ProxyServer
```

Listens on `127.0.0.1:43210`.

```bash
curl "http://httpbin.org/uuid" -x 127.0.0.1:43210
curl "http://httpbin.org/image/png" -x 127.0.0.1:43210 -o my_image.png
```

## What a proxy has to get right

An ordinary server is asked for a path. A proxy is asked for a **whole URL**:

```
direct    GET /uuid HTTP/1.1              Host: httpbin.org
proxied   GET http://httpbin.org/uuid HTTP/1.1
```

That difference is the entire job. The client cannot reach the destination itself, so it hands
over the full address and the proxy makes the request on its behalf. Three things follow, and
they are what the rest of this document is about:

1. **The destination comes from the request line**, not from configuration.
2. **The client's headers belong to the destination**, not to the proxy — with the exception of
   the ones that describe the client-to-proxy connection itself.
3. **The proxy's own opinions do not belong in the response.** Whatever the destination said,
   status and headers and bytes, is what the client gets.

## How it works

```
client  ──GET http://host/path──▶  proxy  ──GET /path──▶  destination
                                     │
        ◀── status + headers ────────┤ ◀── status + headers ──
        ◀── body, chunk by chunk ────┘ ◀── body ─────────────
```

| Step | Takes in | Gives out |
|---|---|---|
| **Validate** | the request line | the destination URI, or a rejection |
| **Build** | the client's headers | an upstream request minus the hop-by-hop ones |
| **Send** | the upstream request | the destination's status, headers, and an open body stream |
| **Relay** | that response | the same status and headers, then the body a chunk at a time |

### Validate

Two checks, and both map to a status that names the real problem.

**The method must be GET.** Anything else is `501 Not Implemented`, not `405`. The difference is
who the statement is about: `405` says the method is wrong *for that resource*, which is a claim
about the destination — and this proxy never asked it. `501` says this server does not implement
the method, which is the only thing actually known.

**The target must be an absolute `http://` URI.** A bare path means someone pointed a normal HTTP
client at this port, so there is no destination to derive — `400`. An `https://` target is
`501`: HTTPS through a proxy is a `CONNECT` tunnel, not a forwarded GET.

### Build

Every client header is copied to the upstream request except those in one list:

```
connection   keep-alive   proxy-authenticate   proxy-authorization
te           trailer      transfer-encoding    upgrade
```

**Why these are excluded.** They describe *this connection* rather than the message being
carried, so they are meaningful only between two adjacent parties. `Connection: keep-alive` is an
agreement between the client and the proxy about their own socket; passing it on would make a
promise to the destination on someone else's behalf. The term for this is **hop-by-hop**, as
opposed to end-to-end headers like `Accept` or `Cookie`, which are addressed to the destination
and travel untouched.

Two additions to the assignment's list:

- **`Proxy-Connection`** — non-standard, but curl sends it on every proxied request, and it is
  hop-by-hop by exactly the same reasoning as `Connection`.
- **`Content-Length`** — the request payload is empty by assumption, so there is nothing to
  describe. On the response side it is excluded for a different reason: the length is not a
  header this server writes but an argument it passes (see *Framing* below), so forwarding it
  would declare the length twice.

Verified by pointing the proxy at a destination that prints the raw bytes it received:

```
curl -x 127.0.0.1:43210 -H "X-Custom: forward-me" -H "Cookie: a=1" -H "Cookie: b=2" \
     -H "Connection: keep-alive" -H "TE: trailers" -H "Trailer: X-T" -H "Upgrade: h2c" \
     -H "Proxy-Authorization: secret" -H "Keep-Alive: timeout=5" -H "Accept-Language: he-IL" \
     "http://127.0.0.1:43220/some/path?q=1"
```

```
GET /some/path?q=1 HTTP/1.1        ← origin-form: the absolute URI is for the proxy, not the destination
Accept: */*
Accept-language: he-IL
Host: 127.0.0.1:43220
User-agent: curl/7.81.0
X-custom: forward-me
Cookie: a=1; b=2
```

All eight hop-by-hop headers gone, everything else through, and no `Upgrade` or `HTTP2-Settings`
of the proxy's own invention.

### Send

One `HttpClient` for the whole proxy, because it owns the connection pool — a client per request
would open a fresh TCP connection every time and never reuse one. Three settings are deliberate.

**HTTP/1.1 is pinned.** Left at its default, `HttpClient` advertises an upgrade to h2c on every
cleartext request, which adds `Upgrade` and `HTTP2-Settings` headers the client never sent — and
`Upgrade` is a header this proxy is specifically told not to forward.

**Redirects are not followed** (the default, kept on purpose). A proxy hands the `3xx` back and
lets the client decide. Following it would return a body from a URL the client never asked for,
under a status saying otherwise.

**Only the connect timeout is set**, at 10 seconds. A request timeout would run against the whole
response, and killing a large but healthy download part way through is worse than waiting for it.

#### Forwarding `Host`

`HttpClient` refuses a caller-supplied `Host` — it is on a *restricted header* list, meaning a
list of headers the client insists on controlling itself, because an ordinary application has no
business overriding them. A proxy does: forwarding the client's headers is the whole job. The
restriction is lifted for `Host` alone:

```java
System.setProperty("jdk.httpclient.allowRestrictedHeaders", "host");
```

**This must run before any `java.net.http` class initialises** — the permitted set is read once,
in a static initialiser — which is why it is the first statement in `main` rather than sitting
next to the client it configures.

In practice the regenerated `Host` and the forwarded one are usually identical, since both derive
from the same URL. They differ only when a client deliberately sends a `Host` that disagrees with
its own request line, and forwarding it is the more faithful reading of "every request header".

#### When the destination cannot be reached

These are not this proxy failing; they are this proxy reporting that the destination could not be
reached. `502` and `504` say that, where `500` would blame the wrong machine.

| Condition | Status |
|---|---|
| Host does not resolve | 502 |
| Connection refused | 502 |
| Any other upstream IO failure | 502 |
| Timed out connecting | 504 |

**Failures are identified by walking the cause chain, not by catching a type.** A failed lookup
arrives as a `ConnectException` with the real reason buried two levels down, so a `catch` clause
on that reason never runs — it is only ever a cause, never the throwable:

```
java.net.ConnectException : null
java.net.ConnectException : null
java.nio.channels.UnresolvedAddressException : null
```

Note *which* reason. `HttpClient`'s NIO path reports `UnresolvedAddressException`, which is not
even an `IOException`; `UnknownHostException` is what the blocking path throws. Both are checked,
because testing only the familiar one silently mislabels every DNS failure as a refused
connection — which points debugging at the wrong machine. The first version of this code did
exactly that, and reported `Cannot connect to ... - null`.

### Relay

The destination's status and headers go back to the client with the same hop-by-hop list removed,
then the body is copied.

#### Framing

`HttpServer` takes the response length as a single argument whose three values are not sizes but
modes:

| Value | Meaning |
|---|---|
| positive | declare that `Content-Length` |
| `0` | use chunked encoding, length unknown |
| `-1` | no body at all |

The unintuitive one is `0`, which reads like "empty" and means the opposite. So a destination
that declared a length keeps it; one that did not — it was chunked, or it marks the end by
closing the connection — is re-chunked by this server. Either way **the length is never obtained
by reading the body first**, which is what would defeat the point of level 2.

`1xx`, `204` and `304` are defined to carry no body and get `-1`. A client stops reading after
the headers on all three, so announcing a body that never arrives would leave it waiting.

## Streaming (level 2)

The body is read and written 16KB at a time — comfortably over the 1024 the assignment asks for,
and twice `InputStream.transferTo`'s built-in 8KB. Nothing accumulates: a chunk is read, written,
and **flushed** before the next is asked for.

The flush is what makes that true rather than merely possible. Without it the chunks pile up in
the response stream's own buffer and leave in batches — the same buffering this level exists to
avoid, just moved one layer down.

Two measurements, both against a variant identical except for a buffering
`BodyHandlers.ofByteArray()` in place of `ofInputStream()`.

**Time to first byte.** A destination that emits 10 × 100KB with 200ms gaps, so the body takes
~2s to finish:

```
direct    first byte 0.075s   total 1.880s   1024000 bytes
proxied   first byte 0.006s   total 1.813s   1024000 bytes
```

First byte at 6ms against a transfer that runs for 1.8s. A proxy that read the response before
answering could not produce that number — its first byte cannot precede the destination's last.

**Memory.** A 100MB response through a proxy given a 32MB heap:

| | status | delivered | `OutOfMemoryError` |
|---|---|---|---|
| streaming | 200 | 104,857,600 bytes | 0 |
| buffering | — | 0 bytes | 2 |

The streaming proxy relays a body three times the size of its entire heap, because it never holds
more than 16KB of it.

**Integrity.** Both image URLs fetched directly and through the proxy are byte-identical, and
still valid images:

```
IDENTICAL  496997 bytes  pngs-img-arena.png    PNG image data, 736 x 2026, 8-bit/color RGB, interlaced
IDENTICAL    5783 bytes  Logo_JPEG_Jubilaeum_33_small.png
```

Eight concurrent 497KB transfers: 8/8 byte-identical.

## Errors and logging

A client hanging up mid-download is **routine** — every `curl -m` timeout and every Ctrl-C does
it — so it costs one line:

```
relay stopped http://127.0.0.1:43221/ - after 524204 bytes, client or destination went away (IOException: Broken pipe)
```

It is a type of its own (`RelayFailure`) rather than a `ProxyException` because by that point
there is no status left to send: the response was committed the moment the status line went out.
All that remains is the log entry, which is exactly why it must not be reported as a fault. The
first version let it reach the generic handler and printed a 20-line stack trace per abandoned
download, which would bury a real failure.

The message names both ends rather than picking one. A failed write cannot distinguish a client
that hung up from a destination that died, and once the response is committed neither can be
reported nor repaired — so it says so instead of guessing.

Everything else keeps the split from assignment 3: a `ProxyException` carries the status the
client should get and logs one line; anything else is a bug in this proxy and gets a full stack.

## Probe suite

```bash
P=127.0.0.1:43210
curl -s -m 25 -o /dev/null -w "GET png                 -> %{http_code}\n" -x $P http://sylvana.net/jpegcrop/Logo_JPEG_Jubilaeum_33_small.png
curl -s -m 40 -o /dev/null -w "GET big png             -> %{http_code}\n" -x $P http://libpng.org/pub/png/img_png/pngs-img-arena.png
curl -s -m 25 -o /dev/null -w "GET httpbin/status/404  -> %{http_code}\n" -x $P http://httpbin.org/status/404
curl -s -m 25 -o /dev/null -w "POST                    -> %{http_code}\n" -X POST -x $P http://httpbin.org/uuid
curl -s -m 25 -o /dev/null -w "origin-form (no -x)     -> %{http_code}\n" http://127.0.0.1:43210/nope
curl -s -m 25 -o /dev/null -w "unresolvable host       -> %{http_code}\n" -x $P http://no-such-host-xyz.invalid/
curl -s -m 25 -o /dev/null -w "connection refused      -> %{http_code}\n" -x $P http://127.0.0.1:43299/
```

Expected: `200 200 404 501 400 502 502`.

A printed `000` is not a status — it means no HTTP response arrived at all.

**`httpbin.org` was intermittently returning `503` and timing out** while this was being tested,
directly as well as through the proxy, so `status/404` above may come back `503`. That is the
proxy doing its job: an unhealthy destination's status is forwarded, not replaced. The two image
hosts were reliable throughout and are the better test targets.

## Notes on the implementation

**No dependencies.** `com.sun.net.httpserver` handles the client side and `java.net.http.HttpClient`
the destination side; both ship with the JDK, so `javac ProxyServer.java` is the whole build.
Between them they handle request parsing, chunked encoding and connection reuse — a raw-socket
proxy would have to dechunk the destination's body by hand, since `Transfer-Encoding` is
hop-by-hop and cannot simply be passed through.

**Absolute URIs route to `/`.** `HttpServer` matches contexts on the request URI's path, so a
context registered at `/` catches every destination path, and `getRequestURI()` hands back the
full absolute URI with its query intact. Confirmed before anything else was written, because the
whole design rests on it.

**Virtual threads.** Without an executor `HttpServer` serves one request at a time, and a proxy is
almost entirely blocked waiting on an upstream socket — which is what virtual threads are for.

**Repeated headers keep all their values, in order.** Collapsing them would change the meaning of
a repeated `Accept` or `Cookie`.

**Logging is `System.err`.** A real deployment would use a logging framework; that would mean a
dependency and a build tool, which is the trade-off this project deliberately avoided.

## Known limitations

- **No `CONNECT`**, so no HTTPS tunnelling. Out of scope, but it is the obvious next level.
- **Only GET.** Adding the other bodyless methods would be mechanical; anything with a request
  body would need the payload streamed upstream too.
- **`Connection`'s listed values are not stripped.** RFC 7230 says a `Connection: X` header makes
  `X` hop-by-hop for that message as well. This implementation removes the fixed list the
  assignment gives and no more, so an unusual client could have a header forwarded that should
  have been dropped.
- **No `Via` header**, per the assignment.
- **Header names are re-cased** by `HttpServer` — `User-Agent` arrives as `User-agent` and is
  forwarded that way. HTTP header names are case-insensitive by definition, so this changes
  nothing semantically, but a destination that compares them by hand would notice.
- **`HttpServer` rejects some malformed requests before the handler runs**, e.g. an unparseable
  `Transfer-Encoding` value gets its own `501` with an HTML body this proxy never wrote.
- **No cache and no access log.** Every request goes to the destination.
