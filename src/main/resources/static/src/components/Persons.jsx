import React, { useState, useEffect } from 'react';

export default function Persons() {
  const [persons, setPersons] = useState([]);
  const [editingId, setEditingId] = useState(null);
  const [newName, setNewName] = useState('');

  useEffect(() => {
    fetch('/api/faces/persons')
      .then(response => response.json())
      .then(data => {
        setPersons(Array.isArray(data) ? data : (data.content || []));
      })
      .catch(error => console.error('Error fetching persons:', error));
  }, []);

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
        throw new Error('Network response was not ok');
      }
      // If response is OK, optimistically update the UI
      setPersons(persons.map(p => (p.id === person.id ? updatedPerson : p)));
      setEditingId(null);
    })
    .catch(error => {
      console.error('Error updating person:', error);
      // Optionally, revert the change in the UI if the update fails
      setEditingId(null);
    });
  };

  return (
    <div className="content-body">
      <div className="photo-grid">
        {persons.map(person => (
          <div key={person.id} className="person-item">
            <img src={person.coverPhoto?.image || ''} alt={person.name || 'Unknown person'} />
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
                {person.name || 'Who is this?'}
              </div>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}