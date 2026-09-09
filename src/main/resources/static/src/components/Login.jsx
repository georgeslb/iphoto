import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import './Login.css'; // Assuming you'll create a Login.css for styling
import { useLocale } from '../LocaleContext.jsx'; // Import useLocale

// Chrome (and other browsers) never offered to save this login in its password manager - the
// <input>s had no name/autocomplete attributes at all, which Chrome's save-password heuristics
// rely on to identify a username/password pair (this matters even more for an AJAX/fetch-based
// login like this one, which never does a real page navigation - autocomplete="username"/
// "current-password" plus the real <form>'s onSubmit event and the post-login route change
// unmounting this component are what let Chrome recognize and offer to save the credentials).
function Login({ checkAuthStatus }) {
    const { messages } = useLocale(); // Use the useLocale hook
    const [username, setUsername] = useState('');
    const [password, setPassword] = useState('');
    const [rememberMe, setRememberMe] = useState(false);
    const [error, setError] = useState('');
    const navigate = useNavigate();

    const handleSubmit = async (event) => {
        event.preventDefault();
        setError('');

        try {
            // remember-me matches SecurityConfig's rememberMeParameter - only sent when checked
            // (Spring's RememberMeConfigurer treats the parameter's mere presence with a truthy
            // value as "on", so omitting it entirely when unchecked, rather than sending
            // "remember-me=false", is the correct way to say "not this time").
            const body = `username=${encodeURIComponent(username)}&password=${encodeURIComponent(password)}`
                + (rememberMe ? '&remember-me=true' : '');
            const response = await fetch('/api/login', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/x-www-form-urlencoded',
                },
                body,
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
                        name="username"
                        autoComplete="username"
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
                        name="password"
                        autoComplete="current-password"
                        value={password}
                        onChange={(e) => setPassword(e.target.value)}
                        required
                    />
                </div>
                <div className="form-group form-group-checkbox">
                    <label htmlFor="remember-me">
                        <input
                            type="checkbox"
                            id="remember-me"
                            checked={rememberMe}
                            onChange={(e) => setRememberMe(e.target.checked)}
                        />
                        {messages["rememberMeLabel"]}
                    </label>
                </div>
                <button type="submit" className="login-button">{messages["loginButton"]}</button>
            </form>
        </div>
    );
}

export default Login;