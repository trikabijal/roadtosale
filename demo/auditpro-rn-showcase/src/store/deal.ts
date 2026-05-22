import { create } from 'zustand';
import { Deal, DealSetup, FlagSource, FlagValue } from '../types';
import { BackendService } from '../services/backend/BackendService';

type Status = 'idle' | 'loading' | 'ready';

type DealStore = {
  status: Status;
  deal: Deal | null;
  // The store is bound to a backend at startup so actions can write through.
  backend: BackendService | null;
  bind: (backend: BackendService) => void;

  startDeal: (setup: DealSetup) => Promise<Deal>;
  resetDeal: () => void;
  setCurrentStep: (step: number) => Promise<void>;

  setFlag: (id: string, value: FlagValue, source: FlagSource) => Promise<void>;
  setFeature: (id: string, value: 'pass' | 'fail' | null) => Promise<void>;
  setUseCase: (id: string, value: boolean) => Promise<void>;
  setPhoto: (slot: string, uri: string | null) => Promise<void>;
  setNote: (key: 'trade', value: string) => Promise<void>;
  setTimestamp: (key: 'pencil' | 'boSigned' | 'mgrTo' | 'fiHandoff', value: number | null) => Promise<void>;
  setBoolean: (key: 'pencilMarked' | 'boCaptured' | 'fiHandoff' | 'routeCompleted', value: boolean) => Promise<void>;
  markCompleted: () => Promise<void>;
};

// Loud rather than silent: any mutating action assumes a deal + backend have
// already been bound. Hitting these means a screen rendered without an active
// audit (programmer error, not a recoverable runtime state) — surfacing it
// via the ErrorBoundary is more useful than ghost no-ops on a real device.
function requireBound(
  state: { deal: Deal | null; backend: BackendService | null },
  action: string
): { deal: Deal; backend: BackendService } {
  if (!state.backend) {
    throw new Error(`useDealStore.${action}: backend not bound — call bind() at app init`);
  }
  if (!state.deal) {
    throw new Error(`useDealStore.${action}: no active deal — call startDeal() first`);
  }
  return { deal: state.deal, backend: state.backend };
}

export const useDealStore = create<DealStore>((set, get) => ({
  status: 'idle',
  deal: null,
  backend: null,

  bind: (backend) => set({ backend, status: 'ready' }),

  startDeal: async (setup) => {
    const backend = get().backend;
    if (!backend) throw new Error('useDealStore.startDeal: backend not bound');
    const deal = await backend.createDeal(setup);
    set({ deal });
    await backend.logEvent({ dealId: deal.id, type: 'deal_started', timestamp: Date.now() });
    return deal;
  },

  resetDeal: () => set({ deal: null }),

  setCurrentStep: async (step) => {
    const { deal, backend } = requireBound(get(), 'setCurrentStep');
    const updated = await backend.updateDeal(deal.id, { currentStep: step });
    set({ deal: updated });
  },

  setFlag: async (id, value, source) => {
    const { deal, backend } = requireBound(get(), 'setFlag');
    const updated = await backend.updateDeal(deal.id, {
      flags: {
        ...deal.flags,
        [id]: { value, source, setAt: Date.now() },
      },
    });
    set({ deal: updated });
    await backend.logEvent({
      dealId: deal.id,
      type: 'flag_set',
      payload: { id, value, source },
      timestamp: Date.now(),
    });
  },

  setFeature: async (id, value) => {
    const { deal, backend } = requireBound(get(), 'setFeature');
    const updated = await backend.updateDeal(deal.id, {
      features: { ...deal.features, [id]: value },
    });
    set({ deal: updated });
  },

  setUseCase: async (id, value) => {
    const { deal, backend } = requireBound(get(), 'setUseCase');
    const updated = await backend.updateDeal(deal.id, {
      useCases: { ...deal.useCases, [id]: value },
    });
    set({ deal: updated });
  },

  setPhoto: async (slot, uri) => {
    const { deal, backend } = requireBound(get(), 'setPhoto');
    const updated = await backend.updateDeal(deal.id, {
      photos: { ...deal.photos, [slot]: uri },
    });
    set({ deal: updated });
    if (uri) {
      await backend.logEvent({
        dealId: deal.id,
        type: 'photo_captured',
        payload: { slot, uri },
        timestamp: Date.now(),
      });
    }
  },

  setNote: async (key, value) => {
    const { deal, backend } = requireBound(get(), 'setNote');
    const updated = await backend.updateDeal(deal.id, {
      notes: { ...deal.notes, [key]: value },
    });
    set({ deal: updated });
  },

  setTimestamp: async (key, value) => {
    const { deal, backend } = requireBound(get(), 'setTimestamp');
    const updated = await backend.updateDeal(deal.id, {
      timestamps: { ...deal.timestamps, [key]: value },
    });
    set({ deal: updated });
  },

  setBoolean: async (key, value) => {
    const { deal, backend } = requireBound(get(), 'setBoolean');
    const updated = await backend.updateDeal(deal.id, { [key]: value } as Partial<Deal>);
    set({ deal: updated });
  },

  markCompleted: async () => {
    const { deal, backend } = requireBound(get(), 'markCompleted');
    const updated = await backend.updateDeal(deal.id, { status: 'completed' });
    set({ deal: updated });
    await backend.logEvent({
      dealId: deal.id,
      type: 'deal_completed',
      timestamp: Date.now(),
    });
  },
}));
