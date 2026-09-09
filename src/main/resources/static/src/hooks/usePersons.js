import { useState, useEffect, useCallback, useRef } from 'react';
import { useLocale } from '../LocaleContext.jsx';

const PAGE_SIZE = 50;

// GET /api/faces/persons is a real Page<PersonDTO> (@PageableDefault(size = 50)), so anyone with
// more than one page of people needs real pagination here, not just a single unpaginated fetch.
// fetchPersons() (re)loads page 0 and replaces the list (used for the initial mount and after a
// rename/merge, where a full refresh is simplest and correct); loadMore() appends the next page,
// meant to be driven by an IntersectionObserver sentinel the same way day-indexed photo grids do.
export function usePersons() {
  const { messages } = useLocale();
  const [persons, setPersons] = useState([]);
  const [loading, setLoading] = useState(true);
  const [hasMore, setHasMore] = useState(false);
  const pageRef = useRef(0);

  const fetchPage = useCallback((pageNumber, append) => {
    setLoading(true);
    return fetch(`/api/faces/persons?page=${pageNumber}&size=${PAGE_SIZE}`)
      .then(response => response.json())
      .then(data => {
        const content = Array.isArray(data) ? data : (data.content || []);
        const isLast = Array.isArray(data)
          ? true
          : (data.page ? data.page.number >= data.page.totalPages - 1 : data.last !== false);
        pageRef.current = pageNumber;
        setPersons(prev => (append ? [...prev, ...content] : content));
        setHasMore(!isLast);
        setLoading(false);
      })
      .catch(error => {
        console.error(messages["errorFetchingPersons"], error);
        setLoading(false);
      });
  }, [messages]);

  const fetchPersons = useCallback(() => fetchPage(0, false), [fetchPage]);

  const loadMore = useCallback(() => {
    if (loading || !hasMore) return;
    fetchPage(pageRef.current + 1, true);
  }, [fetchPage, loading, hasMore]);

  useEffect(() => {
    fetchPersons();
  }, [fetchPersons]);

  return { persons, setPersons, loading, hasMore, loadMore, fetchPersons };
}
