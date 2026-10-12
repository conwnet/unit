from http.server import BaseHTTPRequestHandler
from urllib.parse import urlsplit
import struct
import zlib


def picture(rgb):
    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data) & 0xffffffff)
    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', 96, 96, 8, 2, 0, 0, 0)) +
            chunk(b'IDAT', zlib.compress((b'\0' + bytes(rgb) * 96) * 96)) + chunk(b'IEND', b''))


PICTURE = picture((218, 130, 49))
HEAD = '<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1"><style>body{font:16px system-ui;margin:24px}a{display:block;margin:24px 0}img{width:96px;height:96px}</style>'
HOME = HEAD + '<title>Unit test page</title><h1>Unit test page</h1><a href="/second">Next page</a>'
IMAGES = HEAD + '<title>Images</title><h1>Images</h1><a href="/second"><img id="linked" src="/protected-image" alt="Linked picture"></a>'
SECOND = HEAD + '<title>Second page</title><h1>Second page</h1><a href="/">Home</a>'


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        path = urlsplit(self.path).path
        headers = {}
        if path == '/protected-image':
            if ('image_session=ok' not in self.headers.get('Cookie', '') or
                    not self.headers.get('Referer', '').endswith('/images') or
                    'Chrome/' not in self.headers.get('User-Agent', '')):
                self.send_error(403)
                return
            body, mime = PICTURE, 'image/png'
        elif path in ('/', '/images', '/second'):
            body = {'/': HOME, '/images': IMAGES, '/second': SECOND}[path].encode()
            mime = 'text/html; charset=utf-8'
            if path == '/images':
                headers['Set-Cookie'] = 'image_session=ok; Path=/; SameSite=Lax'
        else:
            self.send_error(404)
            return
        self.send_response(200)
        self.send_header('Content-Type', mime)
        self.send_header('Content-Length', str(len(body)))
        for name, value in headers.items():
            self.send_header(name, value)
        self.end_headers()
        self.wfile.write(body)
