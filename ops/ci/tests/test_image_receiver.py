"""Merchant fixture must not confuse cancelled connections with bad signatures."""
import hashlib
import hmac
import importlib.util
import io
import json
from pathlib import Path
import secrets
import time
import unittest
from unittest.mock import Mock

SPEC = importlib.util.spec_from_file_location("merchant", Path(__file__).resolve().parents[2] / "container/tests/receiver.py")
merchant = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(merchant)


class ReceiverTest(unittest.TestCase):
    def setUp(self):
        merchant.STATE.clear()
        merchant.STATE.update(secret=secrets.token_urlsafe(32), active=False, invalid=False, events={}, hold=False)

    def delivery(self, *, amount=1, event="evt_owned", valid=True, cancelled=False, timestamp=None):
        raw = json.dumps({"id": event, "type": "payment.succeeded", "amount": amount}).encode()
        timestamp = str(int(time.time()) if timestamp is None else timestamp)
        signed = timestamp.encode() + b"." + raw
        signature = hmac.new(merchant.STATE["secret"].encode(), signed, hashlib.sha256).hexdigest()
        request = object.__new__(merchant.Merchant)
        request.headers = {"Content-Length": str(len(raw)), "FlowPay-Event-Id": event,
                           "FlowPay-Signature": "t=" + timestamp + ",v1=" + (signature if valid else "0" * 64)}
        request.rfile = io.BytesIO(raw)
        request.send_response = Mock()
        request.end_headers = Mock(side_effect=BrokenPipeError if cancelled else None)
        request.do_POST()
        return request

    def test_exact_body_signature_accepted(self):
        request = self.delivery()
        request.send_response.assert_called_once_with(204)
        self.assertFalse(merchant.STATE["invalid"])

    def test_wrong_signature_rejected(self):
        request = self.delivery(valid=False)
        request.send_response.assert_called_once_with(400)
        self.assertTrue(merchant.STATE["invalid"])

    def test_expired_signature_rejected(self):
        self.delivery(timestamp=int(time.time()) - 120)
        self.assertTrue(merchant.STATE["invalid"])

    def test_equal_duplicate_accepted(self):
        self.delivery()
        self.delivery()
        self.assertEqual(len(merchant.STATE["events"]), 1)
        self.assertFalse(merchant.STATE["invalid"])

    def test_contradictory_duplicate_rejected(self):
        self.delivery()
        self.delivery(amount=2)
        self.assertTrue(merchant.STATE["invalid"])

    def test_shutdown_cancellation_is_not_bad_hmac(self):
        self.delivery(cancelled=True)
        self.assertFalse(merchant.STATE["invalid"])
        self.assertFalse(merchant.STATE["active"])
