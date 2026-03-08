import { useState } from 'react';
import { useAuth } from '../hooks/useAuth';
import LoginModal from './LoginModal';
import SignupModal from './SignupModal';

function Header({ sseConnected, wsConnected, demoMode, onToggleDemo, onTogglePanel, panelOpen, onReset }) {
  const { user, isAuthenticated, logout } = useAuth();
  const [showLogin, setShowLogin] = useState(false);
  const [showSignup, setShowSignup] = useState(false);

  return (
    <>
      <header className="header">
        <div className="header-left">
          <h1 className="logo" onClick={onReset} style={{ cursor: 'pointer' }}>Soccer Dashboard</h1>
          <span className="logo-subtitle">Real-time scores & System Design</span>
        </div>

        <div className="header-center">
          <button
            className={`demo-toggle ${demoMode ? 'active' : ''}`}
            onClick={onToggleDemo}
          >
            {demoMode ? 'Demo ON' : 'Demo Mode'}
          </button>

          <div className="connection-indicators">
            <span className={`indicator ${sseConnected ? 'connected' : 'disconnected'}`}>
              <span className="indicator-dot" />
              SSE
            </span>
            <span className={`indicator ${wsConnected ? 'connected' : 'disconnected'}`}>
              <span className="indicator-dot" />
              WS
            </span>
          </div>
        </div>

        <div className="header-right">
          {isAuthenticated ? (
            <div className="user-info">
              <span className="username">{user?.username}</span>
              <button className="btn btn-outline" onClick={logout}>Logout</button>
            </div>
          ) : (
            <div className="auth-buttons">
              <button className="btn btn-outline" onClick={() => setShowLogin(true)}>Login</button>
              <button className="btn btn-primary" onClick={() => setShowSignup(true)}>Sign Up</button>
            </div>
          )}
          <button className="panel-toggle" onClick={onTogglePanel}>
            {panelOpen ? 'Hide Panel' : 'Show Panel'}
          </button>
        </div>
      </header>

      {showLogin && <LoginModal onClose={() => setShowLogin(false)} onSwitchToSignup={() => { setShowLogin(false); setShowSignup(true); }} />}
      {showSignup && <SignupModal onClose={() => setShowSignup(false)} onSwitchToLogin={() => { setShowSignup(false); setShowLogin(true); }} />}

      {demoMode && (
        <div className="demo-banner">
          Demo Mode — Replaying Arsenal vs Chelsea, Man City vs Liverpool, Real Madrid vs Barcelona
        </div>
      )}
    </>
  );
}

export default Header;
