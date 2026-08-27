import React, { useEffect, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { ChevronLeft, ChevronRight, User } from 'lucide-react';
import { useLocale } from '../LocaleContext.jsx';
import { usePersons } from '../hooks/usePersons';

// Horizontal, arrow-scrollable strip of every person, shown above a selected person's photos
// (PersonDetail.jsx) so switching between people doesn't require going back to the /persons grid.
export default function PersonStrip({ selectedPersonId, refreshToken }) {
  const { messages } = useLocale();
  const navigate = useNavigate();
  const { persons, fetchPersons } = usePersons();
  const trackRef = useRef(null);
  const isFirstRenderRef = useRef(true);

  // usePersons() only fetches once on its own mount — PersonDetail.jsx bumps refreshToken after
  // a rename/merge (usePersonRename.js) that could have deleted a merged-away person from this
  // list or changed someone's cover photo, which this component's own internal fetch has no way
  // to know about otherwise. Skips the very first render so a caller passing an initial
  // refreshToken value (e.g. 0) doesn't trigger a redundant duplicate of usePersons' own
  // mount-time fetch.
  useEffect(() => {
    if (isFirstRenderRef.current) {
      isFirstRenderRef.current = false;
      return;
    }
    fetchPersons();
    // eslint-disable-next-line react-hooks/exhaustive-deps -- fetchPersons intentionally omitted:
    // it's stable (see usePersons), and this effect should only re-run when refreshToken changes,
    // not merely because usePersons() re-rendered with a fresh function identity.
  }, [refreshToken]);

  const scrollByAmount = (amount) => {
    trackRef.current?.scrollBy({ left: amount, behavior: 'smooth' });
  };

  if (persons.length === 0) return null;

  return (
    <div className="person-strip">
      <button
        type="button"
        className="person-strip-arrow"
        onClick={() => scrollByAmount(-300)}
        aria-label={messages["scrollPersonsLeft"]}
      >
        <ChevronLeft size={20}/>
      </button>
      <div className="person-strip-track" ref={trackRef}>
        {persons.map(person => (
          <div
            key={person.id}
            className={`person-strip-item ${person.id === selectedPersonId ? 'active' : ''}`}
            onClick={() => navigate(`/persons/${person.id}`)}
          >
            {person.coverPhoto?.image ? (
              <img src={person.coverPhoto.image} alt={person.name || messages["unknownPerson"]}/>
            ) : (
              <div className="person-strip-placeholder" role="img" aria-label={person.name || messages["unknownPerson"]}>
                <User size={20}/>
              </div>
            )}
            {/* No "Who is this?" placeholder here — an unnamed person still shows up (this strip
                lists everyone, like before), just without a label underneath. */}
            {person.name && <span>{person.name}</span>}
          </div>
        ))}
      </div>
      <button
        type="button"
        className="person-strip-arrow"
        onClick={() => scrollByAmount(300)}
        aria-label={messages["scrollPersonsRight"]}
      >
        <ChevronRight size={20}/>
      </button>
    </div>
  );
}
