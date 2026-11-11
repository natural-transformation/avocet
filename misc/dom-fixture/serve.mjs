// Static file server for the DOM adapter fixture. Serves the repository root.
// Run from the repository root inside the Nix shell:
//   nix develop . --no-write-lock-file --command node misc/dom-fixture/serve.mjs
// For automated Chromium/WebKit runs use scripts/test-dom-fixture.sh instead.
import { startFixtureServer } from './server.mjs';

const { port } = await startFixtureServer({ root: process.cwd(), port: Number(process.env.PORT || 8765) });
console.log(`dom fixture: http://127.0.0.1:${port}/misc/dom-fixture/index.html`);
