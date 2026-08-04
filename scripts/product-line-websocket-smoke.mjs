#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";

class Client {
  constructor(socket, authenticatedUserId) {
    this.socket = socket;
    this.authenticatedUserId = authenticatedUserId;
    this.messages = [];
    this.waiters = [];
    socket.addEventListener("message", event => {
      const message = JSON.parse(String(event.data));
      trace.push({ action: "message", userId: authenticatedUserId, message });
      const waiter = this.waiters.find(candidate => candidate.predicate(message));
      if (waiter) {
        this.waiters = this.waiters.filter(candidate => candidate !== waiter);
        waiter.resolve(message);
      } else {
        this.messages.push(message);
      }
    });
  }

  next(waitMs) {
    if (this.messages.length > 0) return Promise.resolve(this.messages.shift());
    return new Promise((resolve, reject) => {
      const waiter = { predicate: () => true, resolve, reject, timer: null };
      waiter.timer = setTimeout(() => {
        this.waiters = this.waiters.filter(candidate => candidate !== waiter);
        reject(new Error("websocket event timeout"));
      }, waitMs);
      waiter.resolve = message => { clearTimeout(waiter.timer); resolve(message); };
      this.waiters.push(waiter);
    });
  }

  waitFor(predicate, waitMs) {
    const index = this.messages.findIndex(predicate);
    if (index >= 0) return Promise.resolve(this.messages.splice(index, 1)[0]);
    return new Promise((resolve, reject) => {
      const waiter = { predicate, resolve, reject, timer: null };
      waiter.timer = setTimeout(() => {
        this.waiters = this.waiters.filter(candidate => candidate !== waiter);
        reject(new Error("websocket response timeout"));
      }, waitMs);
      waiter.resolve = message => { clearTimeout(waiter.timer); resolve(message); };
      this.waiters.push(waiter);
    });
  }

  close() {
    for (const waiter of this.waiters) {
      clearTimeout(waiter.timer);
      waiter.reject(new Error("websocket client closed"));
    }
    this.waiters = [];
    this.socket.close();
  }

  send(command) {
    trace.push({ action: "send", userId: this.authenticatedUserId, command });
    this.socket.send(JSON.stringify({ op: "subscribe", ...command }));
  }
}

const args = parseArgs(process.argv.slice(2));
const productLine = args.productLine || process.env.PRODUCT_LINE || "LINEAR_PERPETUAL";
const symbol = args.symbol || process.env.SYMBOL || (productLine === "SPOT" ? "BTC-USDT-SPOT" : "BTC-USDT");
const userId = Number(args.userId || process.env.WS_USER_ID || 0);
const otherUserId = Number(args.otherUserId || process.env.WS_OTHER_USER_ID || userId + 1);
const timeoutMs = Number(args.timeoutMs || process.env.WS_TIMEOUT_MS || 120000);
const url = args.url || process.env.WS_URL || "ws://localhost:9094/ws/v1";
const evidence = args.evidence || process.env.WS_EVIDENCE || `/tmp/product-line-websocket-${Date.now()}.json`;
const privateChannels = (args.privateChannels || process.env.WS_PRIVATE_CHANNELS
    || (productLine === "SPOT" ? "orders,executionReports" : "orders,executionReports,positions"))
    .split(",").map(value => value.trim()).filter(Boolean);
const trace = [];

if (!Number.isSafeInteger(userId) || userId <= 0 || !Number.isSafeInteger(otherUserId) || otherUserId <= 0) {
  fail("userId and otherUserId must be positive integers");
}
if (!Number.isFinite(timeoutMs) || timeoutMs < 1000) {
  fail("timeoutMs must be at least 1000");
}
if (typeof WebSocket !== "function") {
  fail("Node WebSocket runtime is unavailable");
}

