// Runs the linked Scala.js DOM fixture in headless Chromium and WebKit.
// The wrapper links the fixture first and bounds the whole run:
//   nix develop .#browser --no-write-lock-file --command bash scripts/test-dom-fixture.sh
// Direct use from the repository root, after `sbt domFixture/fastLinkJS`:
//   nix develop .#browser --no-write-lock-file --command node misc/dom-fixture/run-browsers.mjs [chromium] [webkit]
//
// Each engine must publish a well-formed `window.avocetDomFixture` report with
// exactly EXPECTED_CHECKS uniquely named results, all passing, and raise no page
// error. Any other published value, null included, fails.
// Exit codes: 0 every engine passed; 1 a check or protocol failure; 2 a setup
// failure; 130/143/129 interrupted by SIGINT/SIGTERM/SIGHUP. The runner owns
// one loopback server and at most one browser process, and closes both on
// every path; a browser that misses its close deadline is killed.
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { startFixtureServer } from './server.mjs';

// Keep in step with DomFixture.scala, ProfileChecks.scala and IdentityChecks.scala
// (23 + 24 + 3 checks): a smaller count means checks vanished.
const EXPECTED_CHECKS = 50;

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const pagePath = '/misc/dom-fixture/index.html';
// The page loads this linked module; see index.html.
const modulePath = path.join(repoRoot, 'dom-fixture/target/scala-3.3.7/avocet-dom-fixture-fastopt/main.js');
const usage = 'Run: nix develop .#browser --no-write-lock-file --command bash scripts/test-dom-fixture.sh';

const deadlines = {
  launch: 30_000,
  navigation: 15_000,
  fixture: Number(process.env.AVOCET_DOM_FIXTURE_TIMEOUT_MS || 30_000),
  close: 10_000,
};

// Fault injection for runner-self-test.mjs. Every fault can only make a run
// fail, so a stray setting cannot hide a real failure.
const faults = {
  // A tampered DOM observation: the fixture's own checks must fail.
  'failing-check': "Object.defineProperty(Element.prototype, 'outerHTML', { configurable: true, get() { return 'tampered'; } });",
  // One published result dropped: the count gate must fail.
  'partial-results': "(() => { let v; Object.defineProperty(window, 'avocetDomFixture', { configurable: true, get: () => v, set(x) { x.results.pop(); v = x; } }); })();",
  // Results never published: the completion deadline must fail.
  'missing-results': "Object.defineProperty(window, 'avocetDomFixture', { configurable: true, get: () => undefined, set() {} });",
  // null published instead of the result object: the shape gate must fail.
  'null-report': "(() => { let v; Object.defineProperty(window, 'avocetDomFixture', { configurable: true, get: () => v, set() { v = null; } }); })();",
  // One result entry replaced by null: the shape gate must fail.
  'malformed-report': "(() => { let v; Object.defineProperty(window, 'avocetDomFixture', { configurable: true, get: () => v, set(x) { x.results[0] = null; v = x; } }); })();",
  // An uncaught page error while every check passes.
  'page-error': "setTimeout(() => { throw new Error('injected page error'); }, 0);",
};

const fault = process.env.AVOCET_DOM_FIXTURE_FAULT || '';
const engines = process.argv.length > 2 ? process.argv.slice(2) : ['chromium', 'webkit'];
const owned = { server: null, browserServer: null };
let playwright = null;
let interruptedCode = null;

const log = line => console.log(line);
const firstLine = error => String(error?.message ?? error).split('\n')[0];

function withDeadline(promise, ms, what) {
  let timer;
  const expired = new Promise((_, reject) => {
    timer = setTimeout(() => reject(new Error(`${what} exceeded ${ms} ms`)), ms);
  });
  return Promise.race([promise, expired]).finally(() => clearTimeout(timer));
}

