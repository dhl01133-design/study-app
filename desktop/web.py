"""Tiny HTTP client on the standard library (urllib), replacing `requests` (~11 MB of Python memory).

Same idea as lite/Net.cs: one function, status codes returned instead of raised, and redirects that
never carry the Authorization header to another host (GitHub release downloads redirect to storage).
"""
import json
import ssl
import urllib.error
import urllib.parse
import urllib.request

_CTX = ssl.create_default_context()   # Windows certificate store, like a browser


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **k):
        return None


_OPENER = urllib.request.build_opener(urllib.request.HTTPSHandler(context=_CTX), _NoRedirect)


class Response:
    def __init__(self, status, headers, body):
        self.status_code, self.headers, self.content = status, headers, body

    def json(self):
        return json.loads(self.content.decode("utf-8"))


def request(url, method="GET", headers=None, params=None, timeout=30, stream_to=None, _hops=0):
    if params:
        url += ("&" if "?" in url else "?") + urllib.parse.urlencode(params)
    req = urllib.request.Request(url, method=method, headers={"User-Agent": "StudyOverlay", **(headers or {})},
                                 data=b"" if method == "POST" else None)
    try:
        resp = _OPENER.open(req, timeout=timeout)
    except urllib.error.HTTPError as e:   # 3xx (redirects are not followed automatically), 4xx, 5xx
        resp = e
    status = resp.status if hasattr(resp, "status") else resp.code
    if status in (301, 302, 303, 307, 308) and _hops < 5 and resp.headers.get("Location"):
        loc = urllib.parse.urljoin(url, resp.headers["Location"])
        same_host = urllib.parse.urlsplit(loc).netloc == urllib.parse.urlsplit(url).netloc
        h = {k: v for k, v in (headers or {}).items() if same_host or k.lower() != "authorization"}
        resp.close()
        return request(loc, "GET" if status == 303 else method, h, None, timeout, stream_to, _hops + 1)
    try:
        if stream_to is not None and status < 300:
            while True:
                chunk = resp.read(1 << 16)
                if not chunk:
                    break
                stream_to.write(chunk)
            body = b""
        else:
            body = resp.read()
    finally:
        resp.close()
    return Response(status, resp.headers, body)
