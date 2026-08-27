import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Check, User } from 'lucide-react';
import { useLocale } from '../LocaleContext.jsx'; // Import useLocale
import { usePersons } from '../hooks/usePersons';

export default function Persons() {
  const { messages } = useLocale(); // Use the useLocale hook
  const navigate = useNavigate();
  const { persons, setPersons, fetchPersons } = usePersons();
  const [editingId, setEditingId] = useState(null);
  const [newName, setNewName] = useState('');
  const [selectedIds, setSelectedIds] = useState([]);
  const [isMerging, setIsMerging] = useState(false);

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

  const toggleSelected = (personId) => {
    setSelectedIds(prev => (
      prev.includes(personId) ? prev.filter(id => id !== personId) : [...prev, personId].slice(-2)
    ));
  };

  const handleMerge = async () => {
    if (selectedIds.length !== 2) return;
    if (!window.confirm(messages["confirmMergePersons"])) return;

    setIsMerging(true);
    try {
      const response = await fetch('/api/faces/persons/merge', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ personIds: selectedIds }),
      });
      if (!response.ok) {
        throw new Error(messages["failedToMergePersons"]);
      }
      setSelectedIds([]);
      await fetchPersons();
    } catch (error) {
      console.error(messages["failedToMergePersons"], error);
      alert(messages["failedToMergePersons"]);
    } finally {
      setIsMerging(false);
    }
  };

  return (
    <div className="content-body">
      {selectedIds.length > 0 && (
        <div className="person-merge-bar">
          <span>{messages["personsSelectedForMerge"](selectedIds.length)}</span>
          <div>
            <button className="btn" onClick={() => setSelectedIds([])}>{messages["cancel"]}</button>
            <button
              className="btn btn-primary"
              disabled={selectedIds.length !== 2 || isMerging}
              onClick={handleMerge}
            >
              {isMerging ? messages["mergingPersons"] : messages["mergePersonsButton"]}
            </button>
          </div>
        </div>
      )}
      <div className="photo-grid">
        {persons.map(person => {
          const isSelected = selectedIds.includes(person.id);
          return (
            <div key={person.id} className={`person-item ${isSelected ? 'selected' : ''}`}>
              <div
                className="person-select-overlay"
                title={messages["selectPersonForMerge"]}
                onClick={(e) => { e.stopPropagation(); toggleSelected(person.id); }}
              >
                <div className={`check-circle ${isSelected ? 'checked' : ''}`}>
                  {isSelected && <Check size={14} />}
                </div>
              </div>
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
          );
        })}
      </div>
    </div>
  );
}
