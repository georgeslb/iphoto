import { useCallback } from 'react';
import { useLocale } from '../LocaleContext.jsx';

export function useSpaceActions(refreshAndMaintainScroll, selectedIds, setSelectedIds) {
  const { messages } = useLocale();

  const handleRestoreSpaces = useCallback(async (ids) => {
    const res = await fetch('/api/spaces/garbage/restore', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(ids) });
    if (res.ok) {
      setSelectedIds([]);
      await refreshAndMaintainScroll();
    }
  }, [refreshAndMaintainScroll, setSelectedIds]);

  const handlePermanentDeleteSpaces = useCallback(async (ids) => {
    if (!window.confirm(messages["permanentDeleteConfirmation"](ids.length))) return;
    const res = await fetch('/api/spaces/garbage/permanent', { method: 'DELETE', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(ids) });
    if (res.ok) {
      setSelectedIds([]);
      await refreshAndMaintainScroll();
    }
  }, [refreshAndMaintainScroll, setSelectedIds, messages]);

  return {
    handleRestoreSpaces,
    handlePermanentDeleteSpaces,
  };
}