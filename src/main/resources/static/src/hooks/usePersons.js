import { useState, useEffect, useCallback } from 'react';
import { useLocale } from '../LocaleContext.jsx';

export function usePersons() {
  const { messages } = useLocale();
  const [persons, setPersons] = useState([]);
  const [loading, setLoading] = useState(true);

  const fetchPersons = useCallback(() => {
    setLoading(true);
    return fetch('/api/faces/persons')
      .then(response => response.json())
      .then(data => {
        setPersons(Array.isArray(data) ? data : (data.content || []));
        setLoading(false);
      })
      .catch(error => {
        console.error(messages["errorFetchingPersons"], error);
        setLoading(false);
      });
  }, [messages]);

  useEffect(() => {
    fetchPersons();
  }, [fetchPersons]);

  return { persons, setPersons, loading, fetchPersons };
}
