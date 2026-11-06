/**
 * Dedicated nonvisual backend host. This page does not render, use React,
 * or contain styles. Transport: origin-confined AndroidX WebMessageListener.
 *
 * Contract: {id, op, args} -> {id, ok, result|error}; unsolicited {event,data}.
 * No UI code is allowed to import this module. All backend APIs are
 * dispatched here so that Kotlin presentation code never depends on the SDK.
 */
import {
  createDaemonConnection,
  factoryRelayTarget,
} from '@droidmobile/daemon-client';
import type { DaemonConnection, SessionHandle } from '@droidmobile/daemon-client';

interface AndroidHost { postMessage(data: string): void }
declare global {
  interface Window {
    DroidHost?: AndroidHost;
    __nativeReceive?: (request: string) => void;
  }
}
type Request = { id: string; op: string; args?: Record<string, any> };
let connection: DaemonConnection | null = null;
const sessions = new Map<string, SessionHandle>();
let terminal: ReturnType<DaemonConnection['openTerminalClient']> | null = null;
let unsubStatus: (() => void) | null = null;
let unsubMission: (() => void) | null = null;
const permissionPending = new Map<string, (value: any) => void>();
const askPending = new Map<string, (value: any) => void>();

function safe(value: unknown): unknown {
  try { return JSON.parse(JSON.stringify(value, (_key, entry) => {
    if (typeof entry === 'bigint') return entry.toString();
    if (entry instanceof Error) return { message: entry.message, name: entry.name };
    return entry;
  })); } catch { return null; }
}
function post(message: unknown): void {
  window.DroidHost?.postMessage(JSON.stringify(safe(message)));
}
function publish(event: string, data: unknown): void {
  post({ event, data });
}
async function handleSession(id: string): Promise<SessionHandle> {
  if (!connection) throw new Error('Not connected');
  const current = sessions.get(id);
  if (current) return current;
  const resumed = await connection.resumeSession(id);
  sessions.set(id, resumed);
  return resumed;
}
function withConnection(): DaemonConnection {
  if (!connection) throw new Error('Not connected');
  return connection;
}
async function requestPermission(data: unknown): Promise<any> {
  const id = crypto.randomUUID();
  publish('permission.request', { id, request: data });
  return new Promise(resolve => permissionPending.set(id, resolve));
}
async function askUser(data: unknown): Promise<any> {
  const id = crypto.randomUUID();
  publish('question.request', { id, request: data });
  return new Promise(resolve => askPending.set(id, resolve));
}
async function connect(args: Record<string, any>): Promise<void> {
  if (connection) {
    unsubStatus?.(); unsubMission?.(); terminal?.dispose();
    connection.disconnect();
    sessions.clear();
  }
  // The CLI prints an /agent URL, but an Android client needs /client.
  const address = String(args.url ?? '');
  const url = factoryRelayTarget(address)?.url ?? address;
  connection = createDaemonConnection({
    url, apiKey: String(args.apiKey ?? ''),
    permissionHandler: (sessionId, request, generation) => requestPermission({ sessionId, request, generation }),
    askUserHandler: (sessionId, request, generation) => askUser({ sessionId, request, generation }),
    onFacadeLost: () => {
      for (const resolve of permissionPending.values()) resolve({ behaviour: 'deny' });
      for (const resolve of askPending.values()) resolve({ cancelled: true });
      permissionPending.clear(); askPending.clear();
    }
  });
  unsubStatus = connection.onStatus(status => publish('connection.status', status));
  // Missions subscribe per opened session; registered on sessions.open/create.
  await connection.connect();
  publish('connection.status', connection.getStatus());
}
async function dispatch(op: string, a: Record<string, any>): Promise<unknown> {
  if (op === 'ping') return { ready: true, protocol: 1 };
  if (op === 'connect') return connect(a);
  if (op === 'disconnect') {
    unsubStatus?.(); unsubMission?.(); terminal?.dispose();
    connection?.disconnect(); connection = null;
    sessions.clear();
    publish('connection.status', 'disconnected');
    return { disconnected: true };
  }
  if (op === 'permission.answer') {
    const resolve = permissionPending.get(String(a.id));
    if (!resolve) return false;
    permissionPending.delete(String(a.id));
    resolve(typeof a.answer === 'object' && a.answer?.decision ? a.answer.decision : (a.answer ?? 'cancel')); return true;
  }
  if (op === 'question.answer') {
    const resolve = askPending.get(String(a.id));
    if (!resolve) return false;
    askPending.delete(String(a.id)); resolve(a.answer); return true;
  }
  const c = withConnection();
  switch (op) {
    // Sessions, search and transcript.
    case 'sessions.list': return c.listSessions(a.options);
    case 'sessions.opened': return c.listOpenedSessions();
    case 'sessions.search': return c.searchSessions(a.params);
    case 'sessions.create': {
      const s = await c.createSession(a.params);
      sessions.set(s.id, s);
      return { id: s.id, cwd: s.cwd, settings: s.settingsSnapshot };
    }
    case 'sessions.rename': return c.renameSession(a.id, a.title);
    case 'sessions.archive': return c.archiveSession(a.id, a.options);
    case 'sessions.unarchive': return c.unarchiveSession(a.id);
    case 'sessions.open': {
      const s = await handleSession(String(a.id));
      return { id: s.id, cwd: s.cwd, settings: s.settingsSnapshot };
    }
    case 'sessions.messages': return c.getMessagesPage(a.id, a.options);
    case 'sessions.allMessages': return c.listAllMessages(a.id, a.options);
    case 'sessions.send': {
      const s = await handleSession(String(a.id));
      (async () => {
        try {
          for await (const event of s.stream(String(a.prompt), a.options)) {
            publish('session.stream', { sessionId: s.id, update: event });
          }
          publish('session.finished', { sessionId: s.id });
        } catch (error) {
          publish('session.failed', { sessionId: s.id, message: String(error) });
        }
      })();
      return { started: true };
    }
    case 'sessions.interrupt': return (await handleSession(a.id)).interrupt();
    case 'sessions.queue': return (await handleSession(a.id)).queueMessage(a.prompt, a.options);
    case 'sessions.cancelQueued': return (await handleSession(a.id)).cancelQueued(a.requestId);
    case 'sessions.settings': return (await handleSession(a.id)).settingsSnapshot;
    case 'sessions.updateSettings': return (await handleSession(a.id)).applySettings(a.patch);
    case 'sessions.context': return (await handleSession(a.id)).getContextBreakdown();
    case 'sessions.compact': return (await handleSession(a.id)).compact(a.instructions);
    case 'sessions.fork': return (await handleSession(a.id)).fork(a.options);
    case 'sessions.rewindInfo': return (await handleSession(a.id)).getRewindInfo(a.messageId);
    case 'sessions.rewind': return (await handleSession(a.id)).rewind(a.params);
    // Workspace and Git.
    case 'workspace.validate': return c.validateDirectory(a.path);
    case 'workspace.trustCheck': return c.checkFolderTrust(a.path);
    case 'workspace.trust': return c.trustFolder(a.path);
    case 'workspace.changeDirectory': return c.changeDirectory({ sessionId: a.id, workingDirectory: a.path });
    case 'workspace.files': return c.listFiles(a.id, a.showHidden);
    case 'workspace.search': return c.searchFiles(a.id, a.query, a.limit, a.showHidden);
    case 'workspace.content': return c.getFileContent({ sessionId: a.id, filePath: a.path });
    case 'workspace.diff': return c.getGitDiff({ sessionId: a.id, baseBranch: a.baseBranch, statsOnly: a.statsOnly });
    case 'git.branches': return c.listGitBranches(a.cwd);
    case 'git.checkout': return c.checkoutGitBranch(a.params);
    case 'git.commit': return c.commitGitChanges(a.id, a.message);
    case 'git.push': return c.pushGitBranch(a.id);
    case 'git.pr': return c.createPullRequest(a.params);
    case 'git.prStatuses': return c.resolvePullRequestStatuses(a.params);
    // Workspace daemon settings and model registry.
    case 'settings.defaults': return c.getDefaultSettings();
    case 'settings.updateDefaults': return c.updateDefaultSettings(a.patch);
    case 'models.list': return c.listModels();
    case 'models.custom.list': return c.customModels.list();
    case 'models.custom.save': return c.customModels.save(a.input, a.target);
    case 'models.custom.remove': return c.customModels.remove(a.target);
    // Extensions (all modelled as first-class native screens).
    case 'mcp.list': return c.mcp.listServers();
    case 'mcp.tools': return c.mcp.listTools(a.name);
    case 'mcp.add': return c.mcp.addServer(a.input);
    case 'mcp.toggle': return c.mcp.toggleServer(a.name, a.enabled);
    case 'mcp.remove': return c.mcp.removeServer(a.name);
    case 'mcp.auth': return c.mcp.authenticateServer(a.name);
    case 'mcp.cancelAuth': return c.mcp.cancelAuth(a.name);
    case 'mcp.clearAuth': return c.mcp.clearAuth(a.name);
    case 'skills.list': return c.skills.list(a.cwd);
    case 'skills.toggle': return c.skills.setDisabled(a.input, a.cwd);
    case 'commands.list': return c.commands.list(a.cwd);
    case 'commands.forSession': return c.commands.listForSession(a.id);
    case 'plugins.marketplaces': return c.plugins.listMarketplaces();
    case 'plugins.marketplaceAdd': return c.plugins.addMarketplace(a.repo);
    case 'plugins.marketplaceRemove': return c.plugins.removeMarketplace(a.name);
    case 'plugins.marketplaceUpdate': return c.plugins.updateMarketplace(a.name);
    case 'plugins.available': return c.plugins.listAvailable();
    case 'plugins.installed': return c.plugins.listInstalled();
    case 'plugins.install': return c.plugins.install(a.input);
    case 'plugins.uninstall': return c.plugins.uninstall(a.id, a.scope);
    case 'plugins.toggle': return c.plugins.setEnabled(a.id, a.scope, a.enabled);
    case 'plugins.update': return c.plugins.updatePlugin(a.id, a.scope);
    // Automations and mission orchestration.
    case 'automations.list': return c.automations.list();
    case 'automations.run': return c.automations.run(a.id);
    case 'automations.pause': return c.automations.pause(a.id);
    case 'automations.resume': return c.automations.resume(a.id);
    case 'automations.history': return c.automations.history(a.id, a.limit, a.offset);
    case 'missions.snapshot': return c.missions.snapshot(a.id);
    // Terminal sidecar.
    case 'terminal.connect': {
      terminal?.dispose();
      terminal = c.openTerminalClient();
      terminal.onStatus(status => publish('terminal.status', status));
      terminal.onEvent(event => publish('terminal.event', event));
      return terminal.connect();
    }
    case 'terminal.list': return terminal?.list(a.id);
    case 'terminal.create': return terminal?.create(a.id, a.params);
    case 'terminal.write': return terminal?.write(a.id, a.terminalId, a.data);
    case 'terminal.resize': return terminal?.resize(a.id, a.terminalId, a.cols, a.rows);
    case 'terminal.close': return terminal?.close(a.id, a.terminalId);
    default: throw new Error('Unsupported backend operation: ' + op);
  }
}

window.__nativeReceive = raw => {
  let msg: Request;
  try { msg = JSON.parse(raw) as Request; } catch { return; }
  if (typeof msg.id !== 'string' || typeof msg.op !== 'string') return;
  void dispatch(msg.op, msg.args ?? {}).then(
    result => post({ id: msg.id, ok: true, result }),
    error => post({ id: msg.id, ok: false, error: error instanceof Error ? error.message : String(error) })
  );
};
publish('host.ready', { protocol: 1 });
