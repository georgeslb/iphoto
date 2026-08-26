import React, { useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { ChevronLeft, ChevronRight, User } from 'lucide-react';
import { useLocale } from '../LocaleContext.jsx';
import { usePersons } from '../hooks/usePersons';

// Horizontal, arrow-scrollable strip of every person, shown above a selected person's photos
// (PersonDetail.jsx) so switching between people doesn't require going back to the /persons grid.
export default function PersonStrip({ selectedPersonId }) {
  const { messages } = useLocale();
  const navigate = useNavigate();
  const { persons } = usePersons();
  const trackRef = useRef(null);

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
            <span>{person.name || messages["whoIsThis"]}</span>
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
