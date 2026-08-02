import { useState, useCallback } from 'react';
import { useLocale } from '../LocaleContext.jsx'; // Import useLocale

export function useDuplicateManagement() {
  const { messages } = useLocale(); // Use the useLocale hook
  const [duplicatesPage, setDuplicatesPage] = useState(0);
  const [currentDuplicateGroup, setCurrentDuplicateGroup] = useState(null);
  const [isLastDuplicateGroup, setIsLastDuplicateGroup] = useState(false);

  const fetchDuplicates = useCallback(async (pageNum) => {
    try {
      const res = await fetch(`/api/photos/duplicates?page=${pageNum}&size=1`);
      if (res.ok) {
        const data = await res.json();
        if (data.content && data.content.length > 0) {
          const group = data.content[0];
          setCurrentDuplicateGroup(group);
          setDuplicatesPage(pageNum);
          setIsLastDuplicateGroup(data.last === undefined ? true : data.last);
          window.history.replaceState(null, '', `/duplicates/${group.hash}?page=${pageNum}`);
        } else if (pageNum > 0) {
          fetchDuplicates(pageNum - 1);
        } else {
          setCurrentDuplicateGroup(null);
          setIsLastDuplicateGroup(true);
          setDuplicatesPage(0);
          window.history.replaceState(null, '', `/duplicates`);
        }
      }
    } catch(err) { console.error(messages["fetchDuplicatesFailed"], err); }
  }, [messages]);

  const handleFinishComparison = useCallback(async () => {
    if (!currentDuplicateGroup) return;
    try {
      const res = await fetch('/api/photos/duplicates/finishChanges', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ hash: currentDuplicateGroup.hash })
      });
      if (res.ok) {
        await fetchDuplicates(duplicatesPage);
      } else {
        throw new Error(messages["failedToFinishComparison"]);
      }
    } catch (err) {
      alert(messages["errorAlert"](err.message));
    }
  }, [currentDuplicateGroup, duplicatesPage, fetchDuplicates, messages]);

  return {
    duplicatesPage,
    setDuplicatesPage,
    currentDuplicateGroup,
    setCurrentDuplicateGroup,
    isLastDuplicateGroup,
    setIsLastDuplicateGroup,
    fetchDuplicates,
    handleFinishComparison,
  };
}