async function closeBrowser() {
  const browserServer = owned.browserServer;
  owned.browserServer = null;
  if (!browserServer) return;
  try {
    await withDeadline(browserServer.close(), deadlines.close, 'browser close');
  } catch (error) {
    log(`forcing browser exit: ${firstLine(error)}`);
    await browserServer.kill().catch(() => {});
  }
}

async function closeServer() {
  const server = owned.server;
  owned.server = null;
  if (server) await withDeadline(server.close(), deadlines.close, 'server close').catch(error => log(firstLine(error)));
}

/** Returns a setup problem, or null once the driver, browsers and module are usable. */
function setupProblem() {
  if (!process.env.PLAYWRIGHT_DRIVER_PATH || !process.env.PLAYWRIGHT_BROWSERS_PATH) {
    return `missing the Nix Playwright environment. ${usage}`;
  }
  if (fault && !faults[fault]) return `unknown AVOCET_DOM_FIXTURE_FAULT ${fault}`;
  try {
    playwright = createRequire(import.meta.url)(process.env.PLAYWRIGHT_DRIVER_PATH);
    for (const engine of engines) {
      if (engine !== 'chromium' && engine !== 'webkit') return `unknown engine ${engine}; use chromium and/or webkit`;
      fs.accessSync(playwright[engine].executablePath(), fs.constants.X_OK);
    }
    if (engines.includes('webkit') && process.platform === 'linux') {
      fs.accessSync(process.env.AVOCET_PLAYWRIGHT_EGL_VENDOR || '', fs.constants.R_OK);
    }
  } catch (error) {
    return `Nix browser dependencies unavailable: ${firstLine(error)}. Do not download browsers separately; ${usage}`;
  }
  if (!fs.existsSync(modulePath)) return `missing ${path.relative(repoRoot, modulePath)}; link it with sbt domFixture/fastLinkJS`;
  return null;
}

/** The published shape: `{failed: number, results: [{name: string, ok: boolean, ...}]}`. */
function wellFormed(report) {
  return report !== null && typeof report === 'object' && typeof report.failed === 'number' &&
    Array.isArray(report.results) &&
    report.results.every(result => result !== null && typeof result === 'object' &&
      typeof result.name === 'string' && typeof result.ok === 'boolean');
}

/** Protocol gates on whatever the page published, even null; returns the problems found. */
function judge(report) {
  if (!wellFormed(report)) return [`malformed fixture report: ${String(JSON.stringify(report)).slice(0, 200)}`];
  const problems = [];
  const results = report.results;
  if (results.length !== EXPECTED_CHECKS) problems.push(`expected ${EXPECTED_CHECKS} checks, got ${results.length}`);
  if (new Set(results.map(result => result.name)).size !== results.length) problems.push('check names are not unique');
  const failing = results.filter(result => result.ok !== true).length;
  if (report.failed !== failing) problems.push(`summary reports ${report.failed} failed, results show ${failing}`);
  if (failing > 0) problems.push(`${failing} check(s) failed`);
  return problems;
}

