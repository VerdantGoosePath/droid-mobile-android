import { Capacitor, CapacitorHttp } from '@capacitor/core';
import { factoryRelayTarget } from '@droidmobile/daemon-client';
import type { PinnedComputerIdentity } from '@droidmobile/daemon-client';

const COMPUTERS_API = 'https://api.factory.ai/api/v0/computers';
const EU_COMPUTERS_API = 'https://api.eu.factory.ai/api/v0/computers';

export interface FactoryComputer {
  id: string;
  name: string;
  relayClientUrl: string;
  status: string;
  hasComputerIdentity: boolean;
}

function parseIdentity(raw: unknown): PinnedComputerIdentity | null {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null;
  const data = raw as Record<string, unknown>;
  if (data.algorithm !== 'X25519' || typeof data.publicKey !== 'string' ||
      typeof data.keyId !== 'string') return null;
  return { algorithm: 'X25519', publicKey: data.publicKey, keyId: data.keyId };
}

function decodeComputers(payload: unknown): FactoryComputer[] {
  if (typeof payload !== 'object' || payload === null) return [];
  const value = payload as Record<string, unknown>;
  if (!Array.isArray(value.computers)) return [];
  const computers: FactoryComputer[] = [];
  for (const item of value.computers) {
    if (typeof item !== 'object' || item === null) continue;
    const data = item as Record<string, unknown>;
    if (typeof data.id !== 'string' || typeof data.name !== 'string') continue;
    if (typeof data.relayClientUrl !== 'string') continue;
    const relay = factoryRelayTarget(data.relayClientUrl);
    if (relay === null || relay.computerId !== data.id.toLowerCase()) continue;
    computers.push({
      id: data.id,
      name: data.name,
      relayClientUrl: relay.url,
      status: typeof data.status === 'string' ? data.status : 'unknown',
      hasComputerIdentity: parseIdentity(data.computerIdentity) !== null,
    });
  }
  return computers;
}

async function getJson(url: string, apiKey: string): Promise<unknown> {
  const headers = { Authorization: 'Bearer ' + apiKey };
  if (Capacitor.isNativePlatform()) {
    const response = await CapacitorHttp.get({ url, headers, responseType: 'json' });
    if (response.status !== 200) throw new Error('Factory computer lookup failed');
    return response.data as unknown;
  }
  const response = await fetch(url, { headers, cache: 'no-store' });
  if (!response.ok) throw new Error('Factory computer lookup failed');
  return await response.json() as unknown;
}

/** Calls Factory's documented Computers API, not the untrusted WebSocket. */
export async function listFactoryComputers(
  apiKey: string, region: 'global' | 'eu' = 'global',
): Promise<FactoryComputer[]> {
  const url = region === 'eu' ? EU_COMPUTERS_API : COMPUTERS_API;
  return decodeComputers(await getJson(url, apiKey));
}

/**
 * Reads the pinned identity from the authenticated API on EVERY connection
 * attempt, including restore/reconnect and manually pasted /agent URLs.
 * Reject if the API cannot bind the identity and client URL to this computer.
 */
export async function getFactoryComputerIdentity(
  relayUrl: string, apiKey: string,
): Promise<PinnedComputerIdentity> {
  const target = factoryRelayTarget(relayUrl);
  if (!target || target.url !== relayUrl)
    throw new Error('Invalid Factory relay client URL');
  const regionApi = target.url.startsWith('wss://relay.eu.factory.ai/')
    ? EU_COMPUTERS_API : COMPUTERS_API;
  const payload = await getJson(regionApi + '/' + encodeURIComponent(target.computerId), apiKey);
  if (!payload || typeof payload !== 'object' || Array.isArray(payload))
    throw new Error('Factory did not return a computer record');
  const record = payload as Record<string, unknown>;
  const url = typeof record.relayClientUrl === 'string'
    ? factoryRelayTarget(record.relayClientUrl) : null;
  if (
    record.id !== target.computerId ||
    url === null ||
    url.url !== target.url
  ) throw new Error('Factory computer record does not match the relay URL');
  const identity = parseIdentity(record.computerIdentity);
  if (!identity) throw new Error('Computer has no X25519 identity; refusing plaintext relay');
  return identity;
}
