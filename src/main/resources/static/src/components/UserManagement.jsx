import React, { useState, useEffect } from 'react';
import './UserManagement.css'; // For styling

function UserManagement() {
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
                setError('Failed to fetch users.');
            }
        } catch (err) {
            setError('An error occurred while fetching users.');
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
                setMessage('User created successfully!');
                setNewUser({ username: '', password: '', role: 'USER' });
                fetchUsers(); // Refresh the list
            } else {
                const errorData = await response.json();
                setError(errorData.message || 'Failed to create user.');
            }
        } catch (err) {
            setError('An error occurred while creating user.');
            console.error('Create user error:', err);
        }
    };

    const handleDeleteUser = async (userId) => {
        if (!window.confirm('Are you sure you want to delete this user?')) {
            return;
        }
        setError('');
        setMessage('');
        try {
            const response = await fetch(`/api/users/${userId}`, {
                method: 'DELETE',
            });

            if (response.ok) {
                setMessage('User deleted successfully!');
                fetchUsers(); // Refresh the list
            } else {
                setError('Failed to delete user.');
            }
        } catch (err) {
            setError('An error occurred while deleting user.');
            console.error('Delete user error:', err);
        }
    };

    const handleResetPassword = async (userId) => {
        const newPassword = window.prompt('Enter new password:');
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
                setMessage('Password reset successfully!');
            } else {
                setError('Failed to reset password.');
            }
        } catch (err) {
            setError('An error occurred while resetting password.');
            console.error('Reset password error:', err);
        }
    };

    const handleActivateFaceRecognition = async () => {
        setError('');
        setMessage('');
        try {
            const response = await fetch('/admin/activateFaceRecognition');
            if (response.ok) {
                setMessage('Face recognition activated successfully!');
            } else {
                setError('Failed to activate face recognition.');
            }
        } catch (err) {
            setError('An error occurred while activating face recognition.');
            console.error('Activate face recognition error:', err);
        }
    };

    const handleUpdateImageTaskToDo = async () => {
        setError('');
        setMessage('');
        try {
            const response = await fetch('/admin/updateImageTaskToDo');
            if (response.ok) {
                setMessage('Image task updated successfully!');
            } else {
                setError('Failed to update image task.');
            }
        } catch (err) {
            setError('An error occurred while updating image task.');
            console.error('Update image task error:', err);
        }
    };

    return (
        <div className="user-management-container">
            <h2>User Management</h2>

            {error && <p className="error-message">{error}</p>}
            {message && <p className="success-message">{message}</p>}

            <h3>Create New User</h3>
            <form onSubmit={handleCreateUser} className="create-user-form">
                <div className="form-group">
                    <label htmlFor="newUsername">Username:</label>
                    <input
                        type="text"
                        id="newUsername"
                        value={newUser.username}
                        onChange={(e) => setNewUser({ ...newUser, username: e.target.value })}
                        required
                    />
                </div>
                <div className="form-group">
                    <label htmlFor="newPassword">Password:</label>
                    <input
                        type="password"
                        id="newPassword"
                        value={newUser.password}
                        onChange={(e) => setNewUser({ ...newUser, password: e.target.value })}
                        required
                    />
                </div>
                <div className="form-group">
                    <label htmlFor="newRole">Role:</label>
                    <select
                        id="newRole"
                        value={newUser.role}
                        onChange={(e) => setNewUser({ ...newUser, role: e.target.value })}
                    >
                        <option value="USER">USER</option>
                        <option value="ADMIN">ADMIN</option>
                    </select>
                </div>
                <button type="submit">Create User</button>
            </form>

            <h3>Existing Users</h3>
            <table className="users-table">
                <thead>
                    <tr>
                        <th>ID</th>
                        <th>Username</th>
                        <th>Role</th>
                        <th>Actions</th>
                    </tr>
                </thead>
                <tbody>
                    {users.map((user) => (
                        <tr key={user.id}>
                            <td>{user.id}</td>
                            <td>{user.username}</td>
                            <td>{user.role}</td>
                            <td>
                                <button onClick={() => handleResetPassword(user.id)}>Reset Password</button>
                                <button onClick={() => handleDeleteUser(user.id)} disabled={user.role === 'ADMIN' && users.filter(u => u.role === 'ADMIN').length === 1}>Delete</button>
                            </td>
                        </tr>
                    ))}
                </tbody>
            </table>

            <h3>Admin Operations</h3>
            <div className="admin-operations">
                <button onClick={handleActivateFaceRecognition}>Activate Face Recognition</button>
                <button onClick={handleUpdateImageTaskToDo}>Update Image Task To Do</button>
            </div>
        </div>
    );
}

export default UserManagement;