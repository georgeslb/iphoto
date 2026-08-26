import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { User } from 'lucide-react';
import { useLocale } from '../LocaleContext.jsx'; // Import useLocale
import { usePersons } from '../hooks/usePersons';

export default function Persons() {
  const { messages } = useLocale(); // Use the useLocale hook
  const navigate = useNavigate();
  const { persons, setPersons } = usePersons();
  const [editingId, setEditingId] = useState(null);
  const [newName, setNewName] = useState('');

  const handleNameClick = (person) => {
    setEditingId(person.id);
    setNewName(person.name || '');
  };

  const handleNameChange = (e) => {
    setNewName(e.target.value);
  };

  const handleNameSubmit = (person) => {
    const updatedPerson = { ...person, name: newName };

    fetch(`/api/faces/person/${person.id}`, {
      method: 'PUT',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(updatedPerson),
    })
    .then(response => {
      if (!response.ok) {
        throw new Error(messages["networkResponseNotOk"]);
      }
      // If response is OK, optimistically update the UI
      setPersons(persons.map(p => (p.id === person.id ? updatedPerson : p)));
      setEditingId(null);
    })
    .catch(error => {
      console.error(messages["errorUpdatingPerson"], error);
      // Optionally, revert the change in the UI if the update fails
      setEditingId(null);
    });
  };

  return (
    <div className="content-body">
      <div className="photo-grid">
        {persons.map(person => (
          <div key={person.id} className="person-item">
            {person.coverPhoto?.image ? (
              <img
                src={person.coverPhoto.image}
                alt={person.name || messages["unknownPerson"]}
                onClick={() => navigate(`/persons/${person.id}`)}
              />
            ) : (
              <div
                className="person-item-placeholder"
                role="img"
                aria-label={person.name || messages["unknownPerson"]}
                onClick={() => navigate(`/persons/${person.id}`)}
              >
                <User size={40} />
              </div>
            )}
            {editingId === person.id ? (
              <input
                type="text"
                className="person-name-input"
                value={newName}
                onChange={handleNameChange}
                onBlur={() => handleNameSubmit(person)}
                onKeyDown={(e) => e.key === 'Enter' && handleNameSubmit(person)}
                autoFocus
              />
            ) : (
              <div 
                className={`person-name ${!person.name ? 'person-name-placeholder' : ''}`}
                onClick={() => handleNameClick(person)}
              >
                {person.name || messages["whoIsThis"]}
              </div>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}