const clients = [];
try {
  const primary = await connectWithRetry(`${url}?userId=${userId}`, userId);
  const secondary = await connectWithRetry(`${url}?userId=${otherUserId}`, otherUserId);
  const anonymous = await connectWithRetry(url, null);
  clients.push(primary, secondary, anonymous);

  await subscribe(primary, { id: "primary-depth", channel: "depth", symbol, productLine });
  for (const channel of privateChannels) {
    await subscribe(primary, { id: `primary-${channel}`, channel, symbol: "*", productLine });
    await subscribe(secondary, { id: `secondary-${channel}`, channel, symbol: "*", productLine });
  }
  const anonymousError = await expectError(anonymous, {
    id: "anonymous-private",
    channel: "orders",
    symbol: "*",
    productLine
  });
  await sendAndWait(primary, { op: "ping", id: "primary-ping" }, message => message.op === "pong"
      && message.id === "primary-ping");

  const requiredChannels = new Set(["depth", ...privateChannels]);
  const observed = new Set();
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline && observed.size < requiredChannels.size) {
    const remaining = Math.max(1, deadline - Date.now());
    const result = await Promise.race([
      primary.next(remaining).then(message => ({ client: "primary", message })),
      secondary.next(remaining).then(message => ({ client: "secondary", message }))
    ]);
    if (result.client === "secondary" && isPrivateEvent(result.message)) {
      fail(`private event leaked to user ${otherUserId}: ${JSON.stringify(result.message)}`);
    }
    if (result.client === "primary" && result.message.op === "event") {
      if (result.message.productLine && result.message.productLine !== productLine) {
        fail(`event product line mismatch: ${JSON.stringify(result.message)}`);
      }
      if (requiredChannels.has(result.message.channel)
          && (result.message.channel === "depth" || result.message.userId === userId)) {
        observed.add(result.message.channel);
      }
    }
  }
  if (observed.size !== requiredChannels.size) {
    fail(`missing events: required=${[...requiredChannels].join(",")} observed=${[...observed].join(",")}`);
  }
  await sendAndWait(primary, { op: "unsubscribe", id: "primary-depth", channel: "depth", symbol, productLine },
      message => message.op === "unsubscribed" && message.id === "primary-depth");
  const result = {
    pass: true,
    productLine,
    symbol,
    userId,
    otherUserId,
    requiredChannels: [...requiredChannels],
    observedChannels: [...observed],
    anonymousPrivateSubscription: anonymousError,
    actions: trace
  };
  fs.mkdirSync(path.dirname(evidence), { recursive: true });
  fs.writeFileSync(evidence, `${JSON.stringify(result, null, 2)}\n`);
  process.stdout.write(`websocket smoke PASS evidence=${evidence}\n`);
} catch (error) {
  writeFailure(error);
  process.exitCode = 1;
} finally {
  for (const client of clients) {
    try { client.close(); } catch { }
  }
}

function parseArgs(values) {
  const result = {};
  for (let index = 0; index < values.length; index += 1) {
    const value = values[index];
    if (!value.startsWith("--")) continue;
    const key = value.slice(2).replace(/-([a-z])/g, (_, letter) => letter.toUpperCase());
    result[key] = values[index + 1] && !values[index + 1].startsWith("--") ? values[++index] : "true";
  }
  return result;
}

function connectWithRetry(target, authenticatedUserId) {
  const deadline = Date.now() + timeoutMs;
  return new Promise((resolve, reject) => {
    const attempt = () => {
      const socket = new WebSocket(target);
      const timer = setTimeout(() => {
        socket.close();
        retry();
      }, Math.min(5000, Math.max(1000, deadline - Date.now())));
      socket.addEventListener("open", () => {
        clearTimeout(timer);
        const client = new Client(socket, authenticatedUserId);
        trace.push({ action: "connected", userId: authenticatedUserId });
        resolve(client);
      }, { once: true });
      socket.addEventListener("error", () => {
        clearTimeout(timer);
        socket.close();
        retry();
      }, { once: true });
    };
    const retry = () => {
      if (Date.now() >= deadline) {
        reject(new Error(`websocket connection timeout: ${target}`));
      } else {
        setTimeout(attempt, 250);
      }
    };
    attempt();
  });
}

async function subscribe(client, command) {
  client.send(command);
  return client.waitFor(message => message.op === "subscribed" && message.id === command.id, timeoutMs);
}

async function expectError(client, command) {
  client.send(command);
  const message = await client.waitFor(response => response.op === "error", timeoutMs);
  if (!String(message.error || "").includes("authenticated")) {
    fail(`unexpected anonymous subscription error: ${JSON.stringify(message)}`);
  }
  return message.error;
}

async function sendAndWait(client, command, predicate) {
  trace.push({ action: "send", userId: client.authenticatedUserId, command });
  client.socket.send(JSON.stringify(command));
  return client.waitFor(predicate, timeoutMs);
}

function isPrivateEvent(message) {
  return message.op === "event" && privateChannels.includes(message.channel);
}

function writeFailure(error) {
  const result = { pass: false, error: String(error?.stack || error), actions: trace };
  try {
    fs.mkdirSync(path.dirname(evidence), { recursive: true });
    fs.writeFileSync(evidence, `${JSON.stringify(result, null, 2)}\n`);
  } catch { }
  process.stderr.write(`websocket smoke FAIL evidence=${evidence}\n${result.error}\n`);
}

function fail(message) {
  throw new Error(message);
}
