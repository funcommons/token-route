# mock FEED 上游：任何 GET /feed 返回 ./feed.json（全量对比真源）
import http.server

class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        with open("feed.json", "rb") as f:
            body = f.read()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *a):
        pass

http.server.HTTPServer(("0.0.0.0", 8000), Handler).serve_forever()
