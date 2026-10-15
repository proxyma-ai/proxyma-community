import { create } from 'zustand'

export type ConnectorOperationPhase = 'STOPPING_SYNC' | 'CLEARING_INDEX' | 'RESETTING' | 'DELETING_DATA'

export interface ConnectorOperation {
  operation: 'reindex' | 'delete'
  phase: ConnectorOperationPhase
}

interface ConnectorOperationState {
  operations: Record<string, ConnectorOperation>
  setOperation: (connectorId: string, op: ConnectorOperation) => void
  clearOperation: (connectorId: string) => void
}

export const useConnectorOperationStore = create<ConnectorOperationState>(set => ({
  operations: {},
  setOperation: (connectorId, op) => set(s => ({ operations: { ...s.operations, [connectorId]: op } })),
  clearOperation: (connectorId) => set(s => {
    const next = { ...s.operations }
    delete next[connectorId]
    return { operations: next }
  }),
}))

export const PHASE_LABELS: Record<ConnectorOperationPhase, string> = {
  STOPPING_SYNC: 'Stopping sync...',
  CLEARING_INDEX: 'Clearing index...',
  RESETTING: 'Resetting...',
  DELETING_DATA: 'Deleting data...',
}
