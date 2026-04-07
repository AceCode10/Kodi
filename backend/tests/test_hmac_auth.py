import hashlib
import hmac

from app.hmac_auth import _sign


def test_hmac_sign_matches_canonical_format():
    secret = "abc"
    ts = "1700000000"
    body = b"{}"
    h = hashlib.sha256(body).hexdigest()
    sig = _sign(secret, ts, "POST", "/v1/sessions", h)
    assert len(sig) == 64
    expected = hmac.new(secret.encode(), f"{ts}\nPOST\n/v1/sessions\n{h}".encode(), hashlib.sha256).hexdigest()
    assert sig == expected


def test_hmac_method_case_normalized():
    secret = "x"
    ts = "1"
    body_hash = hashlib.sha256(b"").hexdigest()
    assert _sign(secret, ts, "get", "/p", body_hash) == _sign(secret, ts, "GET", "/p", body_hash)
