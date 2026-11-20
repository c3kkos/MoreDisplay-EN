"""Cast bench web server: serves receiver.html (this folder) and saves what the page reports.

POST /report?name=<run> with a JSON body writes <reports dir>/<run>.json. Localhost only.
Usage: python server.py <reports dir> [port]
"""
import http.server
import json
import os
import sys
import urllib.parse

REPORTS = os.path.abspath(sys.argv[1])
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 8765
HERE = os.path.dirname(os.path.abspath(__file__))
os.makedirs(REPORTS, exist_ok=True)


class Handler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=HERE, **kwargs)

    def do_POST(self):
        url = urllib.parse.urlparse(self.path)
        name = urllib.parse.parse_qs(url.query).get("name", ["run"])[0]
        if url.path != "/report" or not name.replace("-", "").replace("_", "").isalnum():
            self.send_error(404)
            return
        body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        json.loads(body)  # only valid JSON is stored
        with open(os.path.join(REPORTS, name + ".json"), "wb") as f:
            f.write(body)
        self.send_response(204)
        self.end_headers()

    def log_message(self, *args):
        pass


http.server.ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
