// Static loopback file server for the DOM adapter fixture. It serves the
// repository root so the page can load the linked Scala.js module. Used by
// serve.mjs (manual runs) and run-browsers.mjs (automated runs).
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';

const types = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.map': 'application/json',
};

/**
 * Starts the server on `host:port`; port 0 lets the OS choose a free port.
 * Resolves to `{port, close}`. `close` also drops open keep-alive
 * connections, so an owning process can exit promptly.
 */
export function startFixtureServer({ root = process.cwd(), host = '127.0.0.1', port = 0 } = {}) {
  const base = path.resolve(root);
  const server = http.createServer((req, res) => {
    let file;
    try {
      file = path.resolve(base, '.' + decodeURIComponent(new URL(req.url, 'http://localhost').pathname));
    } catch {
      res.writeHead(400);
      res.end();
      return;
    }
    // Serve only inside the root. A bare prefix check would also admit sibling
    // directories that share the root's name as a prefix.
    if (file !== base && !file.startsWith(base + path.sep)) {
      res.writeHead(403);
      res.end();
      return;
    }
    if (fs.existsSync(file) && fs.statSync(file).isDirectory()) file = path.join(file, 'index.html');
    fs.readFile(file, (err, data) => {
      if (err) {
        res.writeHead(404);
        res.end('not found');
        return;
      }
      res.writeHead(200, { 'content-type': types[path.extname(file)] || 'application/octet-stream' });
      res.end(data);
    });
  });
  return new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(port, host, () => {
      server.off('error', reject);
      const close = () => new Promise(done => {
        server.close(() => done());
        server.closeAllConnections();
      });
      resolve({ port: server.address().port, close });
    });
  });
}
