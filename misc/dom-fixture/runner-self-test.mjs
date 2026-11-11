// Self-test for run-browsers.mjs. Injected faults must fail the run with a
// useful message; passing, failing and interrupted runs must all leave
// neither the browser's process group nor the loopback server behind.
// Needs a linked fixture; scripts/test-dom-fixture.sh runs it after the fixture:
//   nix develop .#browser --no-write-lock-file --command node misc/dom-fixture/runner-self-test.mjs
import { spawn } from 'node:child_process';
import net from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const runner = path.join(path.dirname(fileURLToPath(import.meta.url)), 'run-browsers.mjs');
const runDeadline = 120_000;
const engines = ['chromium', 'webkit'];

const faultCases = [
  { fault: '', code: 0, expect: /^PASS /m },
  { fault: 'failing-check', code: 1, expect: /FAIL first render builds the tree -- tampered/ },
  { fault: 'partial-results', code: 1, expect: /ERROR expected \d+ checks, got \d+/ },
  { fault: 'missing-results', code: 1, expect: /ERROR fixture did not publish results within 3000 ms/, env: { AVOCET_DOM_FIXTURE_TIMEOUT_MS: '3000' } },
  { fault: 'null-report', code: 1, expect: /ERROR malformed fixture report: null/ },
  { fault: 'malformed-report', code: 1, expect: /ERROR malformed fixture report: \{/ },
  { fault: 'page-error', code: 1, expect: /ERROR page error: injected page error/ },
];

// The runner waits for results that never arrive, then receives the signal.
const interruptCases = [
  { engine: 'chromium', signal: 'SIGINT', code: 130 },
  { engine: 'webkit', signal: 'SIGTERM', code: 143 },
];

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

/** Runs the runner once; `onLine` may signal the child. Resolves to its exit code and output. */
function runRunner(args, env, onLine = () => {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [runner, ...args], { env: { ...process.env, ...env }, stdio: ['ignore', 'pipe', 'pipe'] });
    let output = '';
    let pending = '';
    const timer = setTimeout(() => {
      child.kill('SIGKILL');
      reject(new Error(`runner exceeded ${runDeadline} ms:\n${output}`));
    }, runDeadline);
    const consume = chunk => {
      output += chunk;
      pending += chunk;
      const lines = pending.split('\n');
      pending = lines.pop();
      lines.forEach(line => onLine(line, child));
    };
    child.stdout.on('data', consume);
    child.stderr.on('data', consume);
    child.on('error', reject);
    child.on('close', (code, signal) => {
      clearTimeout(timer);
      resolve({ code: code ?? signal, output });
    });
  });
}

function groupAlive(pid) {
  try {
    process.kill(-pid, 0);
    return true;
  } catch (error) {
    return error.code === 'EPERM';
  }
}

function portRefuses(port) {
  return new Promise(resolve => {
    const socket = net.connect({ host: '127.0.0.1', port });
    socket.once('connect', () => { socket.destroy(); resolve(false); });
    socket.once('error', () => resolve(true));
  });
}

/** Every browser group the run reported is gone and its server port refuses connections. */
async function assertCleanedUp(output, label) {
  const pids = [...output.matchAll(/browser pid (\d+)/g)].map(match => Number(match[1]));
  const port = Number(output.match(/server listening on 127\.0\.0\.1:(\d+)/)?.[1]);
  if (pids.length === 0 || !port) throw new Error(`${label}: runner did not report its browser pid and port:\n${output}`);
  for (const pid of pids) {
    // Allow the OS a moment to reap the exited processes.
    for (let attempt = 0; groupAlive(pid) && attempt < 30; attempt++) await sleep(100);
    if (groupAlive(pid)) throw new Error(`${label}: browser process group ${pid} is still alive`);
  }
  if (!(await portRefuses(port))) throw new Error(`${label}: server port ${port} still accepts connections`);
}

async function main() {
  for (const testCase of faultCases) {
    for (const engine of engines) {
      const label = `fault=${testCase.fault || 'none'} engine=${engine}`;
      const { code, output } = await runRunner([engine], { AVOCET_DOM_FIXTURE_FAULT: testCase.fault, ...testCase.env });
      if (code !== testCase.code || !testCase.expect.test(output)) {
        throw new Error(`${label}: expected exit ${testCase.code} matching ${testCase.expect}, got ${code}:\n${output}`);
      }
      await assertCleanedUp(output, label);
      console.log(`ok ${label}: exit ${code}, cleaned up`);
    }
  }
  for (const testCase of interruptCases) {
    const label = `${testCase.signal} engine=${testCase.engine}`;
    const env = { AVOCET_DOM_FIXTURE_FAULT: 'missing-results', AVOCET_DOM_FIXTURE_TIMEOUT_MS: '60000' };
    let seen = '';
    let liveBeforeSignal = null;
    const { code, output } = await runRunner([testCase.engine], env, (line, child) => {
      seen += line + '\n';
      if (!line.endsWith('waiting for results')) return;
      // The same probes must see a live browser group and server first;
      // otherwise the cleanup assertion below could pass vacuously.
      const pid = Number(seen.match(/browser pid (\d+)/)[1]);
      const port = Number(seen.match(/server listening on 127\.0\.0\.1:(\d+)/)[1]);
      portRefuses(port).then(refused => {
        liveBeforeSignal = groupAlive(pid) && !refused;
        child.kill(testCase.signal);
      });
    });
    if (liveBeforeSignal !== true) throw new Error(`${label}: browser group or server was not observed live before the signal:\n${output}`);
    if (code !== testCase.code || !output.includes(`interrupted by ${testCase.signal}`)) {
      throw new Error(`${label}: expected exit ${testCase.code} after interruption, got ${code}:\n${output}`);
    }
    await assertCleanedUp(output, label);
    console.log(`ok ${label}: exit ${code}, cleaned up`);
  }
  console.log('PASS runner self-test');
}

main().catch(error => {
  console.error(`FAIL runner self-test: ${error.message}`);
  process.exitCode = 1;
});
