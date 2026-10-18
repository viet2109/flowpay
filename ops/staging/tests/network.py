"""Native network/TLS assertions against owned containers, never real destinations."""
import http.client
import json
import os
import socket
import ssl
import sys


def blocked(host, port):
    try:
        with socket.create_connection((host, port), timeout=1):
            return False
    except OSError:
        return True


def public():
    targets = json.loads(os.environ['PROTECTED_TARGETS'])
    assert targets, 'owned protected target inventory missing'
    for host, port in targets:
        assert blocked(host, port), 'owned private listener reachable from ingress network'
    for name in ('postgres', 'rabbitmq', 'app', 'collector', 'prometheus', 'grafana', 'tempo', 'egress'):
        try:
            socket.getaddrinfo(name, None)
        except socket.gaierror:
            continue
        raise AssertionError('owned private service resolved on public network')
    for version in (ssl.TLSVersion.TLSv1_2, ssl.TLSVersion.TLSv1_3):
        context = ssl.create_default_context(cafile='/run/secrets/edge-cert.pem')
        context.minimum_version = context.maximum_version = version
        connection = http.client.HTTPSConnection('edge', 8443, context=context, timeout=5)
        connection.request('GET', '/readyz')
        response = connection.getresponse()
        assert response.status == 200 and response.getheader('Strict-Transport-Security') == 'max-age=86400'
        response.read()
        connection.close()
    for ca, hostname in (('/run/secrets/cert.pem', 'edge'), ('/run/secrets/edge-cert.pem', 'wrong.invalid')):
        try:
            with socket.create_connection(('edge', 8443), timeout=5) as channel:
                ssl.create_default_context(cafile=ca).wrap_socket(channel, server_hostname=hostname).close()
        except ssl.SSLCertVerificationError:
            continue
        raise AssertionError('owned ingress accepted invalid trust or hostname')
    context = ssl.create_default_context(cafile='/run/secrets/edge-cert.pem')
    for path, method, expected, headers in (
        ('/actuator/prometheus', 'GET', 404, {'Host': 'app:9091', 'X-Forwarded-Port': '9091'}),
        ('/api/v1/auth/login', 'POST', 403, {}),
        ('/api/v1/auth/login', 'OPTIONS', 403, {'Origin': 'https://untrusted.invalid', 'Access-Control-Request-Method': 'POST'}),
    ):
        connection = http.client.HTTPSConnection('edge', 8443, context=context, timeout=5)
        connection.request(method, path, headers=headers)
        response = connection.getresponse()
        assert response.status == expected and response.getheader('Access-Control-Allow-Origin') is None
        response.read()
        connection.close()
    addresses = {item[4][0] for item in socket.getaddrinfo('edge', 8443, socket.AF_INET6, socket.SOCK_STREAM)}
    assert addresses, 'owned native IPv6 ingress missing'
    for address in addresses:
        with socket.create_connection((address, 8443), timeout=5) as channel:
            with context.wrap_socket(channel, server_hostname='edge') as tls:
                tls.sendall(b'GET /readyz HTTP/1.1\r\nHost: edge\r\nConnection: close\r\n\r\n')
                assert b'200' in tls.recv(256).split(b'\r\n')[0]


def egress():
    proxy = os.environ['PROXY_IP']
    for host in ('11.203.0.10', '2606:4700:ffff::10'):
        assert blocked(host, 8443), 'owned direct Webhook path bypassed proxy'
        with socket.create_connection((proxy, 3128), timeout=5) as channel:
            authority = ('[' + host + ']' if ':' in host else host) + ':8443'
            channel.sendall(('CONNECT ' + authority + ' HTTP/1.1\r\nHost: ' + authority + '\r\n\r\n').encode())
            response = b''
            while not response.endswith(b'\r\n\r\n') and len(response) < 8192:
                chunk = channel.recv(1)
                if not chunk:
                    break
                response += chunk
            assert b'200' in response.split(b'\r\n')[0], 'owned proxy denied public fixture'
            context = ssl.create_default_context(cafile='/run/merchant-ca.pem')
            with context.wrap_socket(channel, server_hostname=host) as tls:
                tls.sendall(b'GET /health HTTP/1.1\r\nHost: owned\r\nConnection: close\r\n\r\n')
                assert b'204' in tls.recv(256).split(b'\r\n')[0], 'owned proxy TLS/IP-SAN failed'
    for host in ('127.0.0.1', os.environ['PRIVATE_TARGET']):
        with socket.create_connection((proxy, 3128), timeout=5) as channel:
            channel.sendall(('CONNECT ' + host + ':5432 HTTP/1.1\r\nHost: owned\r\n\r\n').encode())
            assert b'403' in channel.recv(256).split(b'\r\n')[0], 'owned proxy accepted private target'


if __name__ == '__main__':
    try:
        {'public': public, 'egress': egress}[sys.argv[1]]()
        print('PASS: owned staging network/TLS boundaries')
    except Exception:
        print('FAIL: owned staging network/TLS boundary; raw endpoints/diagnostics withheld')
        sys.exit(1)
