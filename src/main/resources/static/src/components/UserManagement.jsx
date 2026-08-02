import React, { useState, useEffect } from 'react';
import './UserManagement.css'; // For styling
import { useLocale } from '../LocaleContext.jsx'; // Import useLocale

function UserManagement() {
    const { messages } = useLocale(); // Use the useLocale hook
    const [users, setUsers] = useState([]);
    const [newUser, setNewUser] = useState({ username: '', password: '', role: 'USER' });
    const [error, setError] = useState('');
    const [message, setMessage] = useState('');

    useEffect(() => {
        fetchUsers();
    }, []);

    const fetchUsers = async () => {
        try {
            const response = await fetch('/api/users');
            if (response.ok) {
                const data = await response.json();
                setUsers(data);
            } else {
                setError(messages["failedToFetchUsers"]);
            }
        } catch (err) {
            setError(messages["errorFetchingUsers"]);
            console.error('Fetch users error:', err);
        }
    };

    const handleCreateUser = async (e) => {
        e.preventDefault();
        setError('');
        setMessage('');
        try {
            const response = await fetch('/api/users', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                },
                body: JSON.stringify(newUser),
            });

            if (response.ok) {
                setMessage(messages["userCreatedSuccessfully"]);
                setNewUser({ username: '', password: '', role: 'USER' });
                fetchUsers(); // Refresh the list
            } else {
                const errorData = await response.json();
                setError(errorData.message || messages["failedToCreateUser"]);
            }
        } catch (err) {
            setError(messages["errorCreatingUser"]);
            console.error('Create user error:', err);
        }
    };

    const handleDeleteUser = async (userId) => {
        if (!window.confirm(messages["deleteUserConfirmation"])) {
            return;
        }
        setError('');
        setMessage('');
        try {
            const response = await fetch(`/api/users/${userId}`, {
                method: 'DELETE',
            });

            if (response.ok) {
                setMessage(messages["userDeletedSuccessfully"]);
                fetchUsers(); // Refresh the list
            } else {
                setError(messages["failedToDeleteUser"]);
            }
        } catch (err) {
            setError(messages["errorDeletingUser"]);
            console.error('Delete user error:', err);
        }
    };

    const handleResetPassword = async (userId) => {
        const newPassword = window.prompt(messages["resetPasswordPrompt"]);
        if (!newPassword) return;

        setError('');
        setMessage('');
        try {
            const response = await fetch(`/api/users/${userId}/reset-password`, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                },
                body: JSON.stringify({ newPassword }),
            });

            if (response.ok) {
                setMessage(messages["passwordResetSuccessfully"]);
            } else {
                setError(messages["failedToResetPassword"]);
            }
        } catch (err) {
            setError(messages["errorResettingPassword"]);
            console.error('Reset password error:', err);
        }
    };

    const handleActivateFaceRecognition = async () => {
        setError('');
        setMessage('');
        try {
            const response = await fetch('/admin/activateFaceRecognition');
            if (response.ok) {
                setMessage(messages["faceRecognitionActivatedSuccessfully"]);
            } else {
                setError(messages["failedToActivateFaceRecognition"]);
            }
        } catch (err) {
            setError(messages["errorActivatingFaceRecognition"]);
            console.error('Activate face recognition error:', err);
        }
    };

    const handleUpdateImageTaskToDo = async () => {
        setError('');
        setMessage('');
        try {
            const response = await fetch('/admin/updateImageTaskToDo');
            if (response.ok) {
                setMessage(messages["imageTaskUpdatedSuccessfully"]);
            } else {
                setError(messages["failedToUpdateImageTask"]);
            }
        } catch (err) {
            setError(messages["errorUpdatingImageTask"]);
            console.error('Update image task error:', err);
        }
    };

    return (
        <div className="user-management-container">
            <h2>{messages["userManagementTitle"]}</h2>

            {error && <p className="error-message">{error}</p>}
            {message && <p className="success-message">{message}</p>}

            <h3>{messages["createNewUserTitle"]}</h3>
            <form onSubmit={handleCreateUser} className="create-user-form">
                <div className="form-group">
                    <label htmlFor="newUsername">{messages["usernameLabel"]}</label>
                    <input
                        type="text"
                        id="newUsername"
                        value={newUser.username}
                        onChange={(e) => setNewUser({ ...newUser, username: e.target.value })}
                        required
                    />
                </div>
                <div className="form-group">
                    <label htmlFor="newPassword">{messages["passwordLabel"]}</label>
                    <input
                        type="password"
                        id="newPassword"
                        value={newUser.password}
                        onChange={(e) => setNewUser({ ...newUser, password: e.target.value })}
                        required
                    />
                </div>
                <div className="form-group">
                    <label htmlFor="newRole">{messages["tableHeaderRole"]}:</label>
                    <select
                        id="newRole"
                        value={newUser.role}
                        onChange={(e) => setNewUser({ ...newUser, role: e.target.value })}
                    >
                        <option value="USER">USER</option>
                        <option value="ADMIN">ADMIN</option>
                    </select>
                </div>
                <button type="submit">{messages["createNewUserTitle"]}</button>
            </form>

            <h3>{messages["existingUsersTitle"]}</h3>
            <table className="users-table">
                <thead>
                    <tr>
                        <th>{messages["tableHeaderId"]}</th>
                        <th>{messages["tableHeaderUsername"]}</th>
                        <th>{messages["tableHeaderRole"]}</th>
                        <th>{messages["tableHeaderActions"]}</th>
                    </tr>
                </thead>
                <tbody>
                    {users.map((user) => (
                        <tr key={user.id}>
                            <td>{user.id}</td>
                            <td>{user.username}</td>
                            <td>{user.role}</td>
                            <td>
                                <button onClick={() => handleResetPassword(user.id)}>{messages["resetPasswordButton"]}</button>
                                <button onClick={() => handleDeleteUser(user.id)} disabled={user.role === 'ADMIN' && users.filter(u => u.role === 'ADMIN').length === 1}>{messages["deleteButton"]}</button>
                            </td>
                        </tr>
                    ))}
                </tbody>
            </table>

            <h3>{messages["adminOperationsTitle"]}</h3>
            <div className="admin-operations">
                <button onClick={handleActivateFaceRecognition}>{messages["activateFaceRecognition"]}</button>
                <button onClick={handleUpdateImageTaskToDo}>{messages["updateImageTaskToDo"]}</button>
            </div>
        </div>
    );
}

export default UserManagement;