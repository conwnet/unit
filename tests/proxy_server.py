import base64
import http.server
import select
import socket
import socketserver
import time
import urllib.error
import urllib.request
from urllib.parse import urlsplit

DATA = b'unit proxy download\n' * 8192


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, file, code, message, headers, new_url):
        return None


class Origin(http.server.BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def log_message(self, *args):
        pass

    def respond(self, body, mime, status=200, headers=None):
        self.send_response(status)
        self.send_header('Content-Type', mime)
        self.send_header('Content-Length', str(len(body)))
        for name, value in (headers or {}).items():
            self.send_header(name, value)
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        path = urlsplit(self.path).path
        if self.headers.get('Proxy-Authorization'):
            self.send_error(403, 'Proxy credentials reached the origin')
            return
        if path == '/page':
            self.respond(b'''<!doctype html><meta name="viewport" content="width=device-width"><title>Proxy fixture</title>
<body style="font:18px sans-serif;padding:24px">PROXY_OK<p><a href="/slow.bin">Download</a></p></body>''', 'text/html', headers={'Set-Cookie': 'session=unit; Path=/'})
        elif path == '/redirect-other':
            self.respond(b'', 'text/plain', 302, {'Location': 'http://localhost:8780/file.bin'})
        elif path in ['/file.bin', '/cookie-file.bin', '/slow.bin']:
            referer = 'http://proxy-target.test:8780/page'
            if path == '/cookie-file.bin' and (
                    'session=unit' not in self.headers.get('Cookie', '') or
                    self.headers.get('User-Agent') != 'unit-fixture' or
                    self.headers.get('Referer') != referer):
                self.respond(b'Browser credentials required', 'text/plain', 403)
                return
            if path == '/file.bin' and self.headers.get('Host', '').startswith('localhost:') and (
                    self.headers.get('Cookie') or self.headers.get('Referer')):
                self.respond(b'Credentials reached the redirect destination', 'text/plain', 403)
                return
            size = 8 * 1024 * 1024 if path == '/slow.bin' else len(DATA)
            self.send_response(200)
            self.send_header('Content-Type', 'application/octet-stream')
            self.send_header('Content-Disposition', 'attachment; filename="proxy-file.bin"')
            self.send_header('Content-Length', str(size))
            self.end_headers()
            sent = 0
            try:
                while sent < size:
                    chunk = DATA[:min(len(DATA), size - sent)]
                    self.wfile.write(chunk)
                    self.wfile.flush()
                    sent += len(chunk)
                    if path == '/slow.bin':
                        time.sleep(.12)
            except (BrokenPipeError, ConnectionResetError):
                pass
        else:
            self.send_error(404)


def tunnel(client, upstream):
    try:
        while True:
            ready, _, _ = select.select([client, upstream], [], [], 20)
            if not ready:
                return
            for source in ready:
                data = source.recv(32768)
                if not data:
                    return
                (upstream if source is client else client).sendall(data)
    except OSError:
        pass


class HttpProxy(http.server.BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def log_message(self, *args):
        pass

    def auth(self):
        expected = 'Basic ' + base64.b64encode(b'unit:proxy-pass').decode()
        if self.headers.get('Proxy-Authorization') == expected:
            return True
        self.send_response(407)
        self.send_header('Proxy-Authenticate', 'Basic realm="unit"')
        self.send_header('Content-Length', '0')
        self.send_header('Connection', 'close')
        self.end_headers()
        self.close_connection = True
        return False

    def destination(self, host, port):
        if host not in ['proxy-target.test', 'localhost', '127.0.0.1'] or port != 8780:
            raise OSError('Fixture destination refused')
        return '127.0.0.1', port

    def do_GET(self):
        if not self.auth():
            return
        parsed = urlsplit(self.path)
        try:
            host, port = self.destination(parsed.hostname, parsed.port or 80)
            path = parsed.path or '/'
            if parsed.query:
                path += '?' + parsed.query
            headers = {key: value for key, value in self.headers.items()
                       if key.lower() not in ['proxy-authorization', 'connection', 'proxy-connection']}
            response = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect()).open(
                urllib.request.Request('http://' + host + ':' + str(port) + path, headers=headers), timeout=15)
        except urllib.error.HTTPError as error:
            response = error
        except OSError:
            self.send_error(502)
            return
        with response:
            self.send_response(response.status)
            for key, value in response.headers.items():
                if key.lower() not in ['server', 'date', 'connection', 'transfer-encoding']:
                    self.send_header(key, value)
            self.send_header('Connection', 'close')
            self.end_headers()
            self.close_connection = True
            try:
                while data := response.read(32768):
                    self.wfile.write(data)
                    self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError):
                pass


class SocksProxy(socketserver.BaseRequestHandler):
    def read(self, count):
        data = b''
        while len(data) < count:
            block = self.request.recv(count - len(data))
            if not block:
                raise OSError('Closed')
            data += block
        return data

    def handle(self):
        self.request.settimeout(15)
        try:
            version, count = self.read(2)
            methods = self.read(count)
            if version != 5 or 0 not in methods:
                self.request.sendall(b'\x05\xff')
                return
            self.request.sendall(b'\x05\x00')
            version, command, reserved, kind = self.read(4)
            if kind == 3:
                host = self.read(self.read(1)[0]).decode()
            elif kind == 1:
                host = socket.inet_ntoa(self.read(4))
            else:
                self.request.sendall(b'\x05\x08\x00\x01' + b'\x00' * 6)
                return
            port = int.from_bytes(self.read(2), 'big')
            if command != 1 or host not in ['proxy-target.test', 'localhost', '127.0.0.1'] or port != 8780:
                return
            with socket.create_connection(('127.0.0.1', port), 10) as upstream:
                self.request.sendall(b'\x05\x00\x00\x01' + b'\x00' * 6)
                tunnel(self.request, upstream)
        except OSError:
            pass


class TcpServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


def create_servers():
    servers = []
    try:
        servers.append(http.server.ThreadingHTTPServer(('127.0.0.1', 8780), Origin))
        servers.append(http.server.ThreadingHTTPServer(('127.0.0.1', 8880), HttpProxy))
        servers.append(TcpServer(('127.0.0.1', 8882), SocksProxy))
        return servers
    except BaseException:
        for server in servers:
            server.server_close()
        raise
