#!/usr/bin/env python3
"""Independent test inputs only. Requires Python cryptography; no vehicle access."""
import hashlib
import hmac
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.ciphers.aead import AESCCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives.asymmetric import ec


def hkdf(ikm, salt, info):
    return HKDF(algorithm=hashes.SHA256(), length=64, salt=salt, info=info).derive(ikm)


def uart(key, iv, msg, counter, tail):
    ctr = counter.to_bytes(2, 'little')
    nonce = iv + bytes(4) + ctr + bytes(2)
    body = msg[:1] + ctr + AESCCM(key, tag_length=4).encrypt(nonce, msg[1:] + tail, b'')
    return b'\x55\xab' + body + ((~sum(body)) & 65535).to_bytes(2, 'little')


setup = hkdf(bytes(range(32)), None, b'mible-setup-info')
token = bytes(range(12))
app = bytes(range(16))
scooter = bytes(range(16, 32))
login = hkdf(token, app + scooter, b'mible-login-info')
vectors = {
    # RFC3610 §8 Packet Vector1 INPUT adapted: omit nonce's last A5 byte; M=4.
    # These outputs are Python answers, NOT the RFC's published M=8/noncelen13 answers.
    'rfc3610Adapted': AESCCM(bytes(range(0xc0, 0xd0)), tag_length=4).encrypt(
        bytes.fromhex('00000003020100a0a1a2a3a4'), bytes(range(8, 31)), bytes(range(8))),
    'ecdh': ec.derive_private_key(1, ec.SECP256R1()).exchange(
        ec.ECDH(), ec.derive_private_key(2, ec.SECP256R1()).public_key()),
    'setup': setup,
    'did': AESCCM(setup[28:44], tag_length=4).encrypt(bytes(range(16, 28)), b'test-device-id', b'devID'),
    'login': login,
    'loginInfo': hmac.new(login[16:32], app + scooter, hashlib.sha256).digest(),
    'scooterProof': hmac.new(login[:16], scooter + app, hashlib.sha256).digest(),
    'appFrame': uart(login[16:32], login[36:40], bytes.fromhex('032001b020'), 0, bytes.fromhex('aabbccdd')),
    'devFrame': uart(login[:16], login[32:36], bytes.fromhex('042301b50000'), 17, bytes.fromhex('11223344')),
    'emptyCcm': AESCCM(bytes(16), tag_length=4).encrypt(bytes(12), b'', b''),
    'longAadCcm': AESCCM(bytes(16), tag_length=4).encrypt(bytes(12), b'abc', bytes(0xff00)),
}
for name, value in vectors.items():
    print(name + '=' + value.hex())