async function runEngine(name, baseUrl) {
  const env = { ...process.env };
  if (name === 'webkit' && process.platform === 'linux') {
    // Only the owned WebKit process gets the pinned EGL vendor and software rendering.
    env.__EGL_VENDOR_LIBRARY_FILENAMES = process.env.AVOCET_PLAYWRIGHT_EGL_VENDOR;
    env.LIBGL_ALWAYS_SOFTWARE = 'true';
  }
  const problems = [];
  // Whatever the page published; judged even when it is null or false.
  let published = false;
  let report;
  let version = 'unknown';
  try {
    // launchServer exposes the browser process, so cleanup can be verified by pid.
    owned.browserServer = await playwright[name].launchServer({
      headless: true, env, timeout: deadlines.launch,
      handleSIGINT: false, handleSIGTERM: false, handleSIGHUP: false,
    });
    log(`${name}: browser pid ${owned.browserServer.process().pid}`);
    if (interruptedCode !== null) throw new Error('interrupted during launch');
    const browser = await playwright[name].connect(owned.browserServer.wsEndpoint(), { timeout: deadlines.launch });
    version = browser.version();
    const page = await browser.newPage();
    page.on('pageerror', error => problems.push(`page error: ${firstLine(error)}`));
    page.on('crash', () => problems.push('page crashed'));
    page.on('requestfailed', request => problems.push(`request failed: ${request.url()} (${request.failure()?.errorText})`));
    page.on('response', response => {
      const kind = response.request().resourceType();
      if (response.status() >= 400 && (kind === 'document' || kind === 'script')) {
        problems.push(`HTTP ${response.status()} for ${response.url()}`);
      }
    });
    if (fault) await page.addInitScript({ content: faults[fault] });
    await page.goto(baseUrl + pagePath, { timeout: deadlines.navigation });
    log(`${name}: waiting for results`);
    try {
      await page.waitForFunction(() => window.avocetDomFixture !== undefined, null, { timeout: deadlines.fixture });
      report = await page.evaluate(() => JSON.parse(JSON.stringify(window.avocetDomFixture)));
      published = true;
    } catch (error) {
      problems.push(error?.name === 'TimeoutError'
        ? `fixture did not publish results within ${deadlines.fixture} ms`
        : `fixture results unavailable: ${firstLine(error)}`);
    }
    // Let already-queued page tasks, such as deferred errors, report first.
    await page.evaluate(() => new Promise(resolve => setTimeout(resolve, 50)));
  } catch (error) {
    problems.push(`browser run failed: ${firstLine(error)}`);
  } finally {
    await closeBrowser();
  }
  if (published) problems.push(...judge(report));
  return { name, version, report: published && wellFormed(report) ? report : null, problems };
}

function print(outcome) {
  log(`${outcome.name} ${outcome.version}:`);
  const results = outcome.report?.results ?? [];
  for (const result of results) {
    log(`  ${result.ok ? 'PASS' : 'FAIL'} ${result.name}${result.ok ? '' : ` -- ${result.detail}`}`);
  }
  for (const problem of outcome.problems) log(`  ERROR ${problem}`);
  log(`${outcome.name}: ${outcome.problems.length === 0 ? 'PASS' : 'FAIL'} (${results.length} checks)`);
}

async function main() {
  const problem = setupProblem();
  if (problem) {
    console.error(problem);
    return 2;
  }
  log(`module: ${path.relative(repoRoot, modulePath)} (modified ${fs.statSync(modulePath).mtime.toISOString()})`);
  if (fault) log(`injected fault: ${fault}`);
  owned.server = await startFixtureServer({ root: repoRoot });
  log(`server listening on 127.0.0.1:${owned.server.port}`);
  let failed = 0;
  for (const engine of engines) {
    if (interruptedCode !== null) break;
    const outcome = await runEngine(engine, `http://127.0.0.1:${owned.server.port}`);
    print(outcome);
    if (outcome.problems.length > 0) failed += 1;
  }
  await closeServer();
  log(failed === 0 ? `PASS ${engines.join(', ')}` : `FAIL ${failed} of ${engines.length} engine(s)`);
  return failed === 0 ? 0 : 1;
}

function exitWith(code) {
  process.exitCode = code;
  // A leaked handle must not keep the process alive after owned cleanup.
  setTimeout(() => process.exit(code), 2_000).unref();
}

for (const [signal, code] of Object.entries({ SIGINT: 130, SIGTERM: 143, SIGHUP: 129 })) {
  process.on(signal, () => {
    if (interruptedCode !== null) return;
    interruptedCode = code;
    log(`interrupted by ${signal}; closing the owned browser and server`);
    Promise.allSettled([closeBrowser(), closeServer()]).then(() => exitWith(code));
  });
}

main().then(
  code => { if (interruptedCode === null) exitWith(code); },
  error => {
    console.error(`runner failed: ${firstLine(error)}`);
    Promise.allSettled([closeBrowser(), closeServer()]).then(() => exitWith(interruptedCode ?? 1));
  });
