import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import './Login.css'; // Assuming you'll create a Login.css for styling
import { useLocale } from '../LocaleContext.jsx'; // Import useLocale

function Login({ checkAuthStatus }) {
    const { messages } = useLocale(); // Use the useLocale hook
    const [username, setUsername] = useState('');
    const [password, setPassword] = useState('');
    const [error, setError] = useState('');
    const navigate = useNavigate();

    const handleSubmit = async (event) => {
        event.preventDefault();
        setError('');

        try {
            const response = await fetch('/api/login', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/x-www-form-urlencoded',
                },
                body: `username=${encodeURIComponent(username)}&password=${encodeURIComponent(password)}`,
            });

            if (response.ok) {
                // Login successful: re-verify the session so useAuth's isAuthenticated flips to
                // true (it no longer re-checks on every navigation, only on demand like this),
                // then navigate home. Without this, isAuthenticated stays false and the
                // redirect effect in useAuth.js immediately bounces back to /login.
                if (checkAuthStatus) {
                    await checkAuthStatus();
                }
                navigate('/');
            } else {
                // Login failed
                setError(messages["invalidCredentialsError"]);
            }
        } catch (err) {
            setError(messages["loginGenericError"]);
            console.error('Login error:', err);
        }
    };

    return (
        <div className="login-container">
            <form onSubmit={handleSubmit} className="login-form">
                <h2>{messages["loginTitle"]}</h2>
                {error && <p className="error-message">{error}</p>}
                <div className="form-group">
                    <label htmlFor="username">{messages["usernameLabel"]}</label>
                    <input
                        type="text"
                        id="username"
                        value={username}
                        onChange={(e) => setUsername(e.target.value)}
                        required
                    />
                </div>
                <div className="form-group">
                    <label htmlFor="password">{messages["passwordLabel"]}</label>
                    <input
                        type="password"
                        id="password"
                        value={password}
                        onChange={(e) => setPassword(e.target.value)}
                        required
                    />
                </div>
                <button type="submit" className="login-button">{messages["loginButton"]}</button>
            </form>
        </div>
    );
}

export default Login;