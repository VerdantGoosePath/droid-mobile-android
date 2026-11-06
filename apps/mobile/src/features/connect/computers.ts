import { Capacitor, CapacitorHttp } from '@capacitor/core';
import { factoryRelayTarget } from '@droidmobile/daemon-client';

const COMPUTERS_API = 'https://api.factory.ai/api/v0/computers';
const EU_COMPUTERS_API = 'https://api.eu.factory.ai/api/v0/computers';

export interface FactoryComputer {
  id: string;
  name: string;
  relayClientUrl: string;
  status: string;
  /** Secure-channel identity advertised by Factory. The current client does not negotiate E2EE. */
  hasComputerIdentity: boolean;
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
      hasComputerIdentity: data.computerIdentity !== null && data.computerIdentity !== undefined,
    });
  }
  return computers;
}

/**
 * Uses Factory's documented GET /api/v0/computers with the user's own API key.
 * CapacitorHttp avoids browser-origin CORS restrictions on Android without a proxy.
 * No response (or token) is cached or logged.
 */
export async function listFactoryComputers(
  apiKey: string,
  region: 'global' | 'eu' = 'global',
): Promise<FactoryComputer[]> {
  const url = region === 'eu' ? EU_COMPUTERS_API : COMPUTERS_API;
  const headers = { Authorization: `Bearer ${apiKey}` };
  let status: number;
  let payload: unknown;
  if (Capacitor.isNativePlatform()) {
    const response = await CapacitorHttp.get({ url, headers, responseType: 'json' });
    status = response.status;
    payload = response.data;
  } else {
    const response = await fetch(url, { headers, cache: 'no-store' });
    status = response.status;
    if (!response.ok) throw new Error('Could not retrieve Factory computers');
    payload = await response.json() as unknown;
  }
  if (status !== 200) throw new Error('Could not retrieve Factory computers');
  return decodeComputers(payload);
}
