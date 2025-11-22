import { Outlet, NavLink, useNavigate } from 'react-router-dom';
import { useAuthStore } from '../stores/authStore';
import './Layout.css';

export default function Layout() {
  const { user, logout } = useAuthStore();
  const navigate = useNavigate();

  const handleLogout = () => {
    logout();
    navigate('/login');
  };

  return (
    <div className="layout">
      <header className="header">
        <div className="header-content">
          <nav className="nav">
            {[
              { to: '/devices', label: 'Устройства', sub: 'Phone Farm' },
              { to: '/tasks', label: 'Задачи', sub: 'Scenario Flow' },
              { to: '/logs', label: 'Логи', sub: 'Realtime' },
              { to: '/artifacts', label: 'Артефакты', sub: 'Evidence' },
            ].map((item) => (
              <NavLink
                key={item.to}
                to={item.to}
                className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}
              >
                <span>{item.label}</span>
                <small>{item.sub}</small>
              </NavLink>
            ))}
          </nav>

          <div className="user-menu">
            <div className="user-chip">
              <span className="user-dot" />
              <span className="username">{user?.username}</span>
            </div>
            <button onClick={handleLogout} className="logout-btn">
              Выход
            </button>
          </div>
        </div>
      </header>
      <main className="main-content">
        <Outlet />
      </main>
    </div>
  );
